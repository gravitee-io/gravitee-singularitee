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
package io.gravitee.singularitee.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepInvocation;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step plugins are gravitee plugins: a zip holding a jar whose root carries
 * {@code plugin.properties} is discovered by a bootstrapped registry, its class resolves
 * PARENT-FIRST (the fixture class lives on the test classpath, the jar only names it),
 * the manifest feature gates activation, and two plugins for one step type fail.
 */
class StepPluginsTest {

  private static final StepExecutorServices SERVICES = new StepExecutorServices(
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  );

  /** Named by the fixture manifest; the class itself is parent-loaded. */
  public static final class FixturePlugin implements StepExecutorPlugin {

    @Override
    public String type() {
      return "fixture";
    }

    @Override
    public StepConfigCodec<?> codec() {
      return (id, cfg, ctx) -> cfg;
    }

    @Override
    public StepExecutor<?> create(StepExecutorServices services) {
      return (StepExecutor<Object>) (id, config, ctx) -> Maybe.empty();
    }
  }

  /** A second provider of the same step type. */
  public static final class DuplicatePlugin extends Object implements StepExecutorPlugin {

    @Override
    public String type() {
      return "fixture";
    }

    @Override
    public StepConfigCodec<?> codec() {
      return (id, cfg, ctx) -> cfg;
    }

    @Override
    public StepExecutor<?> create(StepExecutorServices services) {
      return (StepExecutor<Object>) (id, config, ctx) -> Maybe.empty();
    }
  }

  /** A decorator whose class name is its identity in the assembled chain. */
  public static class TaggedDecorator implements StepDecoratorPlugin, StepExecutorDecorator {

    @Override
    public StepExecutorDecorator create(StepExecutorServices services) {
      return this;
    }

    @Override
    public Maybe<String> around(StepModel step, StepContext ctx, StepInvocation next) {
      return next.proceed(step, ctx);
    }
  }

  public static final class OuterDecorator extends TaggedDecorator {}

  public static final class InnerDecorator extends TaggedDecorator {}

  public static final class GatedDecorator extends TaggedDecorator {}

  private static void writePluginZip(Path dir, String id, Class<?> clazz, String feature)
    throws IOException {
    writePluginZip(dir, id, clazz, feature, StepPlugins.STEP_TYPE, null);
  }

  private static void writeDecoratorZip(
    Path dir,
    String id,
    Class<?> clazz,
    int priority,
    String feature
  ) throws IOException {
    writePluginZip(dir, id, clazz, feature, StepPlugins.DECORATOR_TYPE, priority);
  }

  private static void writePluginZip(
    Path dir,
    String id,
    Class<?> clazz,
    String feature,
    String type,
    Integer priority
  ) throws IOException {
    Path jar = dir.resolve(id + ".jar.tmp");
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("plugin.properties"));
      String props =
        "id=" +
        id +
        "\n" +
        "name=" +
        id +
        "\n" +
        "version=1.0.0\n" +
        "description=fixture\n" +
        "class=" +
        clazz.getName() +
        "\n" +
        "type=" +
        type +
        "\n" +
        (priority == null ? "" : "priority=" + priority + "\n") +
        (feature == null ? "" : "feature=" + feature + "\n");
      out.write(props.getBytes());
      out.closeEntry();
    }
    Path zip = dir.resolve(id + ".zip");
    try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry(id + "-1.0.0.jar"));
      out.write(Files.readAllBytes(jar));
      out.closeEntry();
    }
    Files.delete(jar);
  }

  private static StepPlugins assemble(StepPluginRegistry registry, boolean licensed) {
    return StepPlugins.assemble(
      registry.stepPlugins(),
      registry.loaders(),
      StepPluginsTest.class.getClassLoader(),
      feature -> licensed,
      plugin -> true,
      SERVICES
    );
  }

  @Test
  void zip_plugin_is_discovered_and_its_class_is_parent_loaded(@TempDir Path tmp) throws Exception {
    Path plugins = Files.createDirectory(tmp.resolve("plugins"));
    writePluginZip(plugins, "fixture", FixturePlugin.class, null);

    var registry = StepPluginRegistry.bootstrap(plugins, tmp.resolve("work"));
    var assembled = assemble(registry, false);

    assertThat(registry.registry().plugins("step")).hasSize(1);
    assertThat(assembled.executors()).containsOnlyKeys("fixture");
    assertThat(assembled.codecs()).containsOnlyKeys("fixture");
    assertThat(assembled.unlicensed()).isEmpty();
    var loaded = registry
      .loaders()
      .getOrCreateClassLoader(
        registry.stepPlugins().getFirst(),
        StepPluginsTest.class.getClassLoader()
      );
    assertThat(loaded.loadClass(FixturePlugin.class.getName())).isSameAs(FixturePlugin.class);
  }

  @Test
  void unlicensed_feature_keeps_the_codec_but_not_the_executor(@TempDir Path tmp)
    throws IOException {
    Path plugins = Files.createDirectory(tmp.resolve("plugins"));
    writePluginZip(plugins, "gated", FixturePlugin.class, "singularitee-fixture");

    var registry = StepPluginRegistry.bootstrap(plugins, tmp.resolve("work"));

    var unlicensed = assemble(registry, false);
    assertThat(unlicensed.executors()).isEmpty();
    assertThat(unlicensed.codecs()).containsOnlyKeys("fixture");
    assertThat(unlicensed.unlicensed()).containsEntry("fixture", "singularitee-fixture");

    var licensed = assemble(registry, true);
    assertThat(licensed.executors()).containsOnlyKeys("fixture");
    assertThat(licensed.unlicensed()).isEmpty();
  }

  @Test
  void two_plugins_for_one_step_type_is_misassembly(@TempDir Path tmp) throws IOException {
    Path plugins = Files.createDirectory(tmp.resolve("plugins"));
    writePluginZip(plugins, "first", FixturePlugin.class, null);
    writePluginZip(plugins, "second", DuplicatePlugin.class, null);

    var registry = StepPluginRegistry.bootstrap(plugins, tmp.resolve("work"));

    assertThatThrownBy(() -> assemble(registry, true))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("provided by both");
  }

  @Test
  void disabled_plugin_is_skipped(@TempDir Path tmp) throws IOException {
    Path plugins = Files.createDirectory(tmp.resolve("plugins"));
    writePluginZip(plugins, "fixture", FixturePlugin.class, null);
    var registry = StepPluginRegistry.bootstrap(plugins, tmp.resolve("work"));

    var assembled = StepPlugins.assemble(
      registry.stepPlugins(),
      registry.loaders(),
      StepPluginsTest.class.getClassLoader(),
      feature -> true,
      plugin -> !"fixture".equals(plugin.id()),
      SERVICES
    );

    assertThat(assembled.executors()).isEmpty();
    assertThat(assembled.codecs()).isEmpty();
  }

  @Test
  void decorators_are_ordered_by_priority_and_license_gated(@TempDir Path tmp) throws IOException {
    Path plugins = Files.createDirectory(tmp.resolve("plugins"));
    writeDecoratorZip(plugins, "inner", InnerDecorator.class, 20, null);
    writeDecoratorZip(plugins, "outer", OuterDecorator.class, 10, null);
    writeDecoratorZip(plugins, "gated", GatedDecorator.class, 5, "singularitee-fixture");
    var registry = StepPluginRegistry.bootstrap(plugins, tmp.resolve("work"));

    var unlicensed = assemble(registry, false);
    assertThat(unlicensed.decorators())
      .extracting(d -> d.getClass().getSimpleName())
      .containsExactly("OuterDecorator", "InnerDecorator");

    var licensed = assemble(registry, true);
    assertThat(licensed.decorators())
      .extracting(d -> d.getClass().getSimpleName())
      .containsExactly("GatedDecorator", "OuterDecorator", "InnerDecorator");
  }

  @Test
  void empty_plugins_dir_yields_nothing(@TempDir Path tmp) throws IOException {
    var registry = StepPluginRegistry.bootstrap(
      Files.createDirectory(tmp.resolve("plugins")),
      tmp.resolve("work")
    );
    assertThat(registry.stepPlugins()).isEmpty();
    assertThat(assemble(registry, true).codecs()).isEmpty();
  }
}
