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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.protocol.ClassifyStepConfig;
import io.gravitee.singularitee.protocol.PipelineStep;
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
  public ClassifyStepConfig extractConfig(PipelineStep step) {
    return step.getClassifyConfig();
  }

  @Override
  protected String getModelId(ClassifyStepConfig config) {
    return config.getModelId();
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
    String text = resolveInputText(stepId, cfg.getInputField(), ctx);
    if (text == null) return ctx.rxNextStep(stepId);

    return engine
      .rxClassify(new ClassifyRequest(text))
      .flatMapMaybe(result -> {
        String outputField = resolveOutputField(cfg.getOutputField(), stepId, ".label");
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
