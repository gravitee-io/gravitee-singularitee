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
package io.gravitee.singularitee.plugin.embed;

import io.gravitee.singularitee.engine.api.EmbedRequest;
import io.gravitee.singularitee.engine.api.EmbeddingEngine;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes an EMBED step: runs an embedding model on input text and stores the vector.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class EmbedStepExecutor
  extends ModelBoundStepExecutor<EmbedStepConfig, EmbeddingEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EmbedStepExecutor.class);

  public EmbedStepExecutor(StepExecutionContext execContext) {
    super(execContext);
  }

  @Override
  protected String getModelId(EmbedStepConfig config) {
    return config.modelId();
  }

  @Override
  protected Class<EmbeddingEngine> engineType() {
    return EmbeddingEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    EmbedStepConfig cfg,
    EmbeddingEngine engine,
    StepContext ctx
  ) {
    String text = resolveInputText(stepId, cfg.inputField(), ctx);
    if (text == null) return ctx.rxNextStep(stepId);

    return engine
      .rxEmbed(new EmbedRequest(text))
      .flatMapMaybe(resp -> {
        String outputField = resolveOutputField(cfg.outputField(), stepId, ".embedding");
        ctx.pipelineContext().set(outputField, Arrays.toString(resp.embedding()));

        LOGGER.debug(
          "EmbedStep '{}': generated embedding with {} dimensions",
          stepId,
          resp.embedding().length
        );
        return ctx.rxNextStep(stepId);
      });
  }
}
