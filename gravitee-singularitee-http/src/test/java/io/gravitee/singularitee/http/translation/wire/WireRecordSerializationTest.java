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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.gravitee.singularitee.http.translation.ParsedToolCall;
import io.gravitee.singularitee.protocol.PositionLogprobs;
import io.gravitee.singularitee.protocol.ResponseProgress;
import io.gravitee.singularitee.protocol.TodoItem;
import io.gravitee.singularitee.protocol.TokenLogprob;
import org.junit.jupiter.api.Test;

/** Exact wire JSON (field names AND order) for the fixed-schema payload records. */
class WireRecordSerializationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String json(Object value) throws Exception {
    return MAPPER.writeValueAsString(value);
  }

  @Test
  void chatUsageWithoutChannelCountsPutsEverythingInAnswerTokens() throws Exception {
    assertThat(json(new ChatUsage(10, 7, null, null))).isEqualTo(
      "{\"prompt_tokens\":10,\"completion_tokens\":7,\"total_tokens\":17," +
        "\"completion_tokens_details\":{\"answer_tokens\":7,\"reasoning_tokens\":0,\"tool_tokens\":0}}"
    );
  }

  @Test
  void chatUsageSubtractsReasoningAndToolFromAnswerTokens() throws Exception {
    assertThat(json(new ChatUsage(10, 7, 3, 2))).isEqualTo(
      "{\"prompt_tokens\":10,\"completion_tokens\":7,\"total_tokens\":17," +
        "\"completion_tokens_details\":{\"answer_tokens\":2,\"reasoning_tokens\":3,\"tool_tokens\":2}}"
    );
  }

  @Test
  void chatUsageClampsAnswerTokensAtZero() throws Exception {
    assertThat(json(new ChatUsage(1, 5, 9, 2))).isEqualTo(
      "{\"prompt_tokens\":1,\"completion_tokens\":5,\"total_tokens\":6," +
        "\"completion_tokens_details\":{\"answer_tokens\":0,\"reasoning_tokens\":9,\"tool_tokens\":2}}"
    );
  }

  @Test
  void responsesUsageDerivesTotalTokens() throws Exception {
    assertThat(json(new ResponsesUsage(3, 4))).isEqualTo(
      "{\"input_tokens\":3,\"output_tokens\":4,\"total_tokens\":7}"
    );
  }

  @Test
  void functionCallItemDuplicatesIdAsCallId() throws Exception {
    var item = new FunctionCallItem(
      new ParsedToolCall("call_1", "get_weather", "{\"city\":\"Paris\"}"),
      "completed"
    );
    assertThat(json(item)).isEqualTo(
      "{\"type\":\"function_call\",\"id\":\"call_1\",\"call_id\":\"call_1\"," +
        "\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"," +
        "\"status\":\"completed\"}"
    );
  }

  @Test
  void progressPayloadRendersMarkersAndOmitsEmptyProof() throws Exception {
    ResponseProgress progress = ResponseProgress.newBuilder()
      .setStepId("todo")
      .addTodos(
        TodoItem.newBuilder().setId("t1").setTitle("Fetch").setStatus("done").setProof("ls ok")
      )
      .addTodos(TodoItem.newBuilder().setId("t2").setTitle("Parse").setStatus("in_progress"))
      .addTodos(TodoItem.newBuilder().setId("t3").setTitle("Write").setStatus("pending"))
      .setCompleted(1)
      .setTotal(3)
      .build();

    assertThat(json(new ProgressPayload(7, progress))).isEqualTo(
      "{\"type\":\"gravitee.progress\",\"sequence_number\":7,\"step_id\":\"todo\"," +
        "\"todos\":[" +
        "{\"id\":\"t1\",\"title\":\"Fetch\",\"status\":\"done\",\"proof\":\"ls ok\"}," +
        "{\"id\":\"t2\",\"title\":\"Parse\",\"status\":\"in_progress\"}," +
        "{\"id\":\"t3\",\"title\":\"Write\",\"status\":\"pending\"}]," +
        "\"completed\":1,\"total\":3," +
        "\"text\":\"1. [x] Fetch — proof: ls ok\\n2. [>] Parse\\n3. [ ] Write\"}"
    );
  }

  @Test
  void progressPayloadWithNoTodosRendersEmptyText() throws Exception {
    ResponseProgress progress = ResponseProgress.newBuilder().setStepId("todo").build();
    assertThat(json(new ProgressPayload(0, progress))).isEqualTo(
      "{\"type\":\"gravitee.progress\",\"sequence_number\":0,\"step_id\":\"todo\"," +
        "\"todos\":[],\"completed\":0,\"total\":0,\"text\":\"\"}"
    );
  }

  @Test
  void logprobPositionConvertsRawBytesToUnsignedIntegers() throws Exception {
    TokenLogprob chosen = TokenLogprob.newBuilder()
      .setToken("é")
      .setLogprob(-0.5f)
      .setRawBytes(ByteString.copyFrom(new byte[] { (byte) 0xC3, (byte) 0xA9 }))
      .build();
    TokenLogprob alt = TokenLogprob.newBuilder()
      .setToken("a")
      .setLogprob(-1.5f)
      .setRawBytes(ByteString.copyFrom(new byte[] { 97 }))
      .build();
    PositionLogprobs position = PositionLogprobs.newBuilder()
      .setChosen(chosen)
      .addTop(chosen)
      .addTop(alt)
      .build();

    assertThat(json(new LogprobPosition(position))).isEqualTo(
      "{\"token\":\"é\",\"logprob\":-0.5,\"bytes\":[195,169]," +
        "\"top_logprobs\":[" +
        "{\"token\":\"é\",\"logprob\":-0.5,\"bytes\":[195,169]}," +
        "{\"token\":\"a\",\"logprob\":-1.5,\"bytes\":[97]}]}"
    );
  }
}
