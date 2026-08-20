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

import io.gravitee.llama.cpp.ConversationState;
import io.gravitee.singularitee.engine.ModelEngineType;
import io.gravitee.singularitee.engine.TextGenRequest;
import io.gravitee.singularitee.inference.llama.cpp.BatchEngine;
import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import io.gravitee.singularitee.inference.llama.cpp.Request;

/**
 * {@link io.gravitee.singularitee.engine.TextGenEngine} backed by a llama.cpp
 * {@link BatchEngine}.
 *
 * <p>This class — together with {@link LlamaCppEngineFactory} — is the
 * <strong>only</strong> place in the project that may import
 * {@code gravitee-inference-llama-cpp} types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaCppTextGenEngine
  extends AbstractTextGenEngine<ModelConfig, Request, ConversationState> {

  private final BatchEngine delegate;
  private final int nCtx;

  LlamaCppTextGenEngine(ModelConfig modelConfig) {
    // config.nCtx() is the per-sequence window; the runtime guard measures one sequence, so use it directly.
    this(new BatchEngine(modelConfig), modelConfig.nCtx());
  }

  private LlamaCppTextGenEngine(BatchEngine delegate, int nCtx) {
    super(delegate);
    this.delegate = delegate;
    this.nCtx = nCtx;
  }

  @Override
  public int contextSize() {
    return nCtx;
  }

  @Override
  public String chatTemplateString() {
    return delegate.chatTemplateString();
  }

  @Override
  public java.util.List<String> specialTokenTexts() {
    return delegate.specialTokenTexts();
  }

  /**
   * On the direct-model path the prompt is pre-rendered from message text via Jinja4j, which drops
   * the media placeholders. For a multimodal model we inject the marker the model's own mtmd context
   * expects (from {@code mtmd_get_marker}) once per attachment before rendering, so the rendered
   * prompt carries the same marker count as the bitmaps attached downstream (otherwise
   * {@code mtmd_tokenize} fails: markers 0 != bitmaps N).
   */
  @Override
  protected String mediaMarker() {
    return delegate.isMultimodal() ? delegate.mediaMarker() : null;
  }

  /**
   * Reports what the loaded {@code mmproj} projector can actually decode. A model
   * with no projector reads text alone; one with a projector reads text plus
   * whatever {@code mtmd} says it supports, which is how a VLM is told from an ALM
   * without guessing from the model name.
   */
  @Override
  public java.util.List<String> inputModalities() {
    return io.gravitee.singularitee.engine.Modalities.of(
      delegate.supportsVision(),
      delegate.supportsAudio()
    );
  }

  @Override
  public int countTokens(String text) {
    return delegate.countTokens(text);
  }

  @Override
  public String bosToken() {
    return delegate.bosToken();
  }

  @Override
  public String eosToken() {
    return delegate.eosToken();
  }

  @Override
  protected Request toEngineRequest(TextGenRequest request) {
    return new Request(
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
      request.topLogprobs()
    );
  }
}
