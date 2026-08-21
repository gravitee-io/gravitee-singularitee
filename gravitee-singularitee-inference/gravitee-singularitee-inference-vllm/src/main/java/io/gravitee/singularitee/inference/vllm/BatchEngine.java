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
 *
 * <p>Delegates sequence lifecycle, slots, queuing and streaming to
 * {@link AbstractBatchEngine} and contributes only the vLLM-specific
 * {@link EngineAdapter}. Construction loads the model and initialises the
 * CPython runtime.
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

  /** Context window vLLM resolved for this model, in tokens (0 if unknown). */
  public int maxModelLen() {
    return engineAdapter.maxModelLen();
  }

  /** Every special token the tokenizer declares. */
  public java.util.List<String> allSpecialTokens() {
    return engineAdapter.allSpecialTokens();
  }

  /**
   * Counts tokens with the model's own tokenizer.
   *
   * @return the exact token count, or -1 when it cannot be determined
   */
  public int countTokens(String text) {
    return engineAdapter.countTokens(text);
  }

  /** Beginning-of-sequence token text as declared by the tokenizer. */
  public String bosToken() {
    return engineAdapter.bosToken();
  }

  /** End-of-sequence token text as declared by the tokenizer. */
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
