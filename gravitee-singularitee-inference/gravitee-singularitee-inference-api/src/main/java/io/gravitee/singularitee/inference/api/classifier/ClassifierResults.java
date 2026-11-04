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
package io.gravitee.singularitee.inference.api.classifier;

import java.util.Collection;

/**
 * All labels a classifier produced for one input.
 *
 * @param results      the labels, with their spans for token classification
 * @param inputTokens  tokens the model read for the input, special tokens included; {@code 0}
 *                     when not counted
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ClassifierResults(Collection<ClassifierResult> results, int inputTokens) {
  /** Results whose tokens were not counted. */
  public ClassifierResults(Collection<ClassifierResult> results) {
    this(results, 0);
  }
}
