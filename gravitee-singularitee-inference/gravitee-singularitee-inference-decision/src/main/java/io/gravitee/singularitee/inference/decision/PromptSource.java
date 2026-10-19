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
package io.gravitee.singularitee.inference.decision;

/**
 * Where a decision model's prompts come from, fixed when the model loads. It decides whether the
 * server can render prompts for the model (templated input) or only score prompts the caller
 * rendered (raw input).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface PromptSource {
  /** The model's own {@code systemone} template. */
  record ModelTemplate() implements PromptSource {}

  /** A template the workspace supplies, rendered with the same variables. */
  record WorkspaceTemplate(String source) implements PromptSource {}

  /**
   * The model's chat template around a shipped decision instruction, followed by {@code prefill}
   * so that a label code is the next token. Custom models only.
   */
  record ChatWrapped(String prefill) implements PromptSource {
    /** The default prefill: codes {@code A}, {@code B}... follow an open parenthesis. */
    public static final String DEFAULT_PREFILL = "Answer: (";
  }

  /** No template: the model only scores caller-rendered prompts. */
  record None() implements PromptSource {}

  /** Whether the server can render prompts from this source. */
  default boolean templated() {
    return !(this instanceof None);
  }
}
