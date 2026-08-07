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

import io.gravitee.node.container.GraviteeProductInitializer;
import io.gravitee.singularitee.standalone.node.SingulariteeNode;

/**
 * Identifies this product to the gravitee-node infrastructure.
 * Registered via {@code META-INF/spring.factories}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SingulariteeProductInitializer implements GraviteeProductInitializer {

  @Override
  public String productName() {
    return SingulariteeNode.APPLICATION_NAME;
  }
}
