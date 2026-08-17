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

/**
 * {@code completion_tokens_details}: always written, all buckets explicit.
 * {@code completion_tokens = answer + reasoning + tool}; {@code reasoning_tokens} follows
 * the OpenAI field name, {@code answer_tokens}/{@code tool_tokens} are explicit extensions
 * so the breakdown always sums to {@code completion_tokens}.
 */
public record CompletionTokensDetails(
  @JsonProperty("answer_tokens") int answerTokens,
  @JsonProperty("reasoning_tokens") int reasoningTokens,
  @JsonProperty("tool_tokens") int toolTokens
) {
  public CompletionTokensDetails(int completionTokens, Integer reasoning, Integer tool) {
    this(
      Math.max(
        0,
        completionTokens - (reasoning == null ? 0 : reasoning) - (tool == null ? 0 : tool)
      ),
      reasoning == null ? 0 : reasoning,
      tool == null ? 0 : tool
    );
  }
}
