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
package io.gravitee.singularitee.plugin.infer;

import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.protocol.LoraConfig;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.TagConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime config of an {@code infer} step. Strings and lists are never null (blank or
 * empty means unset); the {@link Boolean} toggles and the message-level objects stay
 * {@code null} when the workspace is silent, so the executor can apply its own default.
 *
 * @param modelId                the text-generation model to call
 * @param outputField            context key the generated text is written to; blank for the default
 * @param rawTemplate            raw Jinja prompt bypassing the chat template; blank when messages apply
 * @param messages               templated messages overriding the conversation; empty for passthrough
 * @param samplingParams         step-level sampling params, or {@code null} for engine defaults
 * @param stop                   stop strings ending generation
 * @param reasoningTags          reasoning channel markers, or {@code null}
 * @param toolCallTags           tool-call channel markers, or {@code null}
 * @param lora                   LoRA adapter, or {@code null}
 * @param context                extra template variables, or {@code null}
 * @param injectTools            forward the caller's tools; {@code null} means true
 * @param stripThinking          drop reasoning tokens from stream and context; {@code null} means false
 * @param streamThinking         forward reasoning deltas on internal steps; {@code null} means false
 * @param systemPrompt           step system prompt merged into the conversation; blank for none
 * @param trimHistory            trim older turns to the context window; {@code null} means true
 * @param toolExtractionTemplate built-in dialect name or inline extraction template; blank for built-ins
 * @param chatTemplate           per-step chat template override, or {@code null} for the model's own
 * @param exposeServerTools      inject the server-owned tools; {@code null} means true
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record InferStepConfig(
  String modelId,
  String outputField,
  String rawTemplate,
  List<MessageTemplate> messages,
  SamplingParams samplingParams,
  List<String> stop,
  TagConfig reasoningTags,
  TagConfig toolCallTags,
  LoraConfig lora,
  Map<String, Object> context,
  Boolean injectTools,
  Boolean stripThinking,
  Boolean streamThinking,
  String systemPrompt,
  Boolean trimHistory,
  String toolExtractionTemplate,
  String chatTemplate,
  Boolean exposeServerTools
) implements ModelBoundConfig {
  public InferStepConfig {
    modelId = modelId == null ? "" : modelId;
    outputField = outputField == null ? "" : outputField;
    rawTemplate = rawTemplate == null ? "" : rawTemplate;
    messages = messages == null ? List.of() : List.copyOf(messages);
    stop = stop == null ? List.of() : List.copyOf(stop);
    systemPrompt = systemPrompt == null ? "" : systemPrompt;
    toolExtractionTemplate = toolExtractionTemplate == null ? "" : toolExtractionTemplate;
    if (context != null) {
      context = new LinkedHashMap<>(context);
    }
  }

  /** A config naming only its model; every other component is unset. */
  public static InferStepConfig ofModel(String modelId) {
    return builder().modelId(modelId).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The effective sampling params: the step's own or the proto default. */
  public SamplingParams effectiveSamplingParams() {
    return samplingParams != null ? samplingParams : SamplingParams.getDefaultInstance();
  }

  /** Whether the tool-call tags define a usable open marker. */
  public boolean hasToolOpenTag() {
    return toolCallTags != null && !toolCallTags.getOpenTag().isBlank();
  }

  public boolean shouldInjectTools() {
    return injectTools == null || injectTools;
  }

  public boolean shouldExposeServerTools() {
    return exposeServerTools == null || exposeServerTools;
  }

  public boolean shouldStripThinking() {
    return stripThinking != null && stripThinking;
  }

  public boolean shouldStreamThinking() {
    return streamThinking != null && streamThinking;
  }

  public boolean shouldTrimHistory() {
    return trimHistory == null || trimHistory;
  }

  public boolean hasChatTemplate() {
    return chatTemplate != null && !chatTemplate.isBlank();
  }

  /** Builds a config one component at a time; unset components keep their unset value. */
  public static final class Builder {

    private String modelId;
    private String outputField;
    private String rawTemplate;
    private final List<MessageTemplate> messages = new ArrayList<>();
    private SamplingParams samplingParams;
    private final List<String> stop = new ArrayList<>();
    private TagConfig reasoningTags;
    private TagConfig toolCallTags;
    private LoraConfig lora;
    private Map<String, Object> context;
    private Boolean injectTools;
    private Boolean stripThinking;
    private Boolean streamThinking;
    private String systemPrompt;
    private Boolean trimHistory;
    private String toolExtractionTemplate;
    private String chatTemplate;
    private Boolean exposeServerTools;

    private Builder() {}

    public Builder modelId(String v) {
      modelId = v;
      return this;
    }

    public Builder outputField(String v) {
      outputField = v;
      return this;
    }

    public Builder rawTemplate(String v) {
      rawTemplate = v;
      return this;
    }

    public Builder message(String role, String content) {
      messages.add(new MessageTemplate(role, content));
      return this;
    }

    public Builder messages(List<MessageTemplate> v) {
      messages.addAll(v);
      return this;
    }

    public Builder samplingParams(SamplingParams v) {
      samplingParams = v;
      return this;
    }

    public Builder stop(List<String> v) {
      stop.addAll(v);
      return this;
    }

    public Builder reasoningTags(TagConfig v) {
      reasoningTags = v;
      return this;
    }

    public Builder toolCallTags(TagConfig v) {
      toolCallTags = v;
      return this;
    }

    public Builder lora(LoraConfig v) {
      lora = v;
      return this;
    }

    public Builder context(Map<String, Object> v) {
      context = v;
      return this;
    }

    public Builder injectTools(Boolean v) {
      injectTools = v;
      return this;
    }

    public Builder stripThinking(Boolean v) {
      stripThinking = v;
      return this;
    }

    public Builder streamThinking(Boolean v) {
      streamThinking = v;
      return this;
    }

    public Builder systemPrompt(String v) {
      systemPrompt = v;
      return this;
    }

    public Builder trimHistory(Boolean v) {
      trimHistory = v;
      return this;
    }

    public Builder toolExtractionTemplate(String v) {
      toolExtractionTemplate = v;
      return this;
    }

    public Builder chatTemplate(String v) {
      chatTemplate = v;
      return this;
    }

    public Builder exposeServerTools(Boolean v) {
      exposeServerTools = v;
      return this;
    }

    public InferStepConfig build() {
      return new InferStepConfig(
        modelId,
        outputField,
        rawTemplate,
        messages,
        samplingParams,
        stop,
        reasoningTags,
        toolCallTags,
        lora,
        context,
        injectTools,
        stripThinking,
        streamThinking,
        systemPrompt,
        trimHistory,
        toolExtractionTemplate,
        chatTemplate,
        exposeServerTools
      );
    }
  }
}
