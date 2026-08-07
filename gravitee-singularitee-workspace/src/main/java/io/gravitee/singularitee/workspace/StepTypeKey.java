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
package io.gravitee.singularitee.workspace;

import io.gravitee.singularitee.protocol.StepType;
import java.util.Locale;

/**
 * Maps YAML step type strings to their corresponding proto {@link StepType} enums.
 * Centralizes step type parsing logic to eliminate string-based switches.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum StepTypeKey {
  INFER("infer", StepType.STEP_TYPE_INFER),
  CLASSIFY("classify", StepType.STEP_TYPE_CLASSIFY),
  EMBED("embed", StepType.STEP_TYPE_EMBED),
  ROUTE("route", StepType.STEP_TYPE_ROUTE),
  GUARD("guard", StepType.STEP_TYPE_GUARD),
  LLM_GUARD("llm_guard", StepType.STEP_TYPE_LLM_GUARD),
  BREAK("break", StepType.STEP_TYPE_BREAK),
  LOOP("loop", StepType.STEP_TYPE_LOOP),
  SUB_PIPELINE("sub_pipeline", StepType.STEP_TYPE_SUB_PIPELINE),
  REGEX_GUARD("regex_guard", StepType.STEP_TYPE_REGEX_GUARD),
  TOOL_SELECT("tool_select", StepType.STEP_TYPE_TOOL_SELECT);

  private final String yamlKey;
  private final StepType protoType;

  StepTypeKey(String yamlKey, StepType protoType) {
    this.yamlKey = yamlKey;
    this.protoType = protoType;
  }

  /**
   * Returns the proto {@link StepType} for this enum value.
   */
  public StepType getProtoType() {
    return protoType;
  }

  /**
   * Parses a YAML step type string to its corresponding enum value.
   *
   * @param typeStr the step type string from YAML (case-insensitive)
   * @return the matching StepTypeKey enum value
   * @throws IllegalArgumentException if the type is not recognized
   */
  public static StepTypeKey parse(String typeStr) {
    if (typeStr == null || typeStr.isBlank()) {
      throw new IllegalArgumentException("Step type cannot be null or blank");
    }
    String normalized = typeStr.toLowerCase(Locale.ENGLISH);
    for (StepTypeKey type : StepTypeKey.values()) {
      if (type.yamlKey.equals(normalized)) {
        return type;
      }
    }
    throw new IllegalArgumentException(
      "Unknown step type '" +
        typeStr +
        "'. Supported types: " +
        "infer, classify, embed, route, guard, llm_guard, break, loop, sub_pipeline, " +
        "regex_guard, tool_select"
    );
  }
}
