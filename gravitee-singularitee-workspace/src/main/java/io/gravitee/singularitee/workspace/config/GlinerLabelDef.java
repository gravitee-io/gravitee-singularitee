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
 * A single label definition for GLiNER4j zero-shot classification.
 *
 * <p>Plain configuration, never on the wire. Numeric zero and empty string mean
 * "engine default".
 */
public record GlinerLabelDef(String name, String description) {
  private static final GlinerLabelDef DEFAULT = newBuilder().build();

  public GlinerLabelDef {
    name = name == null ? "" : name;
    description = description == null ? "" : description;
  }

  /** All defaults: what an absent YAML block means. */
  public static GlinerLabelDef getDefaultInstance() {
    return DEFAULT;
  }

  /** Returns a builder with every field at its default. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Fluent builder; unset fields keep the engine defaults. */
  public static final class Builder {

    private String name = "";
    private String description = "";

    public Builder setName(String v) {
      this.name = v;
      return this;
    }

    public Builder setDescription(String v) {
      this.description = v;
      return this;
    }

    public GlinerLabelDef build() {
      return new GlinerLabelDef(name, description);
    }
  }
}
