/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.singularitee.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Loads every shipped workspace under {@code examples/} through the real loader.
 *
 * <p>The examples are documentation people copy from: a typo in a step type, a
 * model config key or an {@code includes} path is a broken example, and nothing
 * else in the build reads these files. This walks them all and fails on the first
 * one that does not parse — including the {@code modular/} server and client
 * configs, whose {@code includes} are resolved for real against the models/,
 * pipelines/ and templates/ subdirectories.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class ExamplesWorkspaceTest {

  /** Model/pipeline fragments — only meaningful when pulled in by an includes: block. */
  private static final String FRAGMENTS = "examples/modular/models";

  private static final String FRAGMENT_PIPELINES = "examples/modular/pipelines";
  private static final String FRAGMENT_TEMPLATES = "examples/modular/templates";

  private static Path examplesDir() {
    // Surefire runs with the module as CWD; examples/ lives at the repo root.
    return Path.of("..", "examples").normalize();
  }

  private static boolean isFragment(Path p) {
    String s = p.toString().replace('\\', '/');
    return (
      s.contains(FRAGMENTS) || s.contains(FRAGMENT_PIPELINES) || s.contains(FRAGMENT_TEMPLATES)
    );
  }

  static Stream<Path> standaloneWorkspaces() throws IOException {
    Path root = examplesDir();
    if (!Files.isDirectory(root)) return Stream.of();
    try (var walk = Files.walk(root)) {
      return walk
        .filter(Files::isRegularFile)
        .filter(p -> p.getFileName().toString().endsWith(".yaml"))
        .filter(p -> !isFragment(p))
        // observability/ holds docker-compose and Grafana provisioning, not workspaces.
        .filter(p -> !p.toString().replace('\\', '/').contains("examples/observability"))
        .sorted()
        .toList()
        .stream();
    }
  }

  /**
   * Model fragments under {@code modular/models/}.
   *
   * <p>These are excluded from {@link #standaloneWorkspaces()} because they
   * declare no pipeline, so they are only ever exercised through whichever
   * server file happens to include them — which means a fragment nobody
   * currently includes is completely untested. They are documentation people
   * swap in by hand, so each is loaded here on its own.
   */
  static Stream<Path> modelFragments() throws IOException {
    Path root = examplesDir().resolve("modular/models");
    if (!Files.isDirectory(root)) return Stream.of();
    try (var walk = Files.walk(root)) {
      return walk
        .filter(Files::isRegularFile)
        .filter(p -> p.getFileName().toString().endsWith(".yaml"))
        .sorted()
        .toList()
        .stream();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modelFragments")
  void model_fragment_declares_exactly_one_model(Path yaml) throws IOException {
    var requests = YamlWorkspaceLoader.load(yaml);
    var declared = requests.models().size() + requests.remoteModels().size();

    // One model per fragment is the whole contract: they share a logical id, so
    // a server includes exactly one of them and a fragment declaring two would
    // quietly shadow something.
    assertThat(declared).as("%s should declare exactly one model", yaml).isEqualTo(1);
    assertThat(requests.pipelines())
      .as("%s is a model fragment — pipelines belong in modular/pipelines/", yaml)
      .isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("standaloneWorkspaces")
  void example_workspace_loads(Path yaml) throws IOException {
    var requests = YamlWorkspaceLoader.load(yaml);

    assertThat(requests).as("loader returned nothing for %s", yaml).isNotNull();
    boolean publishesNothing =
      requests.models().isEmpty() &&
      requests.pipelines().isEmpty() &&
      requests.remoteModels().isEmpty();
    assertThat(publishesNothing)
      .as("%s declares neither a model nor a pipeline — it would publish nothing", yaml)
      .isFalse();
  }

  /**
   * The config records ignore unknown properties, so "it loaded" is a weak signal:
   * a misspelt key parses fine and silently does nothing. These pin the steps the
   * pipeline examples exist to demonstrate down to the parsed proto.
   */
  @Test
  void tool_router_example_really_wires_a_tool_select_step() throws IOException {
    var pipeline = onlyPipeline("pipelines/tool-router.yaml");
    var step = pipeline
      .getStepsList()
      .stream()
      .filter(s -> s.getType() == io.gravitee.singularitee.protocol.StepType.STEP_TYPE_TOOL_SELECT)
      .findFirst()
      .orElseThrow(() -> new AssertionError("no tool_select step parsed"));

    assertThat(step.getToolSelectConfig().getModelId()).isEqualTo("tool-router");
    assertThat(step.getToolSelectConfig().getBatchSize()).isPositive();
    assertThat(step.getToolSelectConfig().getTrimDescriptions()).isTrue();
  }

  @Test
  void router_examples_really_wire_their_routing_strategies() throws IOException {
    assertThat(routeStrategyOf("pipelines/gliner-router.yaml")).isEqualTo(
      io.gravitee.singularitee.protocol.RoutingStrategy.ROUTING_STRATEGY_CLASSIFIER
    );
    assertThat(routeStrategyOf("pipelines/embedding-router.yaml")).isEqualTo(
      io.gravitee.singularitee.protocol.RoutingStrategy.ROUTING_STRATEGY_EMBEDDING_KNN
    );

    // embedding_knn routes on reference sentences — without them there is nothing to match against.
    var route = routeStepOf("pipelines/embedding-router.yaml");
    assertThat(route.getRouteConfig().getRulesList()).allSatisfy(rule ->
      assertThat(rule.getSentencesList()).isNotEmpty()
    );
  }

  private static io.gravitee.singularitee.protocol.Pipeline onlyPipeline(String relative)
    throws IOException {
    var requests = YamlWorkspaceLoader.load(examplesDir().resolve(relative));
    assertThat(requests.pipelines()).hasSize(1);
    return requests.pipelines().get(0);
  }

  private static io.gravitee.singularitee.protocol.PipelineStep routeStepOf(String relative)
    throws IOException {
    return onlyPipeline(relative)
      .getStepsList()
      .stream()
      .filter(s -> s.getType() == io.gravitee.singularitee.protocol.StepType.STEP_TYPE_ROUTE)
      .findFirst()
      .orElseThrow(() -> new AssertionError("no route step parsed in " + relative));
  }

  private static io.gravitee.singularitee.protocol.RoutingStrategy routeStrategyOf(String relative)
    throws IOException {
    return routeStepOf(relative).getRouteConfig().getStrategy();
  }

  @Test
  void every_expected_example_folder_is_covered() throws IOException {
    List<String> found = standaloneWorkspaces()
      .map(p -> p.getParent().getFileName().toString())
      .distinct()
      .sorted()
      .toList();

    assertThat(found)
      .as("examples/ layout changed — update the README table alongside it")
      .contains("llama", "vllm", "classifier", "embedding", "reranker", "pipelines", "modular");
  }

  @Test
  void harmony_workspaces_declare_a_re_enterable_reasoning_channel() throws IOException {
    // gpt-oss opens analysis and commentary, and re-enters them within one
    // generation. Drop either half and the header leaks into the answer as raw
    // text — the failure this configuration exists to prevent.
    for (String file : List.of(
      "llama/gpt-oss-20b.yaml",
      "vllm/gpt-oss-20b.yaml",
      "vllm/gpt-oss-20b-mac.yaml",
      "vllm/gpt-oss-20b-80gb.yaml"
    )) {
      var tags = onlyPipeline(file).getStepsList().getFirst().getInferConfig().getReasoningTags();

      assertThat(tags.getRepeatable()).as("%s: reasoning_repeatable", file).isTrue();
      assertThat(tags.getOpenTagAlternativesList())
        .as("%s: commentary opener", file)
        .contains("<|channel|>commentary<|message|>");
    }
  }

  @Test
  void a_workspace_that_is_silent_leaves_re_entry_to_the_engine() throws IOException {
    // Unset must not arrive as an explicit false: the tool channel repeats by
    // default, and forwarding false would forbid a second tool call.
    var tags = onlyPipeline("llama/qwen3-0.6b.yaml")
      .getStepsList()
      .getFirst()
      .getInferConfig()
      .getReasoningTags();

    assertThat(tags.hasRepeatable()).isFalse();
  }
}
