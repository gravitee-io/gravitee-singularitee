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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A typed question about a state.
 *
 * <p>Instructions, descriptions and levels are JSON-like values ({@code String},
 * {@code Number}, {@code Boolean}, {@code Map}, {@code List} or
 * {@code null}): they reach the prompt template as they are.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface Question {
  /** Unique within a request. */
  String id();

  /** The question itself. */
  Object instructions();

  /** The type of the question. */
  QuestionKind kind();

  /** The answers this question offers, in prompt order (before any family reordering). */
  List<Option> options();

  /** A possible answer: its key and an optional description. */
  record Option(String key, Object description) {}

  /** Pick one of {@code options}. */
  record Choice(String id, Object instructions, List<Option> options) implements Question {
    public Choice {
      options = List.copyOf(options);
    }

    @Override
    public QuestionKind kind() {
      return QuestionKind.CHOICE;
    }
  }

  /** Place the state on {@code levels}, lowest first; level {@code i} has key {@code "i"}. */
  record Score(String id, Object instructions, List<Object> levels) implements Question {
    public Score {
      levels = Collections.unmodifiableList(new ArrayList<>(levels));
    }

    @Override
    public QuestionKind kind() {
      return QuestionKind.SCORE;
    }

    @Override
    public List<Option> options() {
      var out = new ArrayList<Option>(levels.size());
      for (int i = 0; i < levels.size(); i++) out.add(new Option(String.valueOf(i), levels.get(i)));
      return out;
    }
  }

  /** Yes or no; the optional descriptions say what each answer means. */
  record Noul(String id, Object instructions, Object whenTrue, Object whenFalse) implements
    Question {
    @Override
    public QuestionKind kind() {
      return QuestionKind.NOUL;
    }

    /** {@code false} then {@code true}; families that were trained the other way round swap them. */
    @Override
    public List<Option> options() {
      return List.of(new Option("false", whenFalse), new Option("true", whenTrue));
    }
  }
}
