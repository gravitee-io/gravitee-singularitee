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

import io.gravitee.singularitee.protocol.MemoryCheckPolicy;
import java.util.Locale;

/**
 * Maps YAML memory check policy strings to their corresponding proto enums.
 * Centralizes memory check policy parsing to eliminate string-based switches.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum MemoryCheckPolicyType {
  FAIL("fail", MemoryCheckPolicy.MEMORY_CHECK_POLICY_FAIL),
  WARN("warn", MemoryCheckPolicy.MEMORY_CHECK_POLICY_WARN),
  DISABLED("disabled", MemoryCheckPolicy.MEMORY_CHECK_POLICY_DISABLED);

  private final String yamlKey;
  private final MemoryCheckPolicy protoPolicy;

  MemoryCheckPolicyType(String yamlKey, MemoryCheckPolicy protoPolicy) {
    this.yamlKey = yamlKey;
    this.protoPolicy = protoPolicy;
  }

  /**
   * Parses a YAML memory check policy string to its corresponding proto enum value.
   * Defaults to {@code MEMORY_CHECK_POLICY_WARN} if the input is null or blank.
   *
   * @param policyStr the policy string from YAML (case-insensitive)
   * @return the matching proto policy; {@code MEMORY_CHECK_POLICY_WARN} as default
   */
  public static MemoryCheckPolicy parse(String policyStr) {
    if (policyStr == null || policyStr.isBlank()) {
      return MemoryCheckPolicy.MEMORY_CHECK_POLICY_WARN;
    }
    String normalized = policyStr.toLowerCase(Locale.ENGLISH);
    for (MemoryCheckPolicyType type : MemoryCheckPolicyType.values()) {
      if (type.yamlKey.equals(normalized)) {
        return type.protoPolicy;
      }
    }
    return MemoryCheckPolicy.MEMORY_CHECK_POLICY_WARN;
  }

  /**
   * Parses a YAML memory check policy string to the corresponding
   * {@link MemoryCheckPolicyType} enum constant.
   * Defaults to {@link #WARN} if the input is null, blank, or unrecognised.
   *
   * @param policyStr the policy string from YAML (case-insensitive)
   * @return the matching {@link MemoryCheckPolicyType}
   */
  public static MemoryCheckPolicyType parseType(String policyStr) {
    if (policyStr == null || policyStr.isBlank()) return WARN;
    String normalized = policyStr.toLowerCase(Locale.ENGLISH);
    for (MemoryCheckPolicyType type : MemoryCheckPolicyType.values()) {
      if (type.yamlKey.equals(normalized)) return type;
    }
    return WARN;
  }
}
