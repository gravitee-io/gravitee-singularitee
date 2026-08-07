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
package io.gravitee.singularitee.adapter.textgen;

import io.gravitee.singularitee.engine.ModelEngineType;
import io.gravitee.singularitee.engine.TextGenRequest;
import io.gravitee.singularitee.inference.vllm.BatchEngine;
import io.gravitee.singularitee.inference.vllm.EngineAdapter;
import io.gravitee.singularitee.inference.vllm.VllmConfig;
import io.gravitee.singularitee.inference.vllm.VllmRequest;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link io.gravitee.singularitee.engine.TextGenEngine} backed by a vLLM
 * {@link BatchEngine}.
 *
 * <p>This class — together with {@link VllmEngineFactory} — is the
 * <strong>only</strong> place in the project that may import
 * {@code gravitee-inference-vllm} types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmTextGenEngine
  extends AbstractTextGenEngine<VllmConfig, VllmRequest, EngineAdapter.VllmSequenceState> {

  private static final Logger LOGGER = LoggerFactory.getLogger(VllmTextGenEngine.class);

  private final BatchEngine delegate;

  /**
   * Context window and special tokens are fixed for the life of the engine but
   * each read crosses into CPython and takes the GIL, and
   * {@link #specialTokenTexts()} is consulted on every request. Resolved once,
   * lazily, so a failure at construction time cannot stop the engine loading.
   */
  private volatile int contextSize = -1;

  private volatile List<String> specialTokens;

  VllmTextGenEngine(BatchEngine delegate) {
    super(delegate);
    this.delegate = delegate;
  }

  @Override
  public ModelEngineType type() {
    return ModelEngineType.TEXT_GEN;
  }

  @Override
  public String chatTemplateString() {
    return delegate.chatTemplateString();
  }

  @Override
  public String bosToken() {
    return delegate.bosToken();
  }

  @Override
  public String eosToken() {
    return delegate.eosToken();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reports what vLLM actually resolved rather than what the workspace
   * asked for — an unset {@code max_model_len} is derived from the
   * checkpoint. Returning a real value here arms the context-overrun guard in
   * {@link AbstractTextGenEngine}, which stays disabled while this is 0.
   */
  @Override
  public int contextSize() {
    int cached = contextSize;
    if (cached < 0) {
      cached = safely(delegate::maxModelLen, 0, "context size");
      contextSize = cached;
    }
    return cached;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Exact, via the model's own tokenizer, rather than the character-count
   * heuristic the caller falls back to when this returns -1.
   */
  @Override
  public int countTokens(String text) {
    return delegate.countTokens(text);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Sorted longest-first, as the contract requires: neutralisation replaces
   * these in order, and a short marker matching inside a longer one would
   * otherwise corrupt it.
   */
  @Override
  public List<String> specialTokenTexts() {
    List<String> cached = specialTokens;
    if (cached == null) {
      List<String> tokens = safely(delegate::allSpecialTokens, List.<String>of(), "special tokens");
      cached = tokens
        .stream()
        .distinct()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList();
      specialTokens = cached;
    }
    return cached;
  }

  /**
   * Runs a one-off engine introspection, degrading to {@code fallback} rather
   * than failing the request: every caller of these accessors already handles
   * the "engine cannot tell me" case.
   */
  private static <T> T safely(Supplier<T> read, T fallback, String what) {
    try {
      return read.get();
    } catch (RuntimeException e) {
      LOGGER.debug("Could not read {} from vLLM: {}", what, e.getMessage());
      return fallback;
    }
  }

  @Override
  protected VllmRequest toEngineRequest(TextGenRequest request) {
    return new VllmRequest(
      request.prompt(),
      toChatMessages(request.messages()),
      request.maxTokens(),
      request.temperature(),
      request.topP(),
      request.presencePenalty(),
      request.frequencyPenalty(),
      request.stop() == null || request.stop().isEmpty() ? null : request.stop(),
      request.seed(),
      toLibraryTagConfig(request.reasoningTags()),
      toLibraryTagConfig(request.toolCallTags()),
      null, // tools — handled by Jinja4j at the executor level now
      request.loraName(),
      request.loraPath()
    );
  }
}
