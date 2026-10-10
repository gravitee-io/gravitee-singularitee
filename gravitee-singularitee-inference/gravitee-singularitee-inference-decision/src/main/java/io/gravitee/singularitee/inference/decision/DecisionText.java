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

/**
 * The plain-text form of a decision value (a state, instructions, an option description) for a
 * model that reads text only: objects become {@code key: value} lines, arrays {@code - item}
 * lines, strings stay as they are.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionText {

  private DecisionText() {}

  /** The text of {@code value}; empty for {@code null}. */
  public static String of(Object value) {
    return TemplateValues.kevRender(value, 0);
  }
}
