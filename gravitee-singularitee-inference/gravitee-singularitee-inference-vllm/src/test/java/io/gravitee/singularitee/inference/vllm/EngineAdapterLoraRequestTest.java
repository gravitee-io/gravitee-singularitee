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
package io.gravitee.singularitee.inference.vllm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The adapter selection a sequence hands to vLLM.
 *
 * <p>The id must come from the adapter, not from the sequence: vLLM batches and caches by id, so an id per
 * sequence serializes sequences sharing an adapter, and an id reused across adapters serves the wrong weights.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class EngineAdapterLoraRequestTest {

  private static VllmRequest request(String loraName, String loraPath) {
    return new VllmRequest(
      "p",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      loraName,
      loraPath,
      null
    );
  }

  @Test
  void a_request_without_a_path_selects_no_adapter() {
    var ids = new LoraIds();
    assertThat(EngineAdapter.loraRequest(ids, request(null, null))).isNull();
    assertThat(EngineAdapter.loraRequest(ids, request("named", " "))).isNull();
  }

  @Test
  void sequences_on_one_adapter_share_its_id() {
    var ids = new LoraIds();
    var first = EngineAdapter.loraRequest(ids, request("default", "/adapters/a"));
    var second = EngineAdapter.loraRequest(ids, request("default", "/adapters/a"));

    assertThat(second).isEqualTo(first);
    assertThat(first.loraIntId()).isGreaterThanOrEqualTo(1);
    assertThat(first.loraName()).isEqualTo("default");
    assertThat(first.loraPath()).isEqualTo("/adapters/a");
  }

  @Test
  void another_adapter_never_reuses_an_id() {
    var ids = new LoraIds();
    var a = EngineAdapter.loraRequest(ids, request("a", "/adapters/a"));
    var b = EngineAdapter.loraRequest(ids, request("b", "/adapters/b"));
    var aAgain = EngineAdapter.loraRequest(ids, request("a", "/adapters/a"));

    assertThat(b.loraIntId()).isNotEqualTo(a.loraIntId());
    assertThat(aAgain.loraIntId()).isEqualTo(a.loraIntId());
  }
}
