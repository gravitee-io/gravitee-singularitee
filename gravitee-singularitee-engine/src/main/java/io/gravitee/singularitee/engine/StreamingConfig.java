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
package io.gravitee.singularitee.engine;

/**
 * Server-wide streaming policy, set once at startup from {@code gravitee.yml}.
 *
 * <p>Holds the per-sequence token buffer depth used by every {@code rxStream}: the
 * number of generated tokens a single (slow) client may fall behind before its stream
 * is cancelled rather than buffered without bound. It is a server policy, not a model
 * attribute, so it lives here as a process-wide value shared by the local and remote
 * text-gen engines — mirroring how {@code OnnxInference} holds its static thread-count
 * tuning. The value is read on each new stream, so setting it during context startup
 * (before any request) is sufficient.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class StreamingConfig {

  /**
   * Default per-sequence token buffer depth — a few seconds of generation, enough to
   * absorb event-loop jitter and TLS write bursts, small enough to bound memory per
   * stream to tens of KB.
   */
  public static final int DEFAULT_STREAM_BUFFER_CAPACITY = 256;

  private static volatile int streamBufferCapacity = DEFAULT_STREAM_BUFFER_CAPACITY;

  private StreamingConfig() {}

  /** The per-sequence bounded buffer depth before a slow client's stream is cancelled. */
  public static int streamBufferCapacity() {
    return streamBufferCapacity;
  }

  /**
   * Sets the server-wide buffer depth (typically from {@code ai.streaming.buffer-capacity}
   * in {@code gravitee.yml}). Non-positive values reset to {@link #DEFAULT_STREAM_BUFFER_CAPACITY}.
   */
  public static void setStreamBufferCapacity(int capacity) {
    streamBufferCapacity = capacity > 0 ? capacity : DEFAULT_STREAM_BUFFER_CAPACITY;
  }
}
