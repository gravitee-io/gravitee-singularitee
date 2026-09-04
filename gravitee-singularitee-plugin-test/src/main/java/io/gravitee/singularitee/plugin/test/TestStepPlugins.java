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
package io.gravitee.singularitee.plugin.test;

import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.plugin.api.StepExecutorServices;
import io.gravitee.singularitee.plugin.api.StepPluginRegistry;
import io.gravitee.singularitee.plugin.api.StepPlugins;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Step plugins for test suites: a gravitee plugin registry bootstrapped once per JVM over
 * the plugin zips a module staged under {@code target/test-plugins} (with
 * maven-dependency-plugin), assembled with every feature considered licensed. Tests load
 * workspaces through these codecs exactly like the server does through its node registry.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TestStepPlugins {

  /** Where a module's build stages the plugin zips, relative to the module directory. */
  public static final Path DEFAULT_PLUGINS_DIR = Path.of("target", "test-plugins");

  private static volatile StepPlugins cached;

  private TestStepPlugins() {}

  /** The assembled plugins from {@link #DEFAULT_PLUGINS_DIR}, bootstrapped once. */
  public static StepPlugins plugins() {
    StepPlugins local = cached;
    if (local == null) {
      synchronized (TestStepPlugins.class) {
        local = cached;
        if (local == null) {
          local = assemble(
            DEFAULT_PLUGINS_DIR,
            new StepExecutorServices(null, null, null, null, null, null, null, null, null)
          );
          cached = local;
        }
      }
    }
    return local;
  }

  /** The step codecs, keyed by type: what a workspace load needs. */
  public static Map<String, StepConfigCodec<?>> codecs() {
    return plugins().codecs();
  }

  /** Assembles the plugins under {@code pluginsDir} with the given services, every feature licensed. */
  public static StepPlugins assemble(Path pluginsDir, StepExecutorServices services) {
    if (!Files.isDirectory(pluginsDir)) {
      throw new IllegalStateException(
        "No staged plugin zips at " +
          pluginsDir.toAbsolutePath() +
          "; the module's pom must copy them (maven-dependency-plugin)"
      );
    }
    var registry = StepPluginRegistry.bootstrap(
      pluginsDir,
      pluginsDir.resolveSibling("test-plugins-work")
    );
    return StepPlugins.assemble(
      registry.stepPlugins(),
      registry.loaders(),
      TestStepPlugins.class.getClassLoader(),
      feature -> true,
      plugin -> true,
      services
    );
  }
}
