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
 * llama.cpp-backed embedding model configuration.
 *
 * <p>Plain configuration, never on the wire — converted from the former proto
 * message with identical semantics (zero / empty = default).
 */
public record LlamaCppEmbeddingConfig(LlamaCppConfig llamaCppConfig, String embeddingTemplate) {
  private static final LlamaCppEmbeddingConfig DEFAULT = newBuilder().build();

  public LlamaCppEmbeddingConfig {
    embeddingTemplate = embeddingTemplate == null ? "" : embeddingTemplate;
  }

  /** All defaults — what an absent YAML block means. */
  public static LlamaCppEmbeddingConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {

    private LlamaCppConfig llamaCppConfig;
    private String embeddingTemplate = "";

    public Builder setLlamaCppConfig(LlamaCppConfig v) {
      this.llamaCppConfig = v;
      return this;
    }

    public Builder setEmbeddingTemplate(String v) {
      this.embeddingTemplate = v;
      return this;
    }

    public LlamaCppEmbeddingConfig build() {
      return new LlamaCppEmbeddingConfig(llamaCppConfig, embeddingTemplate);
    }
  }
}
