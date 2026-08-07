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
 * ONNX cross-encoder reranker configuration.
 *
 * <p>Plain configuration, never on the wire — converted from the former proto
 * message with identical semantics (zero / empty = default).
 */
public record OnnxRerankerConfig(
  String modelPath,
  String tokenizerPath,
  String configJsonPath,
  int maxSequenceLength,
  String scoring
) {
  private static final OnnxRerankerConfig DEFAULT = newBuilder().build();

  public OnnxRerankerConfig {
    modelPath = modelPath == null ? "" : modelPath;
    tokenizerPath = tokenizerPath == null ? "" : tokenizerPath;
    configJsonPath = configJsonPath == null ? "" : configJsonPath;
    scoring = scoring == null ? "" : scoring;
  }

  /** All defaults — what an absent YAML block means. */
  public static OnnxRerankerConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Copy with {@code modelPath} replaced — resolvers rewrite paths after download. */
  public OnnxRerankerConfig withModelPath(String v) {
    return new OnnxRerankerConfig(v, tokenizerPath, configJsonPath, maxSequenceLength, scoring);
  }

  /** Copy with {@code tokenizerPath} replaced — resolvers rewrite paths after download. */
  public OnnxRerankerConfig withTokenizerPath(String v) {
    return new OnnxRerankerConfig(modelPath, v, configJsonPath, maxSequenceLength, scoring);
  }

  /** Copy with {@code configJsonPath} replaced — resolvers rewrite paths after download. */
  public OnnxRerankerConfig withConfigJsonPath(String v) {
    return new OnnxRerankerConfig(modelPath, tokenizerPath, v, maxSequenceLength, scoring);
  }

  public static final class Builder {

    private String modelPath = "";
    private String tokenizerPath = "";
    private String configJsonPath = "";
    private int maxSequenceLength;
    private String scoring = "";

    public Builder setModelPath(String v) {
      this.modelPath = v;
      return this;
    }

    public Builder setTokenizerPath(String v) {
      this.tokenizerPath = v;
      return this;
    }

    public Builder setConfigJsonPath(String v) {
      this.configJsonPath = v;
      return this;
    }

    public Builder setMaxSequenceLength(int v) {
      this.maxSequenceLength = v;
      return this;
    }

    public Builder setScoring(String v) {
      this.scoring = v;
      return this;
    }

    public OnnxRerankerConfig build() {
      return new OnnxRerankerConfig(
        modelPath,
        tokenizerPath,
        configJsonPath,
        maxSequenceLength,
        scoring
      );
    }
  }
}
