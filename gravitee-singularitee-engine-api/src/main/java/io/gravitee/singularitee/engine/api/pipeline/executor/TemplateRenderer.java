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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import java.util.Map;

/**
 * Renders a template string against a context map. The plugin-facing contract
 * for pipeline template rendering: a step executor receives one through
 * {@code StepExecutorServices} and never sees the templating engine behind it.
 *
 * <p>Implementations compile and cache templates internally and hold no
 * per-request state, so a single instance is safe to share across threads.
 */
public interface TemplateRenderer {
  /**
   * Renders {@code templateString} against {@code ctx}.
   *
   * @param templateString the template source
   * @param tag            a debug label surfaced in engine error messages
   *                       (e.g. {@code "<step>"}, {@code "<guard>"})
   * @param ctx            the rendering context, consumed read-only
   * @return the rendered string
   */
  String render(String templateString, String tag, Map<String, Object> ctx);
}
