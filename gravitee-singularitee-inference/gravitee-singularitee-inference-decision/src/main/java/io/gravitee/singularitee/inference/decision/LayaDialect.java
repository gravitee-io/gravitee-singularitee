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

import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * laya: one column of the hidden state at one mask marker per option. The prompt is
 * {@code [cls] question [sep] ([mask] option)* [sep] state [sep]}, with the question and the
 * options cut to the token budget the model was trained with.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
record LayaDialect() implements DecisionDialect {
  /** Most tokens one option keeps, its marker excluded. */
  static final int MAX_OPTION_TOKENS = 48;

  /** Removes the mask text from every input string, so the only markers are the template's. */
  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> variables(
    Object state,
    List<Question> questions,
    Question question,
    int variant,
    DecisionBackend backend
  ) {
    Map<String, Object> vars = DecisionDialect.super.variables(
      state,
      questions,
      question,
      variant,
      backend
    );
    String markerText = backend.layaVocab().markerText();
    if (markerText == null || markerText.isEmpty()) return vars;
    Object images = vars.get("images");
    var out = (Map<String, Object>) TemplateValues.replaceText(vars, markerText, " ");
    out.put("images", images);
    return out;
  }

  @Override
  public BackendPrompt prompt(
    int[] tokens,
    QuestionKind kind,
    int nOutputs,
    DecisionBackend backend
  ) {
    return new BackendPrompt.Laya(cut(tokens, nOutputs, backend.layaVocab()), kind, nOutputs);
  }

  /** Cuts the question and the options to {@code maxHeadTokens}, the way the model was trained. */
  static int[] cut(int[] tokens, int nOptions, DecisionBackend.LayaVocab vocab) {
    int sep = vocab.sepToken();
    int[] markers = Arrays.stream(indices(tokens.length))
      .filter(i -> tokens[i] == vocab.markerToken())
      .toArray();
    if (
      markers.length != nOptions ||
      markers[0] < 2 ||
      tokens[markers[0] - 1] != sep ||
      tokens[tokens.length - 1] != sep
    ) {
      throw invalidLayout();
    }
    int headEnd = markers[0] - 1;
    int optsEnd = markers[markers.length - 1];
    while (optsEnd < tokens.length && tokens[optsEnd] != sep) optsEnd++;
    if (optsEnd + 1 >= tokens.length) throw invalidLayout();

    var options = new ArrayList<int[]>(nOptions);
    for (int i = 0; i < nOptions; i++) {
      int end = i + 1 < nOptions ? markers[i + 1] : optsEnd;
      options.add(Arrays.copyOfRange(tokens, markers[i], end));
    }
    int maxHead = vocab.maxHeadTokens();
    int nOptionTokens = shrink(options, MAX_OPTION_TOKENS + 1);
    if (nOptionTokens + 16 > maxHead) {
      nOptionTokens = shrink(options, Math.max(4, (maxHead - Math.min(maxHead, 16)) / nOptions));
    }
    int nQuestionMax = Math.max(8, maxHead - Math.min(maxHead, nOptionTokens));

    var out = new IntList(tokens.length);
    out.add(tokens[0]);
    out.addRange(tokens, 1, Math.min(headEnd, 1 + nQuestionMax));
    out.add(sep);
    for (int[] option : options) out.addRange(option, 0, option.length);
    out.addRange(tokens, optsEnd, tokens.length);
    return out.toArray();
  }

  private static int shrink(List<int[]> options, int max) {
    int total = 0;
    for (int i = 0; i < options.size(); i++) {
      int[] option = options.get(i);
      if (option.length > max) options.set(i, Arrays.copyOf(option, max));
      total += options.get(i).length;
    }
    return total;
  }

  private static int[] indices(int n) {
    int[] out = new int[n];
    for (int i = 0; i < n; i++) out[i] = i;
    return out;
  }

  private static IllegalStateException invalidLayout() {
    return new IllegalStateException("unexpected layout of the decision prompt");
  }
}
