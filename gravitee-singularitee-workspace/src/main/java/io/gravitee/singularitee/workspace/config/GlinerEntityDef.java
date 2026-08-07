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
 * A single entity type definition for GLiNER4j NER.
 *
 * <p>Plain configuration, never on the wire — converted from the former proto
 * message with identical semantics (zero / empty = default).
 */
public record GlinerEntityDef(String name, String description) {
  private static final GlinerEntityDef DEFAULT = newBuilder().build();

  public GlinerEntityDef {
    name = name == null ? "" : name;
    description = description == null ? "" : description;
  }

  /** All defaults — what an absent YAML block means. */
  public static GlinerEntityDef getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

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

    public GlinerEntityDef build() {
      return new GlinerEntityDef(name, description);
    }
  }
}
