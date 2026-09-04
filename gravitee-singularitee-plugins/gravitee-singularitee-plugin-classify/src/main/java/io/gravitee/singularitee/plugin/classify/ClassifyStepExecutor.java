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
package io.gravitee.singularitee.plugin.classify;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.reactivex.rxjava3.core.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a CLASSIFY step: runs a classifier model on input text and stores results.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ClassifyStepExecutor
  extends ModelBoundStepExecutor<ClassifyStepConfig, ClassifierEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClassifyStepExecutor.class);

  public ClassifyStepExecutor(StepExecutionContext execContext) {
    super(execContext);
  }

  @Override
  protected String getModelId(ClassifyStepConfig config) {
    return config.modelId();
  }

  @Override
  protected Class<ClassifierEngine> engineType() {
    return ClassifierEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    ClassifyStepConfig cfg,
    ClassifierEngine engine,
    StepContext ctx
  ) {
    String text = resolveInputText(stepId, cfg.inputField(), ctx);
    if (text == null) return ctx.rxNextStep(stepId);

    // Capture the step-span scribe now (valid before the async classify; see InferStepExecutor).
    final SpanScribe stepScribe = ctx.stepScribe();
    return engine
      .rxClassify(new ClassifyRequest(text))
      .flatMapMaybe(result -> {
        // Span introspection: the winning label and its score.
        stepScribe
          .set(SpanNames.key("classify.label"), result.topLabel())
          .set(SpanNames.key("classify.score"), (double) result.topScore());
        String outputField = resolveOutputField(cfg.outputField(), stepId, ".label");
        ctx.pipelineContext().set(outputField, result.topLabel());
        ctx.pipelineContext().set(outputField + ".score", String.valueOf(result.topScore()));

        LOGGER.debug(
          "ClassifyStep '{}': label='{}' score={}",
          stepId,
          result.topLabel(),
          result.topScore()
        );
        return ctx.rxNextStep(stepId);
      });
  }
}
