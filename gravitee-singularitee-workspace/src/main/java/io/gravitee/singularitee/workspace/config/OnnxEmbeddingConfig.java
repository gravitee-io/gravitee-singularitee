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
 * ONNX embedding model configuration.
 *
 * <p>Plain configuration, never on the wire — converted from the former proto
 * message with identical semantics (zero / empty = default).
 */
public record OnnxEmbeddingConfig(
  String modelPath,
  String tokenizerPath,
  String configJsonPath,
  int maxSequenceLength,
  String poolingMode,
  boolean normalize
) {
  private static final OnnxEmbeddingConfig DEFAULT = newBuilder().build();

  public OnnxEmbeddingConfig {
    modelPath = modelPath == null ? "" : modelPath;
    tokenizerPath = tokenizerPath == null ? "" : tokenizerPath;
    configJsonPath = configJsonPath == null ? "" : configJsonPath;
    poolingMode = poolingMode == null ? "" : poolingMode;
  }

  /** All defaults — what an absent YAML block means. */
  public static OnnxEmbeddingConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Copy with {@code modelPath} replaced — resolvers rewrite paths after download. */
  public OnnxEmbeddingConfig withModelPath(String v) {
    return new OnnxEmbeddingConfig(
      v,
      tokenizerPath,
      configJsonPath,
      maxSequenceLength,
      poolingMode,
      normalize
    );
  }

  /** Copy with {@code tokenizerPath} replaced — resolvers rewrite paths after download. */
  public OnnxEmbeddingConfig withTokenizerPath(String v) {
    return new OnnxEmbeddingConfig(
      modelPath,
      v,
      configJsonPath,
      maxSequenceLength,
      poolingMode,
      normalize
    );
  }

  /** Copy with {@code configJsonPath} replaced — resolvers rewrite paths after download. */
  public OnnxEmbeddingConfig withConfigJsonPath(String v) {
    return new OnnxEmbeddingConfig(
      modelPath,
      tokenizerPath,
      v,
      maxSequenceLength,
      poolingMode,
      normalize
    );
  }

  public static final class Builder {

    private String modelPath = "";
    private String tokenizerPath = "";
    private String configJsonPath = "";
    private int maxSequenceLength;
    private String poolingMode = "";
    private boolean normalize;

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

    public Builder setPoolingMode(String v) {
      this.poolingMode = v;
      return this;
    }

    public Builder setNormalize(boolean v) {
      this.normalize = v;
      return this;
    }

    public OnnxEmbeddingConfig build() {
      return new OnnxEmbeddingConfig(
        modelPath,
        tokenizerPath,
        configJsonPath,
        maxSequenceLength,
        poolingMode,
        normalize
      );
    }
  }
}
