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
package io.gravitee.singularitee.standalone;

import io.gravitee.kubernetes.client.spring.KubernetesClientConfiguration;
import io.gravitee.node.container.spring.SpringBasedContainer;
import io.gravitee.node.kubernetes.spring.NodeKubernetesConfiguration;
import io.gravitee.singularitee.standalone.spring.SingulariteeConfiguration;
import io.gravitee.singularitee.standalone.spring.UtilsConfiguration;
import java.util.List;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SingulariteeContainer extends SpringBasedContainer {

  @Override
  protected List<Class<?>> annotatedClasses() {
    List<Class<?>> classes = super.annotatedClasses();
    classes.add(SingulariteeConfiguration.class);
    return classes;
  }

  @Override
  protected List<Class<?>> bootstrapClasses() {
    List<Class<?>> classes = super.bootstrapClasses();
    classes.add(UtilsConfiguration.class);
    classes.removeIf(
      c ->
        c.equals(KubernetesClientConfiguration.class) || c.equals(NodeKubernetesConfiguration.class)
    );
    return classes;
  }

  @Override
  public String name() {
    return "Gravitee.io - Singularitee";
  }

  /**
   * Convenience entry point for IDE debugging. Production uses {@code Bootstrap.main()}.
   * Run with {@code -Dgravitee.home=/path/to/distribution}.
   */
  static void main(String[] args) throws Exception {
    SingulariteeContainer container = new SingulariteeContainer();
    container.start();
  }
}
