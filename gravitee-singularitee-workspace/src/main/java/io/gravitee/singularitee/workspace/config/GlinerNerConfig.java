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
 * GLiNER4j zero-shot NER configuration.
 *
 * <p>Plain configuration, never on the wire — converted from the former proto
 * message with identical semantics (zero / empty = default).
 */
public record GlinerNerConfig(
  String modelDir,
  java.util.List<GlinerEntityDef> entities,
  float threshold,
  String variant,
  int tokenCap
) {
  private static final GlinerNerConfig DEFAULT = newBuilder().build();

  public GlinerNerConfig {
    modelDir = modelDir == null ? "" : modelDir;
    variant = variant == null ? "" : variant;
    entities = entities == null ? java.util.List.of() : java.util.List.copyOf(entities);
  }

  /** All defaults — what an absent YAML block means. */
  public static GlinerNerConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Copy with {@code modelDir} replaced — resolvers rewrite paths after download. */
  public GlinerNerConfig withModelDir(String v) {
    return new GlinerNerConfig(v, entities, threshold, variant, tokenCap);
  }

  public static final class Builder {

    private String modelDir = "";
    private final java.util.List<GlinerEntityDef> entities = new java.util.ArrayList<>();
    private float threshold;
    private String variant = "";
    private int tokenCap;

    public Builder setModelDir(String v) {
      this.modelDir = v;
      return this;
    }

    public Builder addEntities(GlinerEntityDef v) {
      this.entities.add(v);
      return this;
    }

    public Builder addAllEntities(java.util.Collection<GlinerEntityDef> v) {
      this.entities.addAll(v);
      return this;
    }

    public Builder setThreshold(float v) {
      this.threshold = v;
      return this;
    }

    public Builder setVariant(String v) {
      this.variant = v;
      return this;
    }

    public Builder setTokenCap(int v) {
      this.tokenCap = v;
      return this;
    }

    public GlinerNerConfig build() {
      return new GlinerNerConfig(modelDir, entities, threshold, variant, tokenCap);
    }
  }
}
