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
 * ONNX BERT classifier configuration.
 *
 * <p>Plain configuration, never on the wire — converted from the former proto
 * message with identical semantics (zero / empty = default).
 */
public record OnnxClassifierConfig(
  String modelPath,
  String tokenizerPath,
  String configJsonPath,
  java.util.List<String> labels,
  int maxSequenceLength,
  String classifierMode
) {
  private static final OnnxClassifierConfig DEFAULT = newBuilder().build();

  public OnnxClassifierConfig {
    modelPath = modelPath == null ? "" : modelPath;
    tokenizerPath = tokenizerPath == null ? "" : tokenizerPath;
    configJsonPath = configJsonPath == null ? "" : configJsonPath;
    classifierMode = classifierMode == null ? "" : classifierMode;
    labels = labels == null ? java.util.List.of() : java.util.List.copyOf(labels);
  }

  /** All defaults — what an absent YAML block means. */
  public static OnnxClassifierConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Copy with {@code modelPath} replaced — resolvers rewrite paths after download. */
  public OnnxClassifierConfig withModelPath(String v) {
    return new OnnxClassifierConfig(
      v,
      tokenizerPath,
      configJsonPath,
      labels,
      maxSequenceLength,
      classifierMode
    );
  }

  /** Copy with {@code tokenizerPath} replaced — resolvers rewrite paths after download. */
  public OnnxClassifierConfig withTokenizerPath(String v) {
    return new OnnxClassifierConfig(
      modelPath,
      v,
      configJsonPath,
      labels,
      maxSequenceLength,
      classifierMode
    );
  }

  /** Copy with {@code configJsonPath} replaced — resolvers rewrite paths after download. */
  public OnnxClassifierConfig withConfigJsonPath(String v) {
    return new OnnxClassifierConfig(
      modelPath,
      tokenizerPath,
      v,
      labels,
      maxSequenceLength,
      classifierMode
    );
  }

  public static final class Builder {

    private String modelPath = "";
    private String tokenizerPath = "";
    private String configJsonPath = "";
    private final java.util.List<String> labels = new java.util.ArrayList<>();
    private int maxSequenceLength;
    private String classifierMode = "";

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

    public Builder addLabels(String v) {
      this.labels.add(v);
      return this;
    }

    public Builder addAllLabels(java.util.Collection<String> v) {
      this.labels.addAll(v);
      return this;
    }

    public Builder setMaxSequenceLength(int v) {
      this.maxSequenceLength = v;
      return this;
    }

    public Builder setClassifierMode(String v) {
      this.classifierMode = v;
      return this;
    }

    public OnnxClassifierConfig build() {
      return new OnnxClassifierConfig(
        modelPath,
        tokenizerPath,
        configJsonPath,
        labels,
        maxSequenceLength,
        classifierMode
      );
    }
  }
}
