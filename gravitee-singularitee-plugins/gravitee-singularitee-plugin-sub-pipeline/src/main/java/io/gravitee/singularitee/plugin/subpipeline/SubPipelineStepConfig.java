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
package io.gravitee.singularitee.plugin.subpipeline;

/**
 * Runtime config of a {@code sub_pipeline} step: which pipeline to invoke, where its
 * prompt comes from, where its output lands, and how the transcript is forwarded.
 *
 * @param pipelineId      id of the pipeline to invoke
 * @param inputField      context key passed as the sub-pipeline's prompt; blank means the prompt
 * @param outputField     context key receiving the sub-pipeline's final output; blank means
 *                        {@code <stepId>.output}
 * @param remoteId        id of the {@code remote:} endpoint to run it on; blank means local
 * @param systemPrompt    system message prepended for the sub-pipeline, replacing any existing one
 * @param forwardMessages send the full chat transcript instead of the flat prompt
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record SubPipelineStepConfig(
  String pipelineId,
  String inputField,
  String outputField,
  String remoteId,
  String systemPrompt,
  boolean forwardMessages
) {
  public SubPipelineStepConfig {
    pipelineId = pipelineId == null ? "" : pipelineId;
    inputField = inputField == null ? "" : inputField;
    outputField = outputField == null ? "" : outputField;
    remoteId = remoteId == null ? "" : remoteId;
    systemPrompt = systemPrompt == null ? "" : systemPrompt;
  }
}
