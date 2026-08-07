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
package io.gravitee.singularitee.inference.api.textgen;

/**
 * The generation channel a token belongs to, as classified by the engine at
 * token-production time (e.g. llama.cpp's per-sequence generation state,
 * driven by the reasoning / tool-call tags configured on the request).
 *
 * <p>A {@code null} channel on an emitted token means "unclassified" and is
 * treated as {@link #ANSWER} by downstream consumers — engines that do not
 * classify tokens (e.g. vLLM) simply pass {@code null}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum TokenChannel {
  /** Regular answer content. */
  ANSWER,
  /** Reasoning / chain-of-thought content (inside the thinking block). */
  REASONING,
  /** Tool-call markup (must keep flowing as text for tool parsing). */
  TOOL,
}
