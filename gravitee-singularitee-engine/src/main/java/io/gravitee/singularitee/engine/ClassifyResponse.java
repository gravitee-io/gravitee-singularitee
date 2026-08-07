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

import java.util.List;
import java.util.Map;

/**
 * Output from a {@link ClassifierEngine} inference call.
 *
 * <p>The {@link #topLabel()} convenience method returns the highest-scoring label.
 * The full score distribution across all labels is available via {@link #allScores()}.
 *
 * @param topLabel   the label with the highest score
 * @param topScore   the score of the top label (0.0–1.0)
 * @param allScores  full map of label → score for all classes
 * @param results    ordered list of individual {@link ClassifyResult} entries (may include
 *                   token-level results for NER / token-classification models)
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ClassifyResponse(
  String topLabel,
  float topScore,
  Map<String, Float> allScores,
  List<ClassifyResult> results
) {}
