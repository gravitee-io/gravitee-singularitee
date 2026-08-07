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
package io.gravitee.singularitee.http.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InferenceResponseFormatter#chatStreamEventsWithToolHoldback}. */
class ChatToolHoldbackStreamTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TokenMessage content(String s) {
    return new TokenMessage(s, 0, false, null, 0, 0, null, null, null, null);
  }

  private static TokenMessage reasoning(String s) {
    return new TokenMessage(null, s, 0, false, null, 0, 0, null, null, null, null);
  }

  private static TokenMessage fin(String finishReason) {
    return new TokenMessage(null, 0, true, finishReason, 3, 7, null, null, null, null);
  }

  private List<JsonNode> run(boolean includeUsage, TokenMessage... tokens) {
    List<ServerEvent> events = InferenceResponseFormatter.chatStreamEventsWithToolHoldback(
      Flowable.fromArray(tokens),
      "test-model",
      includeUsage,
      null
    )
      .toList()
      .blockingGet();
    assertThat(events).isNotEmpty();
    assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
    List<JsonNode> chunks = new ArrayList<>();
    for (ServerEvent e : events) {
      if (!"[DONE]".equals(e.data())) {
        try {
          chunks.add(MAPPER.readTree(e.data()));
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
    }
    return chunks;
  }

  private static List<String> contentDeltas(List<JsonNode> chunks) {
    List<String> out = new ArrayList<>();
    for (JsonNode c : chunks) {
      JsonNode delta = c.at("/choices/0/delta/content");
      if (!delta.isMissingNode() && !delta.asText().isEmpty()) {
        out.add(delta.asText());
      }
    }
    return out;
  }

  private static String finishReason(List<JsonNode> chunks) {
    String finish = null;
    for (JsonNode c : chunks) {
      JsonNode fr = c.at("/choices/0/finish_reason");
      if (!fr.isMissingNode() && !fr.isNull()) {
        finish = fr.asText();
      }
    }
    return finish;
  }

  private static List<JsonNode> toolCallDeltas(List<JsonNode> chunks) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode c : chunks) {
      JsonNode tc = c.at("/choices/0/delta/tool_calls");
      if (tc.isArray()) {
        out.add(tc.get(0));
      }
    }
    return out;
  }

  @Test
  void noToolCallStreamsLiveAsMultipleDeltas() {
    List<JsonNode> chunks = run(
      false,
      reasoning("thinking..."),
      content("Hello"),
      content(" world"),
      fin("stop")
    );

    assertThat(contentDeltas(chunks)).containsExactly("Hello", " world");
    // Reasoning passes through untouched.
    List<String> reasoningDeltas = new ArrayList<>();
    for (JsonNode c : chunks) {
      JsonNode r = c.at("/choices/0/delta/reasoning_content");
      if (!r.isMissingNode()) {
        reasoningDeltas.add(r.asText());
      }
    }
    assertThat(reasoningDeltas).containsExactly("thinking...");
    assertThat(finishReason(chunks)).isEqualTo("stop");
    assertThat(toolCallDeltas(chunks)).isEmpty();
    // Role-only chunk is first.
    assertThat(chunks.get(0).at("/choices/0/delta/role").asText()).isEqualTo("assistant");
  }

  @Test
  void toolCallSplitAcrossDeltasNeverLeaksMarkup() {
    List<JsonNode> chunks = run(
      false,
      content("Let me check. "),
      content("<tool"),
      content("_call>"),
      content("{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}"),
      content("</tool_call>"),
      fin("tool_calls")
    );

    List<String> deltas = contentDeltas(chunks);
    // Text before the opener streamed; markup never appeared in content.
    assertThat(String.join("", deltas)).isEqualTo("Let me check. ");
    assertThat(deltas).allSatisfy(d -> assertThat(d).doesNotContain("<tool"));

    List<JsonNode> toolCalls = toolCallDeltas(chunks);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("get_weather");
    assertThat(toolCalls.get(0).at("/function/arguments").asText()).contains("Paris");
    assertThat(finishReason(chunks)).isEqualTo("tool_calls");
  }

  @Test
  void danglingPartialOpenerIsFlushedOnStop() {
    List<JsonNode> chunks = run(false, content("result: "), content("<tool"), fin("stop"));

    assertThat(String.join("", contentDeltas(chunks))).isEqualTo("result: <tool");
    assertThat(finishReason(chunks)).isEqualTo("stop");
    assertThat(toolCallDeltas(chunks)).isEmpty();
  }

  @Test
  void confirmedOpenerButUnparseableMarkupFlushesAsContent() {
    List<JsonNode> chunks = run(
      false,
      content("hmm "),
      content("<tool_call>not json at all"),
      fin("stop")
    );

    assertThat(String.join("", contentDeltas(chunks))).isEqualTo("hmm <tool_call>not json at all");
    assertThat(finishReason(chunks)).isEqualTo("stop");
    assertThat(toolCallDeltas(chunks)).isEmpty();
  }

  @Test
  void xmlFunctionOpenerIsHonored() {
    List<JsonNode> chunks = run(
      false,
      content("Sure. "),
      content("<func"),
      content("tion=get_weather>"),
      content("<parameter=city>Paris</parameter>"),
      content("</function>"),
      fin("tool_calls")
    );

    List<String> deltas = contentDeltas(chunks);
    assertThat(String.join("", deltas)).isEqualTo("Sure. ");
    assertThat(deltas).allSatisfy(d -> assertThat(d).doesNotContain("<func"));

    List<JsonNode> toolCalls = toolCallDeltas(chunks);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("get_weather");
    assertThat(toolCalls.get(0).at("/function/arguments").asText()).isEqualTo(
      "{\"city\":\"Paris\"}"
    );
    assertThat(finishReason(chunks)).isEqualTo("tool_calls");
  }

  @Test
  void xmlFunctionArgumentsAreCoercedWithSchemas() throws Exception {
    JsonNode tools = MAPPER.readTree(
      "[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"parameters\":" +
        "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}," +
        "\"filePath\":{\"type\":\"string\"}}}}}]"
    );
    List<ServerEvent> events = InferenceResponseFormatter.chatStreamEventsWithToolHoldback(
      Flowable.fromArray(
        content("<function=read>"),
        content("<parameter=filePath>/tmp/a.txt</parameter>"),
        content("<parameter=limit>\n80\n</parameter>"),
        content("</function>"),
        fin("tool_calls")
      ),
      "test-model",
      false,
      null,
      InferenceResponseFormatter.toolParameterSchemas(tools)
    )
      .toList()
      .blockingGet();

    String arguments = null;
    for (ServerEvent e : events) {
      if ("[DONE]".equals(e.data())) {
        continue;
      }
      JsonNode tc = MAPPER.readTree(e.data()).at("/choices/0/delta/tool_calls");
      if (tc.isArray()) {
        arguments = tc.get(0).at("/function/arguments").asText();
      }
    }
    assertThat(arguments).isNotNull();
    JsonNode args = MAPPER.readTree(arguments);
    assertThat(args.get("limit").isIntegralNumber()).isTrue();
    assertThat(args.get("limit").asInt()).isEqualTo(80);
    assertThat(args.get("filePath").asText()).isEqualTo("/tmp/a.txt");
  }

  @Test
  void gemmaOpenerSplitAcrossDeltasNeverLeaksMarkup() {
    List<JsonNode> chunks = run(
      false,
      content("On it. "),
      content("<|tool"),
      content("_call>call:send_email{to:<|\"|>jamie@acme.com<|\"|>,"),
      content("body:<|\"|>Reminder: report due tomorrow.<|\"|>}"),
      content("<tool_call|>"),
      fin("tool_calls")
    );

    List<String> deltas = contentDeltas(chunks);
    assertThat(String.join("", deltas)).isEqualTo("On it. ");
    assertThat(deltas).allSatisfy(d -> assertThat(d).doesNotContain("<|tool"));

    List<JsonNode> toolCalls = toolCallDeltas(chunks);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("send_email");
    assertThat(toolCalls.get(0).at("/function/arguments").asText())
      .contains("jamie@acme.com")
      .contains("Reminder: report due tomorrow.");
    assertThat(finishReason(chunks)).isEqualTo("tool_calls");
  }

  @Test
  void plainPipeBracketContentIsNotSwallowed() {
    List<JsonNode> chunks = run(false, content("a <| b"), content(" rest"), fin("stop"));

    assertThat(String.join("", contentDeltas(chunks))).isEqualTo("a <| b rest");
    assertThat(finishReason(chunks)).isEqualTo("stop");
    assertThat(toolCallDeltas(chunks)).isEmpty();
  }

  @Test
  void usageChunkAndOnFinalAreEmittedWhenRequested() {
    AtomicReference<TokenMessage> finalToken = new AtomicReference<>();
    List<ServerEvent> events = InferenceResponseFormatter.chatStreamEventsWithToolHoldback(
      Flowable.fromArray(content("hi"), fin("stop")),
      "test-model",
      true,
      finalToken::set
    )
      .toList()
      .blockingGet();

    assertThat(finalToken.get()).isNotNull();
    assertThat(finalToken.get().finishReason()).isEqualTo("stop");
    String usageEvent = events.get(events.size() - 2).data();
    assertThat(usageEvent).contains("\"prompt_tokens\":3").contains("\"completion_tokens\":7");
    assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
  }
}
