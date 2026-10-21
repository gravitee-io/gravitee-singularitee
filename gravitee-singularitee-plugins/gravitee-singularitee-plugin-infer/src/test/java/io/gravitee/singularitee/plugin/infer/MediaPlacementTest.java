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
package io.gravitee.singularitee.plugin.infer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.MediaAttachment;
import io.gravitee.singularitee.engine.api.MediaAttachmentType;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.template.DelegatingChatTemplateRenderer;
import io.gravitee.singularitee.engine.template.JinjaTemplateRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A caller's image or audio reaches a pipeline model with one placeholder per attachment. */
class MediaPlacementTest {

  private static final String MARKER = "<__media__>";
  private static final String TEMPLATE =
    "{% for m in messages %}<{{ m.role }}>{{ m.content }}\n{% endfor %}";
  private static final MediaAttachment IMAGE = new MediaAttachment(
    MediaAttachmentType.IMAGE_PNG,
    "aGVsbG8="
  );

  private final PromptAssembler assembler = new PromptAssembler(
    new JinjaTemplateRenderer(),
    new DelegatingChatTemplateRenderer(new JinjaTemplateRenderer())
  );

  private static TextGenEngine engine(String marker) {
    var engine = mock(TextGenEngine.class);
    when(engine.chatTemplateString()).thenReturn(TEMPLATE);
    when(engine.mediaMarker()).thenReturn(marker);
    return engine;
  }

  private static PipelineContext withImage() {
    return new PipelineContext(
      "what colour?",
      List.of(
        new ChatTurn(ChatRole.SYSTEM, "be brief"),
        new ChatTurn(ChatRole.USER, "what colour?", List.of(IMAGE))
      ),
      null,
      List.of(),
      null
    );
  }

  @Test
  void passthrough_places_one_marker_in_the_turn_that_carries_the_image() {
    var cfg = InferStepConfig.builder().modelId("vision").build();

    var prompt = assembler.assemble("agent", cfg, engine(MARKER), withImage(), 32);

    assertThat(prompt.renderedPrompt()).isEqualTo(
      "<system>be brief\n<user>" + MARKER + "\nwhat colour?\n"
    );
    assertThat(prompt.wireMessages().get(1).media()).containsExactly(IMAGE);
  }

  @Test
  void an_engine_without_a_marker_renders_text_only() {
    var cfg = InferStepConfig.builder().modelId("vision").build();

    var prompt = assembler.assemble("agent", cfg, engine(null), withImage(), 32);

    assertThat(prompt.renderedPrompt()).doesNotContain(MARKER).contains("<user>what colour?");
  }

  @Test
  void step_messages_carry_the_image_in_their_last_user_message() {
    var cfg = InferStepConfig.builder()
      .modelId("vision")
      .message("system", "You describe images.")
      .message("user", "Describe: {{ prompt }}")
      .build();

    var prompt = assembler.assemble("agent", cfg, engine(MARKER), withImage(), 32);

    assertThat(prompt.renderedPrompt()).isEqualTo(
      "<system>You describe images.\n<user>" + MARKER + "\nDescribe: what colour?\n"
    );
    assertThat(prompt.wireMessages().get(1).media()).containsExactly(IMAGE);
  }

  @Test
  void a_raw_template_without_a_placeholder_is_refused() {
    var cfg = InferStepConfig.builder().modelId("vision").rawTemplate("Q: {{ prompt }}").build();

    assertThatThrownBy(() -> assembler.assemble("agent", cfg, engine(MARKER), withImage(), 32))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage(
        "Step 'agent' cannot place the attached media: 1 attachment(s), 0 placeholder(s) in its prompt"
      );
  }

  @Test
  void text_only_request_is_unaffected_by_step_messages() {
    var cfg = InferStepConfig.builder().modelId("vision").message("user", "{{ prompt }}").build();
    var pctx = new PipelineContext(
      "hi",
      List.of(new ChatTurn(ChatRole.USER, "hi")),
      null,
      List.of(),
      null
    );

    var prompt = assembler.assemble("agent", cfg, engine(MARKER), pctx, 32);

    assertThat(prompt.renderedPrompt()).isEqualTo("<user>hi\n");
  }
}
