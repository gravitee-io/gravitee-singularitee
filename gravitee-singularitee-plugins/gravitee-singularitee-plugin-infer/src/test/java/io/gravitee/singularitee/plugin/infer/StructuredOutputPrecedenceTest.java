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

import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which step a caller's decoding constraint reaches. */
class StructuredOutputPrecedenceTest {

  private static final StructuredOutput REQUEST = new StructuredOutput.JsonObject();

  @Test
  void the_output_step_gets_the_request_constraint() {
    assertThat(
      TextGenRequestFactory.resolveStructuredOutput(StepRole.STEP_ROLE_OUTPUT, REQUEST)
    ).isEqualTo(REQUEST);
    assertThat(
      TextGenRequestFactory.resolveStructuredOutput(StepRole.STEP_ROLE_OUTPUT, null)
    ).isNull();
  }

  @Test
  void other_steps_never_inherit_it() {
    for (StepRole role : List.of(
      StepRole.STEP_ROLE_INTERNAL,
      StepRole.STEP_ROLE_THINKING,
      StepRole.STEP_ROLE_UNSPECIFIED
    )) {
      assertThat(TextGenRequestFactory.resolveStructuredOutput(role, REQUEST)).isNull();
    }
  }

  @Test
  void a_constrained_output_step_that_injects_tools_is_refused() {
    assertThat(
      TextGenRequestFactory.toolsConflict(REQUEST, true, List.of(Map.of("name", "f")), "generate")
    )
      .isInstanceOf(UnsupportedStructuredOutputException.class)
      .extracting(Throwable::getMessage)
      .isEqualTo("structured output cannot be combined with tools on step 'generate'");
  }

  @Test
  void the_combination_is_legal_when_either_half_is_absent() {
    List<Map<String, String>> tools = List.of(Map.of("name", "f"));

    // No constraint, tools not injected, and no tools to inject: each on its own is fine.
    assertThat(TextGenRequestFactory.toolsConflict(null, true, tools, "s")).isNull();
    assertThat(TextGenRequestFactory.toolsConflict(REQUEST, false, tools, "s")).isNull();
    assertThat(TextGenRequestFactory.toolsConflict(REQUEST, true, List.of(), "s")).isNull();
    assertThat(TextGenRequestFactory.toolsConflict(REQUEST, true, null, "s")).isNull();
  }
}
