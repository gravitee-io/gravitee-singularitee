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

import io.gravitee.singularitee.protocol.TokenLogprob;
import java.util.ArrayList;
import java.util.List;

/** A single candidate inside {@code top_logprobs}. */
public record LogprobCandidate(String token, double logprob, List<Integer> bytes) {
  public LogprobCandidate(TokenLogprob t) {
    this(t.getToken(), t.getLogprob(), unsignedBytes(t));
  }

  private static List<Integer> unsignedBytes(TokenLogprob t) {
    byte[] raw = t.getRawBytes().toByteArray();
    List<Integer> bytes = new ArrayList<>(raw.length);
    for (byte b : raw) {
      bytes.add(b & 0xFF);
    }
    return bytes;
  }
}
