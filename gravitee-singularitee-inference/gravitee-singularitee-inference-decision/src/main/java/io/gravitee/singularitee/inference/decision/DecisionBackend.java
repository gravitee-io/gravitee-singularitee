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

import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import java.util.List;
import java.util.Map;

/**
 * The engine-specific part of a decision model: what it read from its weights, and the forward
 * pass. Everything else (rendering, prompt layout, answers) is shared in {@link DecisionRunner}.
 *
 * <p>Implementations must be safe to call from several threads: the runner does not serialise.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface DecisionBackend extends AutoCloseable {
  /** The label codes of a custom model when none are configured: one capital letter per option. */
  List<String> DEFAULT_CODES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".chars()
    .mapToObj(c -> String.valueOf((char) c))
    .toList();

  /** How the model reads its scores. */
  DecisionFamily family();

  /** The model's own decision prompt template ({@code systemone}), or {@code null}. */
  String decisionTemplate();

  /** The model's chat template, or {@code null}; used by the {@code chat} prompt source. */
  String chatTemplate();

  /** Calibration temperatures read from the model, keyed like {@code choice} or {@code choice.3_5}. */
  Map<String, Float> temperatures();

  /**
   * The label code shown in the prompt for each option position (lev, nimble, custom); empty for
   * families whose template does not show codes.
   */
  List<String> labelTexts();

  /** Most options one question may have. */
  int maxOptions();

  /** Tokenizes text with special-token parsing and without BOS, as a rendered template expects. */
  int[] tokenize(String text);

  /** The special tokens a laya prompt is built from; {@code null} for the other families. */
  LayaVocab layaVocab();

  /**
   * Scores each prompt.
   *
   * @return one {@code float[prompt.nOutputs()]} per prompt, in input order
   */
  List<float[]> score(List<BackendPrompt> prompts);

  @Override
  void close();

  /**
   * @param markerToken   the mask token marking each option
   * @param sepToken      the separator token
   * @param markerText    the text of the mask token, removed from inputs before rendering
   * @param maxHeadTokens the budget of the question and its options, as trained
   */
  record LayaVocab(int markerToken, int sepToken, String markerText, int maxHeadTokens) {}
}
