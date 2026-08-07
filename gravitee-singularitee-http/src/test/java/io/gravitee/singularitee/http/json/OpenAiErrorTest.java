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
package io.gravitee.singularitee.http.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiErrorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void escapesQuotesAndNewlinesAndStructuresEnvelope() throws Exception {
    // A message with quotes + newline would corrupt a string-formatted envelope; assert it
    // round-trips through Jackson intact.
    String raw = "bad \"quote\"\nsecond line";
    JsonNode n = mapper.readTree(
      OpenAiError.json(raw, "invalid_request_error", "model", "model_not_found")
    );
    assertThat(n.at("/error/message").asText()).isEqualTo(raw);
    assertThat(n.at("/error/type").asText()).isEqualTo("invalid_request_error");
    assertThat(n.at("/error/param").asText()).isEqualTo("model");
    assertThat(n.at("/error/code").asText()).isEqualTo("model_not_found");
  }

  @Test
  void nullParamAndCodeAreJsonNull() throws Exception {
    JsonNode n = mapper.readTree(OpenAiError.json("oops", "internal_error", null, null));
    assertThat(n.at("/error/param").isNull()).isTrue();
    assertThat(n.at("/error/code").isNull()).isTrue();
  }
}
