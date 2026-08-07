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
 * vLLM engine configuration, as a plain record.
 *
 * <p>Never on the wire; proto3 semantics preserved (zero / empty = engine
 * default). {@code enablePrefixCaching} and {@code enableSleepMode} are boxed:
 * {@code null} means "unset — engine default applies", and an explicit
 * {@code false} is a real disable, which is what the proto3 bool could not
 * express and what made {@code enable_prefix_caching: false} a silent no-op.
 */
public record VllmConfig(
  String dtype,
  int maxModelLen,
  int maxNumSeqs,
  double gpuMemoryUtilization,
  int maxNumBatchedTokens,
  boolean enforceEager,
  boolean trustRemoteCode,
  String quantization,
  int seed,
  Boolean enablePrefixCaching,
  boolean enableChunkedPrefill,
  String kvCacheDtype,
  boolean enableLora,
  int maxLoras,
  int maxLoraRank,
  Boolean enableSleepMode,
  int tensorParallelSize,
  int pipelineParallelSize,
  String distributedExecutorBackend
) {
  private static final VllmConfig DEFAULT = newBuilder().build();

  public VllmConfig {
    dtype = dtype == null ? "" : dtype;
    quantization = quantization == null ? "" : quantization;
    kvCacheDtype = kvCacheDtype == null ? "" : kvCacheDtype;
    distributedExecutorBackend = distributedExecutorBackend == null
      ? ""
      : distributedExecutorBackend;
  }

  /** All engine defaults — what an absent YAML block means. */
  public static VllmConfig getDefaultInstance() {
    return DEFAULT;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {

    private String dtype = "";
    private int maxModelLen;
    private int maxNumSeqs;
    private double gpuMemoryUtilization;
    private int maxNumBatchedTokens;
    private boolean enforceEager;
    private boolean trustRemoteCode;
    private String quantization = "";
    private int seed;
    private Boolean enablePrefixCaching;
    private boolean enableChunkedPrefill;
    private String kvCacheDtype = "";
    private boolean enableLora;
    private int maxLoras;
    private int maxLoraRank;
    private Boolean enableSleepMode;
    private int tensorParallelSize;
    private int pipelineParallelSize;
    private String distributedExecutorBackend = "";

    public Builder setDtype(String v) {
      this.dtype = v;
      return this;
    }

    public Builder setMaxModelLen(int v) {
      this.maxModelLen = v;
      return this;
    }

    public Builder setMaxNumSeqs(int v) {
      this.maxNumSeqs = v;
      return this;
    }

    public Builder setGpuMemoryUtilization(double v) {
      this.gpuMemoryUtilization = v;
      return this;
    }

    public Builder setMaxNumBatchedTokens(int v) {
      this.maxNumBatchedTokens = v;
      return this;
    }

    public Builder setEnforceEager(boolean v) {
      this.enforceEager = v;
      return this;
    }

    public Builder setTrustRemoteCode(boolean v) {
      this.trustRemoteCode = v;
      return this;
    }

    public Builder setQuantization(String v) {
      this.quantization = v;
      return this;
    }

    public Builder setSeed(int v) {
      this.seed = v;
      return this;
    }

    public Builder setEnablePrefixCaching(boolean v) {
      this.enablePrefixCaching = v;
      return this;
    }

    public Builder setEnableChunkedPrefill(boolean v) {
      this.enableChunkedPrefill = v;
      return this;
    }

    public Builder setKvCacheDtype(String v) {
      this.kvCacheDtype = v;
      return this;
    }

    public Builder setEnableLora(boolean v) {
      this.enableLora = v;
      return this;
    }

    public Builder setMaxLoras(int v) {
      this.maxLoras = v;
      return this;
    }

    public Builder setMaxLoraRank(int v) {
      this.maxLoraRank = v;
      return this;
    }

    public Builder setEnableSleepMode(boolean v) {
      this.enableSleepMode = v;
      return this;
    }

    public Builder setTensorParallelSize(int v) {
      this.tensorParallelSize = v;
      return this;
    }

    public Builder setPipelineParallelSize(int v) {
      this.pipelineParallelSize = v;
      return this;
    }

    public Builder setDistributedExecutorBackend(String v) {
      this.distributedExecutorBackend = v;
      return this;
    }

    public VllmConfig build() {
      return new VllmConfig(
        dtype,
        maxModelLen,
        maxNumSeqs,
        gpuMemoryUtilization,
        maxNumBatchedTokens,
        enforceEager,
        trustRemoteCode,
        quantization,
        seed,
        enablePrefixCaching,
        enableChunkedPrefill,
        kvCacheDtype,
        enableLora,
        maxLoras,
        maxLoraRank,
        enableSleepMode,
        tensorParallelSize,
        pipelineParallelSize,
        distributedExecutorBackend
      );
    }
  }
}
