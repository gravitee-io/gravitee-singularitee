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
package io.gravitee.singularitee.inference.api.decision;

import java.util.Locale;

/**
 * How a decision model reads its scores. Native families are declared by the model
 * ({@code <arch>.decision.type}); {@link #CUSTOM} is any causal model scored on
 * caller-chosen label tokens; {@link #DECISION1} and {@link #DECISION2} are served by a runtime
 * that renders and scores its prompts itself.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum DecisionFamily {
  /** Logit of one label token per option ({@code A..Z a..z}). */
  OPENJEV,
  /** As openjev, choices scored in two orders, noul read from a 9-point rating. */
  LEV,
  /** Hidden state of the last token against one end marker per option. */
  KEV,
  /** As openjev, every prompt lists all the questions of the request. */
  NIMBLE,
  /** One column of the hidden state at one mask marker per option. */
  LAYA,
  /** A joint head that decides all the questions of a request in one prompt. */
  CLEF,
  /** Any causal model scored on caller-chosen label tokens. */
  CUSTOM,
  /**
   * vLLM Semantic Router Decision 1.0 (typed heads over an encoder or a decoder), rendered and
   * scored by its own model runtime.
   */
  DECISION1,
  /** vLLM Semantic Router Decision 2.0, rendered and scored by its own model runtime. */
  DECISION2;

  /** The lower-case name used on the wire and in the workspace. */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether the questions of a request are decided together, in one prompt. */
  public boolean joint() {
    return this == CLEF;
  }

  /** Whether the family scores label tokens at the end of a text prompt. */
  public boolean labelled() {
    return this == OPENJEV || this == LEV || this == NIMBLE || this == CUSTOM;
  }
}
