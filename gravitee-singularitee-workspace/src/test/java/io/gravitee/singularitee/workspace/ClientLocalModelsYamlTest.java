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
package io.gravitee.singularitee.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientLocalModelsYamlTest {

  @Test
  void parses_regex_and_composite_models(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");

    String yaml = """
      workspace:
        name: test
        models:
          - id: pii-patterns
            type: regex
            regex:
              patterns:
                - pattern: '\\b\\d{3}-\\d{2}-\\d{4}\\b'
                  entity_type: SSN
          - id: profanity
            type: regex
            regex:
              patterns:
                - pattern: '\\bkill\\b'
                  entity_type: PROFANITY
          - id: content-guardrail
            type: composite_classifier
            composite_classifier:
              models:
                - pii-patterns
                - profanity
        pipelines: []
      """;
    Files.writeString(workspace, yaml);

    var result = YamlWorkspaceLoader.load(workspace);

    // No remote or local-gpu models were declared
    assertThat(result.models()).isEmpty();
    assertThat(result.remoteModels()).isEmpty();

    // Three client-local models were parsed, in declaration order
    assertThat(result.clientLocalModels()).hasSize(3);

    var regex = result.clientLocalModels().get(0);
    assertThat(regex.definition().id()).isEqualTo("pii-patterns");
    assertThat(regex.definition().type()).isEqualTo("regex");
    assertThat(regex.definition().regex()).isNotNull();
    assertThat(regex.definition().regex().patterns()).hasSize(1);
    assertThat(regex.definition().regex().patterns().get(0).entityType()).isEqualTo("SSN");

    var profanity = result.clientLocalModels().get(1);
    assertThat(profanity.definition().id()).isEqualTo("profanity");
    assertThat(profanity.definition().type()).isEqualTo("regex");

    var composite = result.clientLocalModels().get(2);
    assertThat(composite.definition().id()).isEqualTo("content-guardrail");
    assertThat(composite.definition().type()).isEqualTo("composite_classifier");
    assertThat(composite.definition().compositeClassifier()).isNotNull();
    assertThat(composite.definition().compositeClassifier().models()).containsExactly(
      "pii-patterns",
      "profanity"
    );
  }
}
