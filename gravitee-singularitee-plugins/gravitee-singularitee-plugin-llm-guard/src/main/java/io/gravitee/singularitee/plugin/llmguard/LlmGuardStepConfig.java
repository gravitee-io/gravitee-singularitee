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
package io.gravitee.singularitee.plugin.llmguard;

import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.protocol.SamplingParams;
import java.util.List;
import java.util.Map;

/**
 * Runtime config of an {@code llm_guard} step: the judge model, its prompt (a raw
 * template or chat messages), how a safe verdict reads and what to do otherwise.
 *
 * @param modelId        the judge text-generation model id
 * @param action         what happens on an unsafe verdict; REDACT falls back to WARN
 * @param safeToken      first token expected when content is safe; blank = {@code safe}
 * @param rawTemplate    resolved raw Jinja prompt; blank when {@code messages} is used
 * @param messages       templated chat messages; empty when {@code rawTemplate} is used
 * @param samplingParams sampling override for the judge call
 * @param message        Jinja reject message; blank = none
 * @param context        extra template variables exposed to the judge templates
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record LlmGuardStepConfig(
  String modelId,
  GuardAction action,
  String safeToken,
  String rawTemplate,
  List<MessageTemplate> messages,
  SamplingParams samplingParams,
  String message,
  Map<String, Object> context
) implements ModelBoundConfig {
  public LlmGuardStepConfig {
    modelId = modelId == null ? "" : modelId;
    action = action == null ? GuardAction.REJECT : action;
    safeToken = safeToken == null ? "" : safeToken;
    rawTemplate = rawTemplate == null ? "" : rawTemplate;
    messages = messages == null ? List.of() : List.copyOf(messages);
    samplingParams = samplingParams == null ? SamplingParams.getDefaultInstance() : samplingParams;
    message = message == null ? "" : message;
    context = context == null ? Map.of() : Map.copyOf(context);
  }
}
