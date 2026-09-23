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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mapping a {@link StructuredOutput} onto vLLM guided decoding. */
class GuidedDecodingMappingTest {

  @Test
  void everyFormatVllmEnforcesNativelyMaps() {
    for (StructuredOutput format : List.of(
      new StructuredOutput.JsonSchema("{\"type\":\"object\"}"),
      new StructuredOutput.JsonObject(),
      new StructuredOutput.Choice(List.of("yes", "no")),
      new StructuredOutput.Regex("[0-9]+"),
      new StructuredOutput.Grammar("root ::= \"a\"\n", StructuredOutput.DEFAULT_ROOT)
    )) {
      assertThat(EngineAdapter.guidedDecoding(format)).isNotNull();
    }
  }

  @Test
  void aGrammarStartingAnywhereButRootIsRefused() {
    var grammar = new StructuredOutput.Grammar("start ::= \"a\"\n", "start");

    assertThatThrownBy(() -> EngineAdapter.guidedDecoding(grammar))
      .isInstanceOf(UnsupportedStructuredOutputException.class)
      .hasMessageContaining("start rule to be named `root`")
      .hasMessageContaining("`start`");
  }

  @Test
  void theDefaultRootIsAccepted() {
    var grammar = new StructuredOutput.Grammar("root ::= \"a\"\n", StructuredOutput.DEFAULT_ROOT);

    assertThatCode(() -> EngineAdapter.guidedDecoding(grammar)).doesNotThrowAnyException();
  }
}
