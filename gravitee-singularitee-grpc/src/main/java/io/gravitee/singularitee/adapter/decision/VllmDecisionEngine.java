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
import io.gravitee.singularitee.inference.vllm.decision.VllmDecisionModel;
import io.gravitee.singularitee.protocol.ModelType;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;

/**
 * {@link DecisionEngine} backed by a vLLM Semantic Router decision model.
 *
 * <p>Calls run unordered on the worker pool: the model runtime batches concurrent requests on its
 * own scheduler, with the GIL released while a call waits.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmDecisionEngine
  extends BlockingEngineAdapter<VllmDecisionModel>
  implements DecisionEngine, ProtoTypedEngine {

  VllmDecisionEngine(VllmDecisionModel model, Vertx vertx) {
    super(model, vertx, false);
  }

  @Override
  public ModelType protoModelType() {
    return ModelType.MODEL_TYPE_VLLM_DECISION;
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
