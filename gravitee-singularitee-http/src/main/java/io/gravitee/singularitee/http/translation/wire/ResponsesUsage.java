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
package io.gravitee.singularitee.http.translation.wire;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Responses-API {@code usage} block ({@code input/output/total_tokens}). */
public record ResponsesUsage(
  @JsonProperty("input_tokens") int inputTokens,
  @JsonProperty("output_tokens") int outputTokens,
  @JsonProperty("total_tokens") int totalTokens
) {
  public ResponsesUsage(int prompt, int completion) {
    this(prompt, completion, prompt + completion);
  }
}
