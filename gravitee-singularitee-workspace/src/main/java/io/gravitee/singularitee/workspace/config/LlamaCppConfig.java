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
 * llama.cpp engine configuration, as a plain record.
 *
 * <p>Never on the wire. Semantics mirror the proto3 message this replaces:
 * numeric zero and empty string mean "engine default". The three-valued flags
 * ({@code offloadKqv}, {@code useMlock}, {@code promptCache}) are boxed —
 * {@code null} means "unset", which a proto3 plain bool could not say and which
 * is exactly why this stopped being proto.
 */
public record LlamaCppConfig(
  int nCtx,
  int nBatch,
  int nUbatch,
  int nSeqMax,
  int nGpuLayers,
  String poolingType,
  String attentionType,
  String flashAttnType,
  Boolean offloadKqv,
  String loraPath,
  String mmprojPath,
  String mediaMarker,
  boolean mtp,
  SpeculativeConfig speculative,
  Boolean useMlock,
  String cacheTypeK,
  String cacheTypeV,
  Boolean promptCache,
  int promptCacheMinTokens,
  float eogRampStart,
  float eogRampMaxBias,
  String draftModel,
  String draftPath,
  String eagle3Model,
  String eagle3Path
) {
  private static final LlamaCppConfig DEFAULT = newBuilder().build();

  public LlamaCppConfig {
    poolingType = poolingType == null ? "" : poolingType;
    attentionType = attentionType == null ? "" : attentionType;
    flashAttnType = flashAttnType == null ? "" : flashAttnType;
    loraPath = loraPath == null ? "" : loraPath;
    mmprojPath = mmprojPath == null ? "" : mmprojPath;
    mediaMarker = mediaMarker == null ? "" : mediaMarker;
    cacheTypeK = cacheTypeK == null ? "" : cacheTypeK;
    cacheTypeV = cacheTypeV == null ? "" : cacheTypeV;
    draftModel = draftModel == null ? "" : draftModel;
    draftPath = draftPath == null ? "" : draftPath;
    eagle3Model = eagle3Model == null ? "" : eagle3Model;
    eagle3Path = eagle3Path == null ? "" : eagle3Path;
  }

  /** All engine defaults — what an absent YAML block means. */
  public static LlamaCppConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {

    private int nCtx;
    private int nBatch;
    private int nUbatch;
    private int nSeqMax;
    private int nGpuLayers;
    private String poolingType = "";
    private String attentionType = "";
    private String flashAttnType = "";
    private Boolean offloadKqv;
    private String loraPath = "";
    private String mmprojPath = "";
    private String mediaMarker = "";
    private boolean mtp;
    private SpeculativeConfig speculative;
    private Boolean useMlock;
    private String cacheTypeK = "";
    private String cacheTypeV = "";
    private Boolean promptCache;
    private int promptCacheMinTokens;
    private float eogRampStart;
    private float eogRampMaxBias;
    private String draftModel = "";
    private String draftPath = "";
    private String eagle3Model = "";
    private String eagle3Path = "";

    public Builder setNCtx(int v) {
      this.nCtx = v;
      return this;
    }

    public Builder setNBatch(int v) {
      this.nBatch = v;
      return this;
    }

    public Builder setNUbatch(int v) {
      this.nUbatch = v;
      return this;
    }

    public Builder setNSeqMax(int v) {
      this.nSeqMax = v;
      return this;
    }

    public Builder setNGpuLayers(int v) {
      this.nGpuLayers = v;
      return this;
    }

    public Builder setPoolingType(String v) {
      this.poolingType = v;
      return this;
    }

    public Builder setAttentionType(String v) {
      this.attentionType = v;
      return this;
    }

    public Builder setFlashAttnType(String v) {
      this.flashAttnType = v;
      return this;
    }

    public Builder setOffloadKqv(boolean v) {
      this.offloadKqv = v;
      return this;
    }

    public Builder setLoraPath(String v) {
      this.loraPath = v;
      return this;
    }

    public Builder setMmprojPath(String v) {
      this.mmprojPath = v;
      return this;
    }

    public Builder setMediaMarker(String v) {
      this.mediaMarker = v;
      return this;
    }

    public Builder setMtp(boolean v) {
      this.mtp = v;
      return this;
    }

    public Builder setSpeculative(SpeculativeConfig v) {
      this.speculative = v;
      return this;
    }

    public Builder setSpeculative(SpeculativeConfig.Builder v) {
      this.speculative = v.build();
      return this;
    }

    public Builder setUseMlock(boolean v) {
      this.useMlock = v;
      return this;
    }

    public Builder setCacheTypeK(String v) {
      this.cacheTypeK = v;
      return this;
    }

    public Builder setCacheTypeV(String v) {
      this.cacheTypeV = v;
      return this;
    }

    public Builder setPromptCache(boolean v) {
      this.promptCache = v;
      return this;
    }

    public Builder setPromptCacheMinTokens(int v) {
      this.promptCacheMinTokens = v;
      return this;
    }

    public Builder setEogRampStart(float v) {
      this.eogRampStart = v;
      return this;
    }

    public Builder setEogRampMaxBias(float v) {
      this.eogRampMaxBias = v;
      return this;
    }

    public Builder setDraftModel(String v) {
      this.draftModel = v;
      return this;
    }

    public Builder setDraftPath(String v) {
      this.draftPath = v;
      return this;
    }

    public Builder setEagle3Model(String v) {
      this.eagle3Model = v;
      return this;
    }

    public Builder setEagle3Path(String v) {
      this.eagle3Path = v;
      return this;
    }

    public LlamaCppConfig build() {
      return new LlamaCppConfig(
        nCtx,
        nBatch,
        nUbatch,
        nSeqMax,
        nGpuLayers,
        poolingType,
        attentionType,
        flashAttnType,
        offloadKqv,
        loraPath,
        mmprojPath,
        mediaMarker,
        mtp,
        speculative,
        useMlock,
        cacheTypeK,
        cacheTypeV,
        promptCache,
        promptCacheMinTokens,
        eogRampStart,
        eogRampMaxBias,
        draftModel,
        draftPath,
        eagle3Model,
        eagle3Path
      );
    }
  }
}
