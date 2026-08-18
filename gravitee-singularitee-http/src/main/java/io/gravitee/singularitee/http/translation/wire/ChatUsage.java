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

/** Chat/legacy-completions {@code usage} block. */
public record ChatUsage(
  @JsonProperty("prompt_tokens") int promptTokens,
  @JsonProperty("completion_tokens") int completionTokens,
  @JsonProperty("total_tokens") int totalTokens,
  @JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails
) {
  public ChatUsage(int prompt, int completion, Integer reasoning, Integer tool) {
    this(
      prompt,
      completion,
      prompt + completion,
      new CompletionTokensDetails(completion, reasoning, tool)
    );
  }
}
