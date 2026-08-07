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
package io.gravitee.singularitee.inference.vllm;

import io.gravitee.singularitee.inference.api.textgen.AbstractBatchEngine;
import io.gravitee.singularitee.inference.api.textgen.BatchEngineConfig;
import io.gravitee.singularitee.inference.api.textgen.InferenceToken;
import java.util.function.Consumer;

/**
 * Batch inference engine for vLLM models.
 * Delegates all complex orchestration to {@link AbstractBatchEngine},
 * focusing only on vLLM-specific configuration.
 *
 * <p>Unlike llama.cpp which requires a local GGUF file, vLLM takes a HuggingFace
 * model identifier and downloads/loads the model itself via the Python engine.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class BatchEngine
  extends AbstractBatchEngine<VllmConfig, VllmRequest, String, EngineAdapter.VllmSequenceState> {

  private final EngineAdapter engineAdapter;

  /**
   * Creates a new vLLM batch engine with default configuration.
   *
   * @param config The vLLM configuration
   */
  public BatchEngine(VllmConfig config) {
    this(BatchEngineConfig.of(config.maxNumSeqs() > 0 ? config.maxNumSeqs() : 8), config);
  }

  /**
   * Creates a new vLLM batch engine with custom engine configuration.
   *
   * @param engineConfig The engine configuration (slots, queue capacity, etc.)
   * @param vllmConfig The vLLM configuration
   */
  public BatchEngine(BatchEngineConfig engineConfig, VllmConfig vllmConfig) {
    this(engineConfig, new EngineAdapter(vllmConfig));
  }

  private BatchEngine(BatchEngineConfig engineConfig, EngineAdapter adapter) {
    super(engineConfig, adapter);
    this.engineAdapter = adapter;
  }

  /** Returns the raw chat template string from the HuggingFace tokenizer. */
  public String chatTemplateString() {
    return engineAdapter.chatTemplateString();
  }

  public int maxModelLen() {
    return engineAdapter.maxModelLen();
  }

  public java.util.List<String> allSpecialTokens() {
    return engineAdapter.allSpecialTokens();
  }

  public int countTokens(String text) {
    return engineAdapter.countTokens(text);
  }

  public String bosToken() {
    return engineAdapter.bosToken();
  }

  public String eosToken() {
    return engineAdapter.eosToken();
  }

  /**
   * Starts the engine and begins processing sequences.
   *
   * @param tokenConsumer Callback for receiving generated tokens
   */
  @Override
  public void start(Consumer<InferenceToken<String>> tokenConsumer) {
    super.start(tokenConsumer);
  }
}
