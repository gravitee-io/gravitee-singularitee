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
package io.gravitee.singularitee.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The trace session id groups a conversation's turns: the client cache-affinity key when supplied
 * (shared across the conversation), otherwise the per-turn request id.
 */
class PipelineExecutorTest {

  @Test
  void session_id_is_the_cache_key_when_present() {
    assertThat(PipelineExecutor.sessionId("conv-42", "req-1")).isEqualTo("conv-42");
  }

  @Test
  void session_id_falls_back_to_the_request_id_without_a_cache_key() {
    assertThat(PipelineExecutor.sessionId(null, "req-1")).isEqualTo("req-1");
    assertThat(PipelineExecutor.sessionId("", "req-1")).isEqualTo("req-1");
    assertThat(PipelineExecutor.sessionId("   ", "req-1")).isEqualTo("req-1");
  }
}
