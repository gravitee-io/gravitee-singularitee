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
 * Draft-model speculative decoding parameters.
 *
 * <p>Plain configuration, never on the wire. Numeric zero means "engine default".
 */
public record SpeculativeConfig(
  int nDraft,
  int draftMin,
  float pMin,
  float temperature,
  int topK,
  float topP,
  long seed
) {
  /** Returns a builder with every field at its default. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Mirrors the record components; unset fields keep the engine defaults. */
  /** Fluent builder; unset fields keep the engine defaults. */
  public static final class Builder {

    private int nDraft;
    private int draftMin;
    private float pMin;
    private float temperature;
    private int topK;
    private float topP;
    private long seed;

    public Builder setNDraft(int v) {
      this.nDraft = v;
      return this;
    }

    public Builder setDraftMin(int v) {
      this.draftMin = v;
      return this;
    }

    public Builder setPMin(float v) {
      this.pMin = v;
      return this;
    }

    public Builder setTemperature(float v) {
      this.temperature = v;
      return this;
    }

    public Builder setTopK(int v) {
      this.topK = v;
      return this;
    }

    public Builder setTopP(float v) {
      this.topP = v;
      return this;
    }

    public Builder setSeed(long v) {
      this.seed = v;
      return this;
    }

    public SpeculativeConfig build() {
      return new SpeculativeConfig(nDraft, draftMin, pMin, temperature, topK, topP, seed);
    }
  }
}
