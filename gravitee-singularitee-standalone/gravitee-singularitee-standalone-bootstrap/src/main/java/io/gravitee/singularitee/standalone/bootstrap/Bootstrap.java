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
package io.gravitee.singularitee.standalone.bootstrap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Production entry point for Singularitee.
 *
 * <p>Sets up a two-level classloader hierarchy ({@code lib/ext/} → {@code lib/})
 * and reflectively loads the {@code SingulariteeContainer} from the Gravitee classloader.
 * This keeps the bootstrap isolated from all Gravitee / Spring / Vert.x dependencies.
 *
 * <p>Usage: {@code java -Dgravitee.home=/path/to/dist -jar gravitee-singularitee-standalone-bootstrap.jar}
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class Bootstrap {

  private static final String CONTAINER_CLASS =
    "io.gravitee.singularitee.standalone.SingulariteeContainer";
  private static final String LIB_DIRECTORY = "lib";
  private static final String LIB_EXT_DIRECTORY = LIB_DIRECTORY + File.separatorChar + "ext";

  private ClassLoader graviteeClassLoader;
  private Object graviteeDaemon = null;

  public void init() throws Exception {
    setGraviteeHome();
    initClassLoaders();
    Thread.currentThread().setContextClassLoader(graviteeClassLoader);
    Class<?> fwClass = graviteeClassLoader.loadClass(CONTAINER_CLASS);
    graviteeDaemon = fwClass.getDeclaredConstructor().newInstance();
  }

  public void start() throws Exception {
    if (graviteeDaemon == null) {
      init();
    }
    Method method = graviteeDaemon.getClass().getMethod("start", (Class[]) null);
    method.invoke(graviteeDaemon, (Object[]) null);
  }

  public void stop() throws Exception {
    if (graviteeDaemon != null) {
      Method method = graviteeDaemon.getClass().getMethod("stop", (Class[]) null);
      method.invoke(graviteeDaemon, (Object[]) null);
    }
  }

  private void setGraviteeHome() {
    String graviteeHome = System.getProperty("gravitee.home");
    if (graviteeHome == null || graviteeHome.isEmpty()) {
      graviteeHome = System.getenv("GRAVITEE_HOME");
    }
    if (graviteeHome == null || graviteeHome.isEmpty()) {
      throw new IllegalStateException(
        "gravitee.home system property or GRAVITEE_HOME env var must be set"
      );
    }
    System.setProperty("gravitee.home", graviteeHome);
  }

  private void initClassLoaders() throws IOException {
    String graviteeHome = System.getProperty("gravitee.home");

    // Extension classloader: lib/ext/ JARs (third-party)
    ClassLoader extClassLoader = createClassLoader(
      Path.of(graviteeHome, LIB_EXT_DIRECTORY),
      ClassLoader.getSystemClassLoader()
    );

    // Gravitee classloader: lib/ JARs (gravitee + node + Spring)
    graviteeClassLoader = createClassLoader(Path.of(graviteeHome, LIB_DIRECTORY), extClassLoader);
  }

  private static ClassLoader createClassLoader(Path directory, ClassLoader parent)
    throws IOException {
    List<URL> urls = new ArrayList<>();
    if (Files.isDirectory(directory)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
        for (Path jar : stream) {
          urls.add(jar.toUri().toURL());
        }
      }
    }
    return new URLClassLoader(urls.toArray(new URL[0]), parent);
  }

  public static void main(String[] args) throws Exception {
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.start();
  }
}
