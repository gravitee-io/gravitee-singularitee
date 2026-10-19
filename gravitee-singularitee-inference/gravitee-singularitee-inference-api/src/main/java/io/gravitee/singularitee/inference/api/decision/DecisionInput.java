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

import java.util.List;

/**
 * What a decision call evaluates: a state and questions the server renders into prompts,
 * or prompts the caller already rendered.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface DecisionInput {
  /**
   * The server renders one prompt per question (or one joint prompt) from the model's
   * prompt source.
   *
   * @param state     the content to evaluate: a string or a JSON-like value
   * @param questions the questions, rendered and answered in this order
   */
  record Templated(Object state, List<Question> questions) implements DecisionInput {
    public Templated {
      questions = List.copyOf(questions);
    }
  }

  /** The caller rendered every prompt; the server only scores and post-processes. */
  record Raw(List<RawPrompt> prompts) implements DecisionInput {
    public Raw {
      prompts = List.copyOf(prompts);
    }
  }

  /**
   * One caller-rendered prompt, answering one question.
   *
   * @param questionId the id the answer is reported under
   * @param kind       the shape of the answer
   * @param optionKeys the answer keys, in the order the prompt lists them: the option keys of a
   *                   choice, {@code "0".."n-1"} for a score, the family's noul order for a noul
   * @param text       the prompt text (special tokens parsed, no BOS), or {@code null}
   * @param tokens     the prompt tokens, or {@code null}; exactly one of text and tokens is set
   */
  record RawPrompt(
    String questionId,
    QuestionKind kind,
    List<String> optionKeys,
    String text,
    int[] tokens
  ) {
    public RawPrompt {
      optionKeys = List.copyOf(optionKeys);
    }
  }
}
