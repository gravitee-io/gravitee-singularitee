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
package io.gravitee.singularitee.engine;

/**
 * A single classification result for one label.
 *
 * <p>For sequence classification models only {@code label} and {@code score} are
 * meaningful. For token-classification / NER models {@code token}, {@code start},
 * and {@code end} carry the matched span.
 *
 * @param label  the class label
 * @param score  the model confidence for this label (0.0–1.0)
 * @param token  the matched token text for NER results; {@code null} for sequence models
 * @param start  character start offset of the matched span; {@code null} for sequence models
 * @param end    character end offset of the matched span; {@code null} for sequence models
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ClassifyResult(String label, float score, String token, Integer start, Integer end) {
  /**
   * Convenience constructor for sequence-classification results.
   *
   * @param label the class label
   * @param score the confidence score
   */
  public ClassifyResult(String label, float score) {
    this(label, score, null, null, null);
  }
}
