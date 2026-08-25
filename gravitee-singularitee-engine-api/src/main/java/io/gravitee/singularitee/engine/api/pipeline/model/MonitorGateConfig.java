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
package io.gravitee.singularitee.engine.api.pipeline.model;

/**
 * A step config that gates actions behind a downstream monitor (a guard or llm_guard step
 * reachable after it). The loader refuses a workspace where such a config has no monitor
 * on its branch: a monitor tier that silently means "unmonitored" is not acceptable.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface MonitorGateConfig {
  /** Whether any action of this step is gated on a downstream monitor. */
  boolean requiresMonitor();
}
