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

/**
 * What a decision model accepts, known once it is loaded.
 *
 * @param family     how it reads its scores
 * @param maxOptions most options one question may have
 * @param templated  whether the server renders prompts for it ({@link DecisionInput.Templated})
 * @param raw        whether it scores caller-rendered prompts ({@link DecisionInput.Raw})
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record DecisionCapabilities(
  DecisionFamily family,
  int maxOptions,
  boolean templated,
  boolean raw
) {
  /** Whether the questions of a request are decided together. */
  public boolean joint() {
    return family.joint();
  }
}
