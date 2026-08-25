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
package io.gravitee.singularitee.plugin.regexguard;

import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import java.util.List;

/**
 * Runtime config of a {@code regex_guard} step: named patterns and the action taken on a
 * match.
 *
 * @param inputField           context key to scan; blank = the prompt
 * @param patterns             named patterns, evaluated in order
 * @param redactWithEntityType replace spans with {@code [NAME]} instead of {@code [REDACTED]}
 * @param action               what happens on a match
 * @param outputField          context key receiving the (possibly redacted) text; blank =
 *                             {@code <stepId>.output}
 * @param message              Jinja reject message; blank = none
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record RegexGuardStepConfig(
  String inputField,
  List<RegexEntity> patterns,
  boolean redactWithEntityType,
  GuardAction action,
  String outputField,
  String message
) {
  public RegexGuardStepConfig {
    inputField = inputField == null ? "" : inputField;
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
    action = action == null ? GuardAction.REJECT : action;
    outputField = outputField == null ? "" : outputField;
    message = message == null ? "" : message;
  }

  /** One named pattern: a free-form label and a Java regular expression. */
  public record RegexEntity(String name, String pattern) {
    public RegexEntity {
      name = name == null ? "" : name;
      pattern = pattern == null ? "" : pattern;
    }
  }
}
