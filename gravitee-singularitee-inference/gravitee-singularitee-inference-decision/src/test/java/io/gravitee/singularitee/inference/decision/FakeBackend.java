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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A backend whose tokenizer maps each character to its code point, except the special tokens
 * listed, and whose scores come from a function of the prompt.
 */
final class FakeBackend implements DecisionBackend {

  static final int MASK = -1;
  static final int SEP = -2;
  static final int CLS = -3;

  final DecisionFamily family;
  String decisionTemplate;
  String chatTemplate;
  Map<String, Float> temperatures = Map.of();
  List<String> labelTexts = List.of();
  int maxOptions = 52;
  LayaVocab layaVocab;
  Function<BackendPrompt, float[]> scorer = p -> new float[p.nOutputs()];
  final List<BackendPrompt> scored = new ArrayList<>();
  final List<String> tokenized = new ArrayList<>();

  FakeBackend(DecisionFamily family) {
    this.family = family;
  }

  @Override
  public DecisionFamily family() {
    return family;
  }

  @Override
  public String decisionTemplate() {
    return decisionTemplate;
  }

  @Override
  public String chatTemplate() {
    return chatTemplate;
  }

  @Override
  public Map<String, Float> temperatures() {
    return temperatures;
  }

  @Override
  public List<String> labelTexts() {
    return labelTexts;
  }

  @Override
  public int maxOptions() {
    return maxOptions;
  }

  @Override
  public int[] tokenize(String text) {
    tokenized.add(text);
    var out = new IntList(text.length());
    int i = 0;
    while (i < text.length()) {
      if (text.startsWith("[MASK]", i)) {
        out.add(MASK);
        i += 6;
      } else if (text.startsWith("[SEP]", i)) {
        out.add(SEP);
        i += 5;
      } else if (text.startsWith("[CLS]", i)) {
        out.add(CLS);
        i += 5;
      } else {
        out.add(text.charAt(i++));
      }
    }
    return out.toArray();
  }

  @Override
  public LayaVocab layaVocab() {
    return layaVocab;
  }

  @Override
  public List<float[]> score(List<BackendPrompt> prompts) {
    scored.addAll(prompts);
    return prompts.stream().map(scorer).toList();
  }

  @Override
  public void close() {}

  static String text(int[] tokens) {
    var out = new StringBuilder();
    for (int t : tokens) {
      out.append(
        switch (t) {
          case MASK -> "[MASK]";
          case SEP -> "[SEP]";
          case CLS -> "[CLS]";
          default -> String.valueOf((char) t);
        }
      );
    }
    return out.toString();
  }
}
