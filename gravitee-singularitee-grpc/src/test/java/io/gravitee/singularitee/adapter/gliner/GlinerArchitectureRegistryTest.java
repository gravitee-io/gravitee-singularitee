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
package io.gravitee.singularitee.adapter.gliner;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.lab.gliner4j.arch.Architecture;
import io.gravitee.lab.gliner4j.arch.Engine;
import io.gravitee.lab.gliner4j.arch.ModelArchitectures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every GLiNER family the distribution serves resolves on the engines it ships. No bundle needed. */
class GlinerArchitectureRegistryTest {

  @ParameterizedTest
  @EnumSource(
    value = Architecture.class,
    names = { "GLINER2", "GLINER2DOT5", "GLINER_UNI", "GLINER_BI", "GLICLASS" }
  )
  void onnxFamilyIsRegistered(Architecture family) {
    assertThat(ModelArchitectures.forId(family, Engine.ONNX).id()).isEqualTo(family);
  }

  @ParameterizedTest
  @EnumSource(
    value = Architecture.class,
    names = {
      "GLINER2",
      "GLINER2DOT5",
      "GLINER_UNI",
      "GLINER_BI",
      "GLICLASS",
      "GLICLASS_DECODER_KV",
      "GLINER_STREAMING_SPAN",
    }
  )
  void llamacppFamilyIsRegistered(Architecture family) {
    var arch = ModelArchitectures.forId(family, Engine.LLAMACPP);
    assertThat(arch.id()).isEqualTo(family);
    assertThat(arch.engine()).isEqualTo(Engine.LLAMACPP);
  }

  @Test
  void llamacppOnlyFamiliesDefaultToLlamacpp() {
    assertThat(Architecture.GLICLASS_DECODER_KV.defaultEngine()).isEqualTo(Engine.LLAMACPP);
    assertThat(Architecture.GLINER_STREAMING_SPAN.defaultEngine()).isEqualTo(Engine.LLAMACPP);
  }
}
