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
package io.gravitee.singularitee.engine.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.tools.ToolCallExtractor.ExtractedToolCall;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Template-driven tool-call extraction: the built-in dialect templates must accept exactly what
 * the legacy hand-coded parsers accepted (these expectations are ported from the former HTTP-layer
 * dialect tests), plus inline custom templates and fail-open behavior.
 */
class ToolCallExtractorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static List<ExtractedToolCall> extract(String output) {
    return ToolCallExtractor.extract(output, List.of(), null);
  }

  private static JsonNode argsOf(ExtractedToolCall call) {
    try {
      return MAPPER.readTree(call.argumentsJson());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Nested
  class ChatmlJson {

    @Test
    void taggedJsonBlockParses() {
      var calls = extract(
        "<tool_call>{\"name\":\"read\",\"arguments\":{\"filePath\":\"/tmp/a\"}}</tool_call>"
      );
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("read");
      assertThat(argsOf(calls.get(0)).get("filePath").asText()).isEqualTo("/tmp/a");
      assertThat(calls.get(0).coercibleArgs()).isEmpty();
    }

    @Test
    void bareJsonObjectParses() {
      var calls = extract("{\"name\":\"send_email\",\"arguments\":{\"to\":\"a@b.c\"}}");
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).argumentsJson()).isEqualTo("{\"to\":\"a@b.c\"}");
    }

    @Test
    void concatenatedBareJsonObjectsParseAsMultipleCalls() {
      var calls = extract(
        "{\"name\":\"a\",\"arguments\":{}}\n{\"name\":\"b\",\"arguments\":{\"x\":1}}"
      );
      assertThat(calls).hasSize(2);
      assertThat(calls.get(0).name()).isEqualTo("a");
      assertThat(calls.get(1).name()).isEqualTo("b");
      assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"x\":1}");
    }

    @Test
    void bareJsonArrayParses() {
      var calls = extract("[{\"name\":\"a\",\"arguments\":{}},{\"name\":\"b\"}]");
      assertThat(calls).hasSize(2);
      assertThat(calls.get(1).argumentsJson()).isEqualTo("{}");
    }

    @Test
    void textualArgumentsStringIsKeptRaw() {
      var calls = extract(
        "<tool_call>{\"name\":\"f\",\"arguments\":\"{\\\"x\\\":1}\"}</tool_call>"
      );
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).argumentsJson()).isEqualTo("{\"x\":1}");
    }

    @Test
    void garbageYieldsNoCalls() {
      assertThat(extract("not a tool call")).isEmpty();
      assertThat(extract("{broken json")).isEmpty();
      assertThat(ToolCallExtractor.extract(null, List.of(), null)).isEmpty();
    }
  }

  @Nested
  class XmlFunction {

    @Test
    void functionBlockParsesWithAllArgsCoercible() {
      var calls = extract(
        "<tool_call><function=read>\n<parameter=filePath>\n/tmp/a.txt\n</parameter>\n" +
          "<parameter=limit>\n80\n</parameter>\n</function></tool_call>"
      );
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("read");
      JsonNode args = argsOf(calls.get(0));
      assertThat(args.get("filePath").asText()).isEqualTo("/tmp/a.txt");
      assertThat(args.get("limit").asText()).isEqualTo("80");
      assertThat(calls.get(0).coercibleArgs()).containsExactly("filePath", "limit");
    }

    @Test
    void multiLineValueKeepsInnerContentTrimmedAtEdges() {
      String value = "line one\n  line two\nline three";
      var calls = extract(
        "<function=read><parameter=filePath>\n" + value + "\n</parameter></function>"
      );
      assertThat(argsOf(calls.get(0)).get("filePath").asText()).isEqualTo(value);
    }

    @Test
    void multipleFunctionsYieldMultipleCalls() {
      var calls = extract(
        "<function=a><parameter=x>1</parameter></function>" +
          "<function=b><parameter=y>2</parameter></function>"
      );
      assertThat(calls).hasSize(2);
      assertThat(calls.get(0).name()).isEqualTo("a");
      assertThat(calls.get(1).name()).isEqualTo("b");
    }
  }

  @Nested
  class GemmaCall {

    private static final String OBSERVED =
      ">\n<|channel>thought\n<channel|><|tool_call>call:send_email{body:<|\"|>Hi Jamie, just a " +
      "friendly reminder that you need to finish the quarterly report by tomorrow.<|\"|>,to:" +
      "<|\"|>jamie@acme.com<|\"|>}<tool_call|>";

    @Test
    void parsesObservedGemmaBlock() {
      var calls = extract(OBSERVED);
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("send_email");
      JsonNode args = argsOf(calls.get(0));
      assertThat(args.size()).isEqualTo(2);
      assertThat(args.get("body").asText()).isEqualTo(
        "Hi Jamie, just a friendly reminder that you need to finish the quarterly report by tomorrow."
      );
      assertThat(args.get("to").asText()).isEqualTo("jamie@acme.com");
      assertThat(calls.get(0).coercibleArgs()).isEmpty();
    }

    @Test
    void stringValuesMayContainCommasBracesAndNewlines() {
      var calls = extract(
        "<|tool_call>call:send_email{body:<|\"|>Line one, with {braces} and [brackets].\n" +
          "Line two: still the same value.<|\"|>,to:<|\"|>a@b.c<|\"|>}<tool_call|>"
      );
      assertThat(calls).hasSize(1);
      JsonNode args = argsOf(calls.get(0));
      assertThat(args.get("body").asText()).isEqualTo(
        "Line one, with {braces} and [brackets].\nLine two: still the same value."
      );
      assertThat(args.get("to").asText()).isEqualTo("a@b.c");
    }

    @Test
    void bareScalarsStayStringsButAreFlaggedCoercible() {
      var calls = extract(
        "<|tool_call>call:read{filePath:<|\"|>/tmp/a.txt<|\"|>,limit:80,recursive:true," +
          "threshold:0.5}<tool_call|>"
      );
      assertThat(calls).hasSize(1);
      JsonNode args = argsOf(calls.get(0));
      assertThat(args.get("filePath").asText()).isEqualTo("/tmp/a.txt");
      assertThat(args.get("limit").asText()).isEqualTo("80");
      assertThat(args.get("recursive").asText()).isEqualTo("true");
      assertThat(args.get("threshold").asText()).isEqualTo("0.5");
      assertThat(calls.get(0).coercibleArgs()).containsExactly("limit", "recursive", "threshold");
    }

    @Test
    void arrayValueWithDelimitedStringsNormalizesToJsonText() {
      var calls = extract("<|tool_call>call:tag{tags:[<|\"|>a,b<|\"|>,<|\"|>c<|\"|>]}<tool_call|>");
      assertThat(calls).hasSize(1);
      JsonNode args = argsOf(calls.get(0));
      assertThat(args.get("tags").asText()).isEqualTo("[\"a,b\",\"c\"]");
      assertThat(calls.get(0).coercibleArgs()).containsExactly("tags");
    }

    @Test
    void multipleGemmaBlocksYieldMultipleCalls() {
      var calls = extract(
        "<|tool_call>call:first{a:<|\"|>1<|\"|>}<tool_call|>\n" +
          "<|tool_call>call:second{b:<|\"|>2<|\"|>}<tool_call|>"
      );
      assertThat(calls).hasSize(2);
      assertThat(calls.get(0).name()).isEqualTo("first");
      assertThat(calls.get(1).name()).isEqualTo("second");
    }

    @Test
    void escapedKeysAreAccepted() {
      var calls = extract(
        "<|tool_call>call:send_email{<|\"|>to<|\"|>:<|\"|>a@b.c<|\"|>}<tool_call|>"
      );
      assertThat(calls).hasSize(1);
      assertThat(argsOf(calls.get(0)).get("to").asText()).isEqualTo("a@b.c");
    }

    @Test
    void emptyArgumentsParseToEmptyObject() {
      var calls = extract("<|tool_call>call:ping{}<tool_call|>");
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("ping");
      assertThat(calls.get(0).argumentsJson()).isEqualTo("{}");
    }

    @Test
    void malformedBlockWithoutCallPrefixIsIgnored() {
      assertThat(extract("some text <|tool_call>not a call<tool_call|> more text")).isEmpty();
    }

    @Test
    void bareGemmaBodyParses() {
      var calls = extract("call:get_weather{city:<|\"|>Paris, France<|\"|>,days:3}");
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("get_weather");
      JsonNode args = argsOf(calls.get(0));
      assertThat(args.get("city").asText()).isEqualTo("Paris, France");
      assertThat(args.get("days").asText()).isEqualTo("3");
      assertThat(calls.get(0).coercibleArgs()).containsExactly("days");
    }

    @Test
    void multipleBareGemmaBodiesParse() {
      var calls = extract("call:a{x:1}call:b{y:2}");
      assertThat(calls).hasSize(2);
      assertThat(calls.get(0).name()).isEqualTo("a");
      assertThat(calls.get(1).name()).isEqualTo("b");
    }
  }

  @Nested
  class Config {

    @Test
    void namedBuiltinRestrictsToThatDialect() {
      String gemma = "<|tool_call>call:ping{}<tool_call|>";
      assertThat(ToolCallExtractor.extract(gemma, List.of(), "gemma-call")).hasSize(1);
      assertThat(ToolCallExtractor.extract(gemma, List.of(), "chatml-json")).isEmpty();
    }

    @Test
    void inlineCustomTemplateExtractsNovelDialect() {
      // Made-up dialect: TOOL name(arg=value)
      String template = """
        [{%- for m in output | regex_findall('TOOL (\\\\w+)\\\\((\\\\w+)=(\\\\w+)\\\\)') -%}
        {"name": {{ m[0] | tojson }}, "arguments": { {{ m[1] | tojson }}: {{ m[2] | tojson }} }}\
        {{ "," if not loop.last }}
        {%- endfor -%}]
        """;
      var calls = ToolCallExtractor.extract("TOOL ping(host=localhost)", List.of(), template);
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("ping");
      assertThat(argsOf(calls.get(0)).get("host").asText()).isEqualTo("localhost");
    }

    @Test
    void brokenTemplateFailsOpen() {
      assertThat(
        ToolCallExtractor.extract("anything", List.of(), "{% for x in %}broken")
      ).isEmpty();
      assertThat(
        ToolCallExtractor.extract("anything", List.of(), "this renders no json")
      ).isEmpty();
    }

    @Test
    void truncatedBareSpanIsBalancedAndRecovered() {
      // Small models routinely drop the last closing brace(s). The bare
      // TOOL-channel span (engine-classified, markers stripped) is what
      // extraction actually receives on the modern path.
      String truncated =
        "{\"name\": \"set_todos\", \"arguments\": {\"todos\": [{\"id\": \"1\", \"title\": \"a\"}]}";
      var calls = ToolCallExtractor.extract(truncated, List.of(), "chatml-json");
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("set_todos");
    }

    @Test
    void balanceJsonClosesBracesOutsideStrings() {
      assertThat(ToolCallExtractor.balanceJson("{\"a\": [1, 2")).isEqualTo("{\"a\": [1, 2]}");
      assertThat(ToolCallExtractor.balanceJson("{\"a\": \"} ] {\"")).isEqualTo(
        "{\"a\": \"} ] {\"}"
      );
      assertThat(ToolCallExtractor.balanceJson("{\"done\": true}")).isEqualTo("{\"done\": true}");
    }

    @Test
    void extractResultSurfacesFailureCause() {
      var broken = ToolCallExtractor.extractResult("anything", List.of(), "{% for x in %}broken");
      assertThat(broken.calls()).isEmpty();
      assertThat(broken.error()).isNotBlank();

      var notJson = ToolCallExtractor.extractResult("anything", List.of(), "this renders no json");
      assertThat(notJson.calls()).isEmpty();
      assertThat(notJson.error()).isNotBlank();

      // No recognizable call is not an error — plain fail-open.
      var noCall = ToolCallExtractor.extractResult("plain prose", List.of(), null);
      assertThat(noCall.calls()).isEmpty();
      assertThat(noCall.error()).isNull();
    }

    @Test
    void toolsVariableIsAvailableToTemplates() {
      String template = """
        [{% for t in tools %}{"name": {{ t.name | tojson }}, "arguments": {}}\
        {{ "," if not loop.last }}{% endfor %}]
        """;
      var calls = ToolCallExtractor.extract("x", List.of(java.util.Map.of("name", "f")), template);
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("f");
    }
  }

  @Nested
  class GlmNameJson {

    private static final List<java.util.Map<String, Object>> TOOLS = List.of(
      java.util.Map.of("name", "send_email", "description", "Send an email"),
      java.util.Map.of("name", "get_weather", "description", "Weather lookup")
    );

    private static List<ExtractedToolCall> glm(String output) {
      return ToolCallExtractor.extract(output, TOOLS, "glm-name-json");
    }

    @Test
    void nameThenJsonObjectParsesAsSingleCall() {
      var calls = glm("send_email\n{\"to\":\"a@b.com\"}");
      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().name()).isEqualTo("send_email");
      assertThat(argsOf(calls.getFirst()).get("to").asText()).isEqualTo("a@b.com");
      assertThat(calls.getFirst().coercibleArgs()).isEmpty();
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
      var calls = glm("\nget_weather \n {\"city\": \"Paris\", \"unit\": \"c\"} \n");
      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().name()).isEqualTo("get_weather");
      assertThat(argsOf(calls.getFirst()).get("city").asText()).isEqualTo("Paris");
    }

    @Test
    void plainProseAnswerYieldsNoCalls() {
      assertThat(glm("Sure — I sent the email to a@b.com for you.")).isEmpty();
      assertThat(glm("Here is what I found:\nParis is sunny today.")).isEmpty();
    }

    @Test
    void firstLineNotAToolNameYieldsNoCalls() {
      assertThat(glm("delete_everything\n{\"target\":\"/\"}")).isEmpty();
    }

    @Test
    void invalidJsonRemainderFailsOpen() {
      assertThat(glm("send_email\n{not valid json")).isEmpty();
      assertThat(glm("send_email\nplease confirm the address")).isEmpty();
    }

    @Test
    void nameWithoutBodyYieldsNoCalls() {
      assertThat(glm("send_email")).isEmpty();
      assertThat(glm("")).isEmpty();
    }

    @Test
    void aCallAfterAnInlineTurnMarkerIsFound() {
      // Observed: the model explains itself, emits its own turn marker as text, then calls.
      // Keeping the text BEFORE the marker drops the call and the turn ends as a plain answer
      // — indistinguishable, downstream, from the model having chosen not to call anything.
      var calls = glm("Let's send it:<|assistant|>send_email\n{\"to\":\"a@b.com\"}");
      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().name()).isEqualTo("send_email");
      assertThat(argsOf(calls.getFirst()).get("to").asText()).isEqualTo("a@b.com");
    }

    @Test
    void rolePlayedOutputAfterTheCallIsStillCut() {
      var calls = glm("send_email\n{\"to\":\"a@b.com\"}<|observation|>\nEmail sent successfully!");
      assertThat(calls).hasSize(1);
      assertThat(argsOf(calls.getFirst()).get("to").asText()).isEqualTo("a@b.com");
    }

    @Test
    void proseBeforeAnInlineMarkerIsNotItselfACall() {
      assertThat(glm("Here is the plan:<|assistant|>Paris is sunny today.")).isEmpty();
    }
  }
}
