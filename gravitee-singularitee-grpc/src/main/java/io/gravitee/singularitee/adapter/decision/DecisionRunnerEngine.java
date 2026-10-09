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
package io.gravitee.singularitee.adapter.decision;

import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.decision.DecisionRunner;
import io.gravitee.singularitee.inference.decision.PromptSource;
import io.gravitee.singularitee.protocol.ModelType;
import io.gravitee.singularitee.workspace.config.LlamaCppDecisionConfig;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;

/**
 * {@link DecisionEngine} over the shared {@link DecisionRunner}, whichever backend scores its
 * prompts (llama.cpp, or vLLM for custom models).
 *
 * <p>Calls run unordered on the worker pool: the llama.cpp backend hands each call one of its
 * deciders, the vLLM backend batches concurrent calls on one scheduler.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionRunnerEngine
  extends BlockingEngineAdapter<DecisionRunner>
  implements DecisionEngine, ProtoTypedEngine {

  private final ModelType modelType;

  /** @param modelType the model type that loaded it ({@code LLAMA_CPP_DECISION}, {@code VLLM_DECISION}) */
  DecisionRunnerEngine(DecisionRunner runner, ModelType modelType, Vertx vertx) {
    super(runner, vertx, false);
    this.modelType = modelType;
  }

  @Override
  public ModelType protoModelType() {
    return modelType;
  }

  /**
   * The prompt source a workspace names: {@code model}, {@code chat} (with its {@code prefill},
   * empty for the default), {@code none}, or a materialised {@code templates:} entry.
   */
  static PromptSource promptSource(String source, String template, String prefill) {
    return switch (source) {
      case LlamaCppDecisionConfig.SOURCE_MODEL -> new PromptSource.ModelTemplate();
      case LlamaCppDecisionConfig.SOURCE_CHAT -> new PromptSource.ChatWrapped(
        prefill.isEmpty() ? PromptSource.ChatWrapped.DEFAULT_PREFILL : prefill
      );
      case LlamaCppDecisionConfig.SOURCE_NONE -> new PromptSource.None();
      default -> new PromptSource.WorkspaceTemplate(template);
    };
  }

  @Override
  public DecisionCapabilities capabilities() {
    return delegate.capabilities();
  }

  @Override
  public Single<DecisionResult> rxDecide(DecisionRequest request) {
    return rxInfer(() -> delegate.decide(request));
  }

  @Override
  public void close() {
    delegate.close();
  }
}
