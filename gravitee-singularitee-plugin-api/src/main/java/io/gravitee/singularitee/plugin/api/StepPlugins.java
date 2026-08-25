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

import io.gravitee.plugin.core.api.Plugin;
import io.gravitee.plugin.core.api.PluginClassLoaderFactory;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the gravitee plugins a {@code PluginRegistry} discovered into what the engine
 * consumes: executors and codecs keyed by step type, ordered decorators, and the gated step
 * types the license does not cover (type to feature).
 *
 * <p>Each plugin's class is loaded through the registry's parent-first class loader (the
 * plugin jar is consulted only for classes the platform does not have) and instantiated
 * with its no-arg constructor. Two plugins claiming one step type is a misassembly and
 * fails. A plugin whose manifest names a feature the license does not enable keeps its
 * codec (a workspace still parses) but no executor: registration is where the license
 * verdict lands, naming the feature.
 *
 * @param executors  licensed executors keyed by step type
 * @param codecs     codecs for every discovered step type, licensed or not
 * @param decorators licensed plugin decorators, outer to inner
 * @param unlicensed gated step types the license does not cover, type to feature
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepPlugins(
  Map<String, StepExecutor<?>> executors,
  Map<String, StepConfigCodec<?>> codecs,
  List<StepExecutorDecorator> decorators,
  Map<String, String> unlicensed
) {
  /** The gravitee plugin type of a step plugin ({@code type=step} in plugin.properties). */
  public static final String STEP_TYPE = "step";
  /** The gravitee plugin type of a decorator plugin. */
  public static final String DECORATOR_TYPE = "step-decorator";

  private static final Logger LOGGER = LoggerFactory.getLogger(StepPlugins.class);

  /**
   * @param plugins        the plugins of type {@link #STEP_TYPE} and {@link #DECORATOR_TYPE}
   * @param loaders        the registry's class loader factory (parent-first by construction)
   * @param parent         the platform class loader plugins delegate to
   * @param featureEnabled whether the platform license enables a feature
   * @param enabled        whether a plugin is enabled ({@code <type>.<id>.enabled} toggles)
   * @param services       the parent-owned collaborators plugins build from
   */
  public static StepPlugins assemble(
    Collection<Plugin> plugins,
    PluginClassLoaderFactory<Plugin> loaders,
    ClassLoader parent,
    Predicate<String> featureEnabled,
    Predicate<Plugin> enabled,
    StepExecutorServices services
  ) {
    Map<String, StepExecutor<?>> executors = new LinkedHashMap<>();
    Map<String, StepConfigCodec<?>> codecs = new LinkedHashMap<>();
    Map<String, String> unlicensed = new LinkedHashMap<>();
    Map<String, String> providers = new LinkedHashMap<>();
    List<Plugin> decoratorPlugins = new ArrayList<>();

    for (Plugin plugin : plugins) {
      if (!enabled.test(plugin)) {
        LOGGER.info("Plugin '{}' ({}) is disabled by configuration", plugin.id(), plugin.type());
        continue;
      }
      if (DECORATOR_TYPE.equals(plugin.type())) {
        decoratorPlugins.add(plugin);
        continue;
      }
      if (!STEP_TYPE.equals(plugin.type())) {
        continue;
      }
      StepExecutorPlugin step = instantiate(plugin, loaders, parent, StepExecutorPlugin.class);
      String type = step.type();
      String previous = providers.putIfAbsent(type, plugin.id());
      if (previous != null) {
        throw new IllegalStateException(
          "Step type '" +
            type +
            "' is provided by both plugin '" +
            previous +
            "' and plugin '" +
            plugin.id() +
            "'"
        );
      }
      codecs.put(type, step.codec());
      String feature = plugin.manifest().feature();
      if (feature != null && !feature.isBlank() && !featureEnabled.test(feature)) {
        unlicensed.put(type, feature);
        LOGGER.warn(
          "Plugin '{}' (step '{}') detected but not activated: license feature '{}' is not enabled",
          plugin.id(),
          type,
          feature
        );
        continue;
      }
      executors.put(type, step.create(services));
      LOGGER.info("Plugin '{}' installed: step '{}' ({})", plugin.id(), type, plugin.clazz());
    }

    decoratorPlugins.sort(Comparator.comparingInt(p -> p.manifest().priority()));
    List<StepExecutorDecorator> decorators = new ArrayList<>();
    for (Plugin plugin : decoratorPlugins) {
      String feature = plugin.manifest().feature();
      if (feature != null && !feature.isBlank() && !featureEnabled.test(feature)) {
        LOGGER.warn(
          "Plugin '{}' (decorator) detected but not activated: license feature '{}' is not enabled",
          plugin.id(),
          feature
        );
        continue;
      }
      decorators.add(
        instantiate(plugin, loaders, parent, StepDecoratorPlugin.class).create(services)
      );
      LOGGER.info("Plugin '{}' installed: step decorator ({})", plugin.id(), plugin.clazz());
    }

    return new StepPlugins(
      Map.copyOf(executors),
      Map.copyOf(codecs),
      List.copyOf(decorators),
      Map.copyOf(unlicensed)
    );
  }

  /**
   * The codecs of every step plugin, without building executors: what a workspace load
   * needs before the executors' collaborators exist. Duplicate step types fail here too.
   * Disabled plugins are not filtered: their codec still parses a workspace, so a pipeline
   * naming a disabled step is reported precisely instead of as an unknown type.
   */
  public static Map<String, StepConfigCodec<?>> codecsOf(
    Collection<Plugin> plugins,
    PluginClassLoaderFactory<Plugin> loaders,
    ClassLoader parent
  ) {
    Map<String, StepConfigCodec<?>> codecs = new LinkedHashMap<>();
    for (Plugin plugin : plugins) {
      if (!STEP_TYPE.equals(plugin.type())) continue;
      StepExecutorPlugin step = instantiate(plugin, loaders, parent, StepExecutorPlugin.class);
      if (codecs.putIfAbsent(step.type(), step.codec()) != null) {
        throw new IllegalStateException("Step type '" + step.type() + "' is provided twice");
      }
    }
    return Map.copyOf(codecs);
  }

  private static <T> T instantiate(
    Plugin plugin,
    PluginClassLoaderFactory<Plugin> loaders,
    ClassLoader parent,
    Class<T> contract
  ) {
    try {
      Class<?> clazz = loaders.getOrCreateClassLoader(plugin, parent).loadClass(plugin.clazz());
      Object instance = clazz.getDeclaredConstructor().newInstance();
      if (!contract.isInstance(instance)) {
        throw new IllegalStateException(
          "Plugin '" +
            plugin.id() +
            "': class " +
            plugin.clazz() +
            " does not implement " +
            contract.getSimpleName()
        );
      }
      return contract.cast(instance);
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException(
        "Plugin '" +
          plugin.id() +
          "': cannot instantiate " +
          plugin.clazz() +
          " (plugin-api version mismatch?)",
        e
      );
    }
  }
}
