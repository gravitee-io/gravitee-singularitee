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
package io.gravitee.singularitee.http.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PayloadValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode json(String s) throws Exception {
    return mapper.readTree(s);
  }

  @Test
  void validChatPasses() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.CHAT,
        json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
      )
    ).isEmpty();
  }

  @Test
  void chatMissingMessagesFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(SchemaName.CHAT, json("{\"model\":\"m\"}"))
    ).isNotEmpty();
  }

  @Test
  void chatMessagesWrongTypeFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.CHAT,
        json("{\"model\":\"m\",\"messages\":\"oops\"}")
      )
    ).isNotEmpty();
  }

  @Test
  void embeddingsBadEncodingFormatFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.EMBEDDINGS,
        json("{\"model\":\"m\",\"input\":\"hi\",\"encoding_format\":\"xml\"}")
      )
    ).isNotEmpty();
  }

  @Test
  void similarityMissingCandidatesFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.SIMILARITY,
        json("{\"model\":\"m\",\"input\":\"hi\"}")
      )
    ).isNotEmpty();
  }

  @Test
  void chatInvalidReasoningEffortFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.CHAT,
        json(
          "{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}],\"reasoning_effort\":\"extreme\"}"
        )
      )
    ).isNotEmpty();
  }

  @Test
  void chatValidReasoningEffortPasses() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.CHAT,
        json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}],\"reasoning_effort\":\"high\"}")
      )
    ).isEmpty();
  }

  @Test
  void chatInvalidNestedReasoningEffortFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.CHAT,
        json(
          "{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}],\"reasoning\":{\"effort\":\"extreme\"}}"
        )
      )
    ).isNotEmpty();
  }

  @Test
  void responsesInvalidReasoningEffortFails() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.RESPONSES,
        json("{\"model\":\"m\",\"input\":\"hi\",\"reasoning_effort\":\"extreme\"}")
      )
    ).isNotEmpty();
  }

  @Test
  void responsesValidNestedReasoningEffortPasses() throws Exception {
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.RESPONSES,
        json("{\"model\":\"m\",\"input\":\"hi\",\"reasoning\":{\"effort\":\"medium\"}}")
      )
    ).isEmpty();
  }

  @Test
  void unknownFieldsAreAllowed() throws Exception {
    // Lenient additionalProperties: SDK-added params must not be rejected.
    assertThat(
      PayloadValidator.INSTANCE.validate(
        SchemaName.CHAT,
        json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}],\"future_param\":true}")
      )
    ).isEmpty();
  }
}
