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

import io.gravitee.singularitee.protocol.TagConfig;
import org.junit.jupiter.api.Test;

/**
 * Residue detection across every tool dialect the engine ships an extraction
 * template for: leaked dialect machinery in a final answer is flagged, plain
 * prose never is, and each dialect only reacts to ITS OWN markers.
 */
class ToolMarkerResidueTest {

  private static final String PROSE = "The Game of Life needs a grid, rules and a GUI loop.";

  private static TagConfig tags(String open, String close) {
    return TagConfig.newBuilder().setOpenTag(open).setCloseTag(close).build();
  }

  // ── harmony (gpt-oss) ─────────────────────────────────────────────────────

  private static final TagConfig HARMONY_TAGS = TagConfig.newBuilder()
    .setOpenTag("<|end|><|start|>assistant<|channel|>commentary to=functions.")
    .setCloseTag("<|call|>")
    .addOpenTagAlternatives("<|channel|>commentary to=functions.")
    .build();

  @Test
  void harmony_flags_hallucinated_channel_forms_and_routing_fragments() {
    var harmony = ToolMarkerResidues.forTemplate("harmony");

    // Observed live: function name invented AS the channel, no legal header.
    String hallucinated =
      "<|channel|>functions.set_todos<|channel|>commentary json<|message|>{\"todos\":[]}";
    assertThat(harmony.isPresent(hallucinated, HARMONY_TAGS)).isTrue();
    // Observed live: tool call opened on the final channel.
    String finalChannel =
      "<|start|>assistant<|channel|>final to=functions.ask_user<|message|>{\"question\":\"?\"}";
    assertThat(harmony.isPresent(finalChannel, HARMONY_TAGS)).isTrue();
    // A free-floating routing prefix without any full tag.
    assertThat(
      harmony.isPresent("ok to=functions.bash {\"command\":\"ls\"}", HARMONY_TAGS)
    ).isTrue();

    assertThat(harmony.isPresent(PROSE, HARMONY_TAGS)).isFalse();
  }

  // ── chatml-json (Qwen3) ───────────────────────────────────────────────────

  private static final TagConfig CHATML_TAGS = tags("<tool_call>", "</tool_call>");

  @Test
  void chatml_flags_its_leaked_tags_only() {
    var chatml = ToolMarkerResidues.forTemplate("chatml-json");

    assertThat(
      chatml.isPresent("Sure!\n<tool_call>{\"name\":\"bash\",\"arguments\":{}}", CHATML_TAGS)
    ).isTrue();
    // An unclosed leak with only the close tag surviving still counts.
    assertThat(chatml.isPresent("{\"name\":\"bash\"}</tool_call>", CHATML_TAGS)).isTrue();

    assertThat(chatml.isPresent(PROSE, CHATML_TAGS)).isFalse();
    // Harmony debris means nothing to a chatml step.
    assertThat(chatml.isPresent("<|channel|>functions.bash<|message|>{}", CHATML_TAGS)).isFalse();
  }

  // ── xml-function (Qwen3.5) ────────────────────────────────────────────────

  private static final TagConfig XML_TAGS = tags("<function=", "</function>");

  @Test
  void xml_function_flags_its_leaked_tags_only() {
    var xml = ToolMarkerResidues.forTemplate("xml-function");

    assertThat(
      xml.isPresent("<function=bash><parameter=command>ls</parameter>", XML_TAGS)
    ).isTrue();
    assertThat(xml.isPresent(PROSE, XML_TAGS)).isFalse();
    assertThat(xml.isPresent("<tool_call>{\"name\":\"bash\"}", XML_TAGS)).isFalse();
  }

  // ── gemma-call ────────────────────────────────────────────────────────────

  private static final TagConfig GEMMA_TAGS = tags("<|tool_call>", "<tool_call|>");

  @Test
  void gemma_flags_its_leaked_tags_only() {
    var gemma = ToolMarkerResidues.forTemplate("gemma-call");

    assertThat(gemma.isPresent("<|tool_call>call:bash{command:ls}", GEMMA_TAGS)).isTrue();
    assertThat(gemma.isPresent(PROSE, GEMMA_TAGS)).isFalse();
    assertThat(gemma.isPresent("<function=bash>", GEMMA_TAGS)).isFalse();
  }

  // ── glm-name-json (marker-less) ───────────────────────────────────────────

  @Test
  void markerless_dialects_never_flag_anything() {
    var glm = ToolMarkerResidues.forTemplate("glm-name-json");

    // No tags configured — there is no machinery to leak; the whole message
    // IS the call format, so nothing here can be residue.
    TagConfig none = TagConfig.getDefaultInstance();
    assertThat(glm.isPresent("bash\n{\"command\":\"ls\"}", none)).isFalse();
    assertThat(glm.isPresent(PROSE, none)).isFalse();
  }

  // ── resolution ────────────────────────────────────────────────────────────

  @Test
  void unknown_and_custom_templates_get_the_verbatim_tag_baseline() {
    var custom = ToolMarkerResidues.forTemplate("my-custom-template");
    assertThat(custom).isInstanceOf(DefaultToolMarkerResidue.class);
    // Baseline still flags a leaked configured tag…
    assertThat(custom.isPresent("oops <tool_call>{}", CHATML_TAGS)).isTrue();
    // …but derives nothing beyond it (no harmony-style refinement).
    assertThat(custom.isPresent("ok to=functions.bash {}", HARMONY_TAGS)).isFalse();

    assertThat(ToolMarkerResidues.forTemplate(null)).isInstanceOf(DefaultToolMarkerResidue.class);
    assertThat(ToolMarkerResidues.forTemplate("harmony")).isInstanceOf(
      HarmonyToolMarkerResidue.class
    );
  }
}
