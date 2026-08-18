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
import io.gravitee.singularitee.protocol.PositionLogprobs;
import java.util.List;

/** One position of the OpenAI {@code logprobs.content} array: the chosen token plus candidates. */
public record LogprobPosition(
  String token,
  double logprob,
  List<Integer> bytes,
  @JsonProperty("top_logprobs") List<LogprobCandidate> topLogprobs
) {
  public LogprobPosition(PositionLogprobs position) {
    this(
      new LogprobCandidate(position.getChosen()),
      position.getTopList().stream().map(LogprobCandidate::new).toList()
    );
  }

  private LogprobPosition(LogprobCandidate chosen, List<LogprobCandidate> top) {
    this(chosen.token(), chosen.logprob(), chosen.bytes(), top);
  }
}
