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
package io.gravitee.singularitee.inference.api.textgen;

import java.util.List;

/**
 * One candidate token with its log-probability, engine-agnostic.
 *
 * @param token    decoded token text (may be an invalid UTF-8 fragment on its own)
 * @param tokenId  engine token id
 * @param logprob  natural-log probability
 * @param bytes    raw token bytes (unsigned, 0-255) for byte-exact reconstruction;
 *                 never {@code null}, may be empty
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record TokenLogprobEntry(String token, int tokenId, double logprob, List<Integer> bytes) {
  public TokenLogprobEntry {
    bytes = bytes == null ? List.of() : List.copyOf(bytes);
  }
}
