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
package io.gravitee.singularitee.engine.api.registry;

import java.util.Optional;

/**
 * Answers, for a step type, which license feature is missing for it to execute here.
 * Empty means available (every core type, and every licensed gated type). Wired from the
 * plugin assembly; the default answers "available" for everything, which is what the
 * client-side executor and tests want.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface StepAvailability {
  StepAvailability ALL_AVAILABLE = type -> Optional.empty();

  Optional<String> missingLicenseFeature(String type);
}
