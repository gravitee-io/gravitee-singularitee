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
package io.gravitee.singularitee.workspace.config;

/**
 * GLiNER4j decision model configuration: a GLiNER2 bundle answering typed questions.
 *
 * <p>Plain configuration, never on the wire. Empty strings mean "engine default".
 *
 * @param modelDir   the bundle directory; empty, it is downloaded from the model name
 * @param variant    the ONNX variant folder, or the GGUF quantisation of a llama.cpp bundle
 * @param classifier the question asked when the model is used as a classifier, or {@code null}
 */
public record GlinerDecisionConfig(
  String modelDir,
  String variant,
  DecisionClassifierQuestion classifier
) {
  public GlinerDecisionConfig {
    modelDir = modelDir == null ? "" : modelDir;
    variant = variant == null ? "" : variant;
  }

  /** A copy pointing at {@code modelDir}, once the bundle is resolved. */
  public GlinerDecisionConfig withModelDir(String modelDir) {
    return new GlinerDecisionConfig(modelDir, variant, classifier);
  }
}
