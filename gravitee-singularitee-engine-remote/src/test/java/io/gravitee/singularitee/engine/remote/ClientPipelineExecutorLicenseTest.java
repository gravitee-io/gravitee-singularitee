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
package io.gravitee.singularitee.engine.remote;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.registry.UnlicensedStepException;
import io.gravitee.singularitee.plugin.api.StepExecutorPlugin;
import io.gravitee.singularitee.plugin.api.StepExecutorServices;
import io.gravitee.singularitee.plugin.api.StepPluginRegistry;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client node holds no license, so a workspace naming a license-gated step must be
 * refused at creation, with the feature named: the alternative is a healthy node that
 * runs every request with the step skipped.
 */
class ClientPipelineExecutorLicenseTest {

  private static final String FEATURE = "com.example.gated";

  /** Named by the fixture manifest; loaded parent-first from the test classpath. */
  public static final class GatedPlugin implements StepExecutorPlugin {

    @Override
    public String type() {
      return "gated_step";
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

  @Test
  void gated_step_is_refused_on_a_client_rather_than_skipped(@TempDir Path tmp) throws IOException {
    Path plugins = Files.createDirectory(tmp.resolve("plugins"));
    writePluginZip(plugins, "gated", GatedPlugin.class, FEATURE);
    var registry = StepPluginRegistry.bootstrap(plugins, tmp.resolve("work"));

    var pipeline = new PipelineModel(
      "guarded",
      "guarded",
      "g",
      List.of(new StepModel("g", "gated_step", StepRole.STEP_ROLE_OUTPUT, null)),
      Map.of(),
      null,
      false,
      null
    );
    var ws = new YamlWorkspaceLoader.WorkspaceRequests(
      "client",
      List.of(),
      List.of(pipeline),
      List.of(),
      List.of(),
      Map.of()
    );

    assertThatThrownBy(() -> ClientPipelineExecutor.create(ws, null, registry))
      .isInstanceOf(UnlicensedStepException.class)
      .hasMessageContaining("guarded")
      .hasMessageContaining("gated_step")
      .hasMessageContaining(FEATURE);
  }

  private static void writePluginZip(Path dir, String id, Class<?> clazz, String feature)
    throws IOException {
    Path jar = dir.resolve(id + ".jar.tmp");
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("plugin.properties"));
      out.write(
        ("id=" +
          id +
          "\nname=" +
          id +
          "\nversion=1.0.0\ndescription=fixture\nclass=" +
          clazz.getName() +
          "\ntype=step\nfeature=" +
          feature +
          "\n").getBytes()
      );
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
}
