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
import io.gravitee.singularitee.protocol.ResponseProgress;
import java.util.List;

/**
 * The gravitee-namespaced Responses-API progress event
 * ({@code RESPONSE_EVENT_TYPE_PROGRESS} → {@code {"type":"gravitee.progress", ...}}).
 * {@code text} is the preformatted multi-line plan view ({@code "1. [x] title\n2. [>] …"};
 * markers {@code [x]} done, {@code [>]} in_progress, {@code [ ]} pending) so a client can
 * print the plan directly instead of laying out the structured items itself.
 */
public record ProgressPayload(
  String type,
  @JsonProperty("sequence_number") long sequenceNumber,
  @JsonProperty("step_id") String stepId,
  List<ProgressTodo> todos,
  int completed,
  int total,
  String text
) {
  public ProgressPayload(long sequenceNumber, ResponseProgress progress) {
    this(
      "gravitee.progress",
      sequenceNumber,
      progress.getStepId(),
      progress.getTodosList().stream().map(ProgressTodo::new).toList(),
      progress.getCompleted(),
      progress.getTotal(),
      progressText(progress)
    );
  }

  private static String progressText(ResponseProgress progress) {
    StringBuilder sb = new StringBuilder();
    int index = 1;
    for (var t : progress.getTodosList()) {
      String marker = switch (t.getStatus()) {
        case "done" -> "[x]";
        case "in_progress" -> "[>]";
        default -> "[ ]";
      };
      if (index > 1) {
        sb.append('\n');
      }
      sb.append(index++).append(". ").append(marker).append(' ').append(t.getTitle());
      if (!t.getProof().isEmpty()) {
        sb.append(" — proof: ").append(t.getProof());
      }
    }
    return sb.toString();
  }
}
