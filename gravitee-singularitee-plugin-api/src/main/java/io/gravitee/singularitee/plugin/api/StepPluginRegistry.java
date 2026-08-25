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

import io.gravitee.common.event.impl.EventManagerImpl;
import io.gravitee.plugin.core.api.Plugin;
import io.gravitee.plugin.core.api.PluginClassLoaderFactory;
import io.gravitee.plugin.core.api.PluginRegistry;
import io.gravitee.plugin.core.internal.CachedPluginClassLoaderFactory;
import io.gravitee.plugin.core.internal.PluginRegistryConfiguration;
import io.gravitee.plugin.core.internal.PluginRegistryImpl;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.core.env.StandardEnvironment;

/**
 * Bootstraps a standalone gravitee {@code PluginRegistry} over a plugins directory, for
 * hosts that are not a gravitee node: the client-side executor and the test suites. The
 * server uses the registry its node container bootstraps.
 *
 * @param registry the bootstrapped registry ({@code plugins("step")} is populated)
 * @param loaders  the parent-first class loader factory to load plugin classes with
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepPluginRegistry(
  PluginRegistry registry,
  PluginClassLoaderFactory<Plugin> loaders
) {
  /**
   * @param pluginsDir directory holding the plugin zips
   * @param workDir    directory the zips are extracted into
   */
  public static StepPluginRegistry bootstrap(Path pluginsDir, Path workDir) {
    var configuration = new PluginRegistryConfiguration();
    configuration.setPluginsPath(new String[] { pluginsDir.toAbsolutePath().toString() });
    configuration.setPluginWorkDir(workDir.toAbsolutePath().toString());
    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "singularitee-plugin-registry");
      t.setDaemon(true);
      return t;
    });
    try {
      var registry = new PluginRegistryImpl(
        configuration,
        new StandardEnvironment(),
        executor,
        new EventManagerImpl(),
        List.of()
      );
      registry.bootstrap();
      return new StepPluginRegistry(registry, new CachedPluginClassLoaderFactory<>());
    } catch (Exception e) {
      throw new IllegalStateException(
        "Cannot bootstrap the step plugin registry over " + pluginsDir,
        e
      );
    } finally {
      executor.shutdown();
    }
  }

  /** Every step and step-decorator plugin the registry found. */
  public List<Plugin> stepPlugins() {
    var all = new ArrayList<>(registry.plugins(StepPlugins.STEP_TYPE));
    all.addAll(registry.plugins(StepPlugins.DECORATOR_TYPE));
    return List.copyOf(all);
  }
}
