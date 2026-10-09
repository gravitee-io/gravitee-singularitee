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
package io.gravitee.singularitee.inference.vllm.decision;

import java.util.List;
import java.util.Map;

/**
 * What {@link VllmLabelBackend} needs from a vLLM engine: tokenize, and read the log probability
 * of chosen label tokens at the position after a prompt. Not thread-safe; one scheduler thread
 * drives it.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
interface LabelEngine extends AutoCloseable {
  /** Tokenizes without BOS; {@code specials} parses special-token markup. */
  int[] encode(String text, boolean specials);

  /** The model's chat template, or {@code null}. */
  String chatTemplate();

  /** Queues a prompt whose next-token log probabilities are read for {@code labels}. */
  void add(String requestId, int[] tokens, int[] labels);

  /** Runs one engine step and returns the requests it finished. */
  List<Scored> step();

  /** Whether queued requests are still running. */
  boolean busy();

  /** Drops a queued or running request. */
  void abort(String requestId);

  @Override
  void close();

  /**
   * A finished request.
   *
   * @param requestId the id it was queued under
   * @param logprobs  token id to log probability at the first generated position
   */
  record Scored(String requestId, Map<Integer, Double> logprobs) {}
}
