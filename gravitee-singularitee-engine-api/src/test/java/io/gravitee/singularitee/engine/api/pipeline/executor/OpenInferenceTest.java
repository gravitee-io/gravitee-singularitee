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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import org.junit.jupiter.api.Test;

/** The step-type -> OpenInference span-kind mapping. */
class OpenInferenceTest {

  @Test
  void infer_is_an_llm_span() {
    assertThat(OpenInference.spanKind(StepTypes.INFER)).isEqualTo(OpenInference.KIND_LLM);
  }

  @Test
  void embed_is_an_embedding_span() {
    assertThat(OpenInference.spanKind(StepTypes.EMBED)).isEqualTo(OpenInference.KIND_EMBEDDING);
  }

  @Test
  void the_guards_are_guardrail_spans() {
    assertThat(OpenInference.spanKind(StepTypes.GUARD)).isEqualTo(OpenInference.KIND_GUARDRAIL);
    assertThat(OpenInference.spanKind(StepTypes.LLM_GUARD)).isEqualTo(OpenInference.KIND_GUARDRAIL);
    assertThat(OpenInference.spanKind(StepTypes.REGEX_GUARD)).isEqualTo(
      OpenInference.KIND_GUARDRAIL
    );
  }

  @Test
  void tool_steps_are_tool_spans() {
    assertThat(OpenInference.spanKind(StepTypes.TOOL_SELECT)).isEqualTo(OpenInference.KIND_TOOL);
    assertThat(OpenInference.spanKind(StepTypes.TODO)).isEqualTo(OpenInference.KIND_TOOL);
  }

  @Test
  void orchestration_and_unknown_default_to_chain() {
    assertThat(OpenInference.spanKind(StepTypes.ROUTE)).isEqualTo(OpenInference.KIND_CHAIN);
    assertThat(OpenInference.spanKind(StepTypes.CLASSIFY)).isEqualTo(OpenInference.KIND_CHAIN);
    assertThat(OpenInference.spanKind(StepTypes.LOOP)).isEqualTo(OpenInference.KIND_CHAIN);
    assertThat(OpenInference.spanKind("some_plugin_step")).isEqualTo(OpenInference.KIND_CHAIN);
    assertThat(OpenInference.spanKind(null)).isEqualTo(OpenInference.KIND_CHAIN);
  }

  @Test
  void message_index_helpers_build_the_openinference_keys() {
    assertThat(OpenInference.inputMessageContent(2)).isEqualTo(
      "llm.input_messages.2.message.content"
    );
    assertThat(OpenInference.outputToolCallName(0, 1)).isEqualTo(
      "llm.output_messages.0.message.tool_calls.1.tool_call.function.name"
    );
  }
}
