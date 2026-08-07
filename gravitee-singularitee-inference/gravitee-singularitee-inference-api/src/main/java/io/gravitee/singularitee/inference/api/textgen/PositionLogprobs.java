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
 * Log-probability data for one generated token position, engine-agnostic.
 * Mirrors the per-token logprobs object of OpenAI-compatible APIs.
 *
 * @param chosen the token that was actually sampled at this position
 * @param top    the top-N most likely candidates, sorted by descending
 *               log-probability; always includes the chosen token
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record PositionLogprobs(TokenLogprobEntry chosen, List<TokenLogprobEntry> top) {
  public PositionLogprobs {
    top = top == null ? List.of() : List.copyOf(top);
  }
}
