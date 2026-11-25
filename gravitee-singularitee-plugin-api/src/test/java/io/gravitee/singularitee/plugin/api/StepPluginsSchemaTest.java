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

import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step plugin publishes the JSON schema of its config block as {@code step-config.schema.json}
 * in its own jar. The fixture classes are hidden from the parent class loader, so each one is
 * defined by its plugin's class loader and must read the file of its own jar.
 */
class StepPluginsSchemaTest {

  private static final StepExecutorServices SERVICES = new StepExecutorServices(
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  );

  static final class NoopExecutor implements StepExecutor<Object> {

    @Override
    public Maybe<String> execute(String stepId, Object config, StepContext ctx) {
      return Maybe.empty();
    }
  }

  public abstract static class FixtureBase implements StepExecutorPlugin {

    @Override
    public StepConfigCodec<?> codec() {
      return (id, cfg, ctx) -> cfg;
    }

    @Override
    public StepExecutor<?> create(StepExecutorServices services) {
      return new NoopExecutor();
    }
  }

  public static final class AlphaPlugin extends FixtureBase {

    @Override
    public String type() {
      return "alpha";
    }
  }

  public static final class BetaPlugin extends FixtureBase {

    @Override
    public String type() {
      return "beta";
    }
  }

  public static final class SchemalessPlugin extends FixtureBase {

    @Override
    public String type() {
      return "schemaless";
    }
  }

  private static final Set<String> HIDDEN = Set.of(
    AlphaPlugin.class.getName(),
    BetaPlugin.class.getName(),
    SchemalessPlugin.class.getName()
  );

  private static final ClassLoader PARENT_WITHOUT_FIXTURES = new ClassLoader(
    StepPluginsSchemaTest.class.getClassLoader()
  ) {
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (HIDDEN.contains(name)) {
        throw new ClassNotFoundException(name);
      }
      return super.loadClass(name, resolve);
    }
  };

  private static void writePluginZip(Path dir, String id, Class<?> clazz, String schema)
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
          "\ntype=step\n").getBytes(StandardCharsets.UTF_8)
      );
      out.closeEntry();
      for (Class<?> bundled : new Class<?>[] { clazz, FixtureBase.class }) {
        String entry = bundled.getName().replace('.', '/') + ".class";
        out.putNextEntry(new JarEntry(entry));
        try (InputStream in = bundled.getClassLoader().getResourceAsStream(entry)) {
          out.write(in.readAllBytes());
        }
        out.closeEntry();
      }
      if (schema != null) {
        out.putNextEntry(new JarEntry("step-config.schema.json"));
        out.write(schema.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    Path zip = dir.resolve(id + ".zip");
    try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry(id + "-1.0.0.jar"));
      out.write(Files.readAllBytes(jar));
      out.closeEntry();
    }
    Files.delete(jar);
  }

  private static StepPlugins assemble(Path tmp, Fixture... plugins) throws IOException {
    Path dir = Files.createDirectory(tmp.resolve("plugins"));
    for (Fixture p : plugins) {
      writePluginZip(dir, p.id(), p.clazz(), p.schema());
    }
    var registry = StepPluginRegistry.bootstrap(dir, tmp.resolve("work"));
    return StepPlugins.assemble(
      registry.stepPlugins(),
      registry.loaders(),
      PARENT_WITHOUT_FIXTURES,
      feature -> true,
      plugin -> true,
      SERVICES
    );
  }

  private record Fixture(String id, Class<?> clazz, String schema) {}

  @Test
  void each_plugin_publishes_the_schema_of_its_own_jar(@TempDir Path tmp) throws IOException {
    var assembled = assemble(
      tmp,
      new Fixture("alpha", AlphaPlugin.class, "{\"title\":\"alpha\"}"),
      new Fixture("beta", BetaPlugin.class, "{\"title\":\"beta\"}")
    );

    assertThat(assembled.configSchemas()).isEqualTo(
      Map.of("alpha", "{\"title\":\"alpha\"}", "beta", "{\"title\":\"beta\"}")
    );
  }

  @Test
  void step_types_without_a_schema_are_left_out(@TempDir Path tmp) throws IOException {
    var assembled = assemble(
      tmp,
      new Fixture("alpha", AlphaPlugin.class, "{\"title\":\"alpha\"}"),
      new Fixture("schemaless", SchemalessPlugin.class, null)
    );

    assertThat(assembled.codecs()).containsOnlyKeys("alpha", "schemaless");
    assertThat(assembled.configSchemas()).containsOnlyKeys("alpha");
  }

  @Test
  void plugins_on_one_parent_first_classpath_keep_their_own_schema_despite_a_decoy(
    @TempDir Path tmp
  ) throws Exception {
    Path decoy = Files.writeString(
      Files.createDirectory(tmp.resolve("decoy")).resolve("step-config.schema.json"),
      "{\"title\":\"decoy\"}"
    );
    Path alphaRoot = pluginRoot(tmp.resolve("alpha"), AlphaPlugin.class, "{\"title\":\"alpha\"}");
    Path betaRoot = pluginRoot(tmp.resolve("beta"), BetaPlugin.class, "{\"title\":\"beta\"}");
    Path bareRoot = pluginRoot(tmp.resolve("bare"), SchemalessPlugin.class, null);
    var decoyLoader = new URLClassLoader(
      new URL[] { decoy.getParent().toUri().toURL() },
      PARENT_WITHOUT_FIXTURES
    );
    var shared = new URLClassLoader(
      new URL[] { alphaRoot.toUri().toURL(), betaRoot.toUri().toURL(), bareRoot.toUri().toURL() },
      decoyLoader
    );

    assertThat(shared.getResource("step-config.schema.json")).isEqualTo(decoy.toUri().toURL());
    assertThat(plugin(shared, AlphaPlugin.class).configSchema()).isEqualTo("{\"title\":\"alpha\"}");
    assertThat(plugin(shared, BetaPlugin.class).configSchema()).isEqualTo("{\"title\":\"beta\"}");
    assertThat(plugin(shared, SchemalessPlugin.class).configSchema()).isNull();
  }

  private static Path pluginRoot(Path root, Class<?> clazz, String schema) throws IOException {
    String entry = clazz.getName().replace('.', '/') + ".class";
    Path classFile = root.resolve(entry);
    Files.createDirectories(classFile.getParent());
    try (InputStream in = clazz.getClassLoader().getResourceAsStream(entry)) {
      Files.write(classFile, in.readAllBytes());
    }
    if (schema != null) {
      Files.writeString(root.resolve("step-config.schema.json"), schema);
    }
    return root;
  }

  private static StepExecutorPlugin plugin(ClassLoader loader, Class<?> clazz) throws Exception {
    Class<?> loaded = loader.loadClass(clazz.getName());
    assertThat(loaded).isNotSameAs(clazz);
    return (StepExecutorPlugin) loaded.getDeclaredConstructor().newInstance();
  }

  @Test
  void plugin_without_the_resource_reports_no_schema() {
    assertThat(new SchemalessPlugin().configSchema()).isNull();
  }
}
