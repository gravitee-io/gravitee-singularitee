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
package io.gravitee.singularitee.e2e.spans;

import java.util.List;

/** Span contract of {@code gliner_ner} on ONNX Runtime. */
class GlinerOnnxSpanOffsetsE2ETest extends SpanOffsetsContract {

  @Override
  String backend() {
    return "gliner-onnx";
  }

  @Override
  String workspace() {
    return "spans-gliner-onnx.yaml";
  }

  @Override
  List<String> repositories() {
    return List.of("gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi");
  }
}
