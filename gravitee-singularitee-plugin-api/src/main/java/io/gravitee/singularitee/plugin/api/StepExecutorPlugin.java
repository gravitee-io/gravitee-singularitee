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

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;

/**
 * A gravitee plugin of type {@code step}: the {@code class=} of its {@code plugin.properties}
 * names an implementation of this interface, which contributes one step type, its config
 * codec (the YAML block a workspace writes under {@code config:}) and its executor, built
 * once per server against the parent-owned {@link StepExecutorServices}.
 *
 * <p>Licensing is the manifest's: a {@code feature=} entry in {@code plugin.properties}
 * makes the step available only when the platform license enables that feature. Core
 * steps declare none.
 *
 * <p>A plugin may ship a JSON schema of its config block as {@value #CONFIG_SCHEMA_FILE}
 * at the root of its jar; {@link #configSchema()} reads it from the root the plugin class
 * itself was loaded from.
 *
 * <p>Implementations need a public no-arg constructor. A plugin is a decorator over the
 * engine: it may only use parent-loaded APIs, never loads a model or a native library,
 * and cannot claim a step type another plugin already provides.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface StepExecutorPlugin {
  /** File at the root of the plugin jar holding the JSON schema of the step's config block. */
  String CONFIG_SCHEMA_FILE = "step-config.schema.json";

  /** The step type string a workspace writes as {@code type:}. */
  String type();

  /** Parses and validates this step's YAML config block. */
  StepConfigCodec<?> codec();

  /** Builds the executor from the parent-owned services. */
  StepExecutor<?> create(StepExecutorServices services);

  /**
   * The JSON schema of this step's config block, or {@code null} when the plugin ships none.
   *
   * <p>The file is read from the jar (or directory) this plugin class was loaded from, never
   * by name through a class loader: loaders delegate parent-first, so a lookup by name would
   * give every plugin the first {@value #CONFIG_SCHEMA_FILE} on the shared classpath.
   *
   * @throws UncheckedIOException when the file exists but cannot be read; during assembly
   *                              this fails the boot, like any other broken plugin
   */
  default String configSchema() {
    CodeSource source = getClass().getProtectionDomain().getCodeSource();
    if (source == null) {
      return null;
    }
    String location = source.getLocation().toExternalForm();
    String schemaUrl = location.endsWith("/")
      ? location + CONFIG_SCHEMA_FILE
      : "jar:" + location + "!/" + CONFIG_SCHEMA_FILE;
    try {
      URLConnection connection = URI.create(schemaUrl).toURL().openConnection();
      connection.setUseCaches(false);
      try (InputStream in = connection.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (FileNotFoundException e) {
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
