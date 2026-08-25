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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.TagConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the {@code config:} block of an {@code infer} step: the prompt (raw template by
 * id, file or inline, or structured messages), sampling, the reasoning/tool tag set
 * (inline or a reference into the workspace {@code tags:} section), the per-step
 * context and the chat-template override.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class InferStepCodec implements StepConfigCodec<InferStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(InferStepCodec.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The workspace section named tag sets live in. */
  static final String TAGS_SECTION = "tags";

  @Override
  public InferStepConfig parse(String stepId, Map<String, Object> raw, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(raw, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid infer config: " + e.getMessage(),
        e
      );
    }
    var b = InferStepConfig.builder().modelId(d.modelId()).outputField(d.outputField());

    // Raw template: template_id > template_file > inline template (mutually exclusive).
    String resolvedTemplate = resolveTemplate(stepId, d.prompt(), ctx);
    if (resolvedTemplate != null && !resolvedTemplate.isBlank()) {
      b.rawTemplate(resolvedTemplate);
    } else if (d.prompt() != null && d.prompt().messages() != null) {
      for (var msg : d.prompt().messages()) {
        b.message(msg.role(), msg.content());
      }
    }

    b.samplingParams(toSamplingParams(d.sampling()));
    if (d.sampling() != null && d.sampling().stop() != null) {
      b.stop(d.sampling().stop());
    }

    // A bare string tags value references the workspace's named tags: entries.
    var tags = resolveTags(stepId, d.tags(), ctx);
    if (tags != null) {
      b.reasoningTags(
        toTagConfig(tags.reasoningOpen(), tags.reasoningClose(), tags.reasoningRepeatable())
      );
      b.toolCallTags(toTagConfig(tags.toolOpen(), tags.toolClose(), null));
    }

    if (d.context() != null && !d.context().isEmpty()) {
      b.context(d.context());
    }
    b.injectTools(d.injectTools());
    b.exposeServerTools(d.serverTools());
    b.stripThinking(d.stripThinking());
    b.streamThinking(d.streamThinking());
    if (d.system() != null && !d.system().isBlank()) {
      b.systemPrompt(d.system());
    }
    b.trimHistory(d.trimHistory());
    if (d.toolExtractionTemplate() != null && !d.toolExtractionTemplate().isBlank()) {
      b.toolExtractionTemplate(d.toolExtractionTemplate());
    }
    // A workspace templates: id resolves to its content; anything else is inline source.
    if (d.chatTemplate() != null && !d.chatTemplate().isBlank()) {
      b.chatTemplate(ctx.templates().getOrDefault(d.chatTemplate(), d.chatTemplate()));
    }
    return b.build();
  }

  // -----------------------------------------------------------------------
  // Prompt template resolution
  // -----------------------------------------------------------------------

  /**
   * Resolves the raw prompt template of a {@code prompt:} block, in priority order
   * {@code template_id}, {@code template_file}, {@code template}; {@code null} when none
   * is set (the messages path applies).
   */
  private static String resolveTemplate(String stepId, PromptYaml prompt, StepCodecContext ctx) {
    if (prompt == null) return null;
    boolean hasId = prompt.templateId() != null && !prompt.templateId().isBlank();
    boolean hasFile = prompt.templateFile() != null && !prompt.templateFile().isBlank();
    boolean hasInline = prompt.template() != null && !prompt.template().isBlank();
    long setCount = (hasId ? 1 : 0) + (hasFile ? 1 : 0) + (hasInline ? 1 : 0);
    if (setCount > 1) {
      throw new IllegalArgumentException(
        "Step '" +
          stepId +
          "': prompt.template_id, prompt.template_file and prompt.template are mutually exclusive, use only one"
      );
    }
    if (hasId) {
      String content = ctx.templates().get(prompt.templateId());
      if (content == null) {
        throw new IllegalArgumentException(
          "Step '" +
            stepId +
            "': prompt.template_id '" +
            prompt.templateId() +
            "' not found in workspace templates"
        );
      }
      return content;
    }
    if (hasFile) {
      return readFileContent(stepId, prompt.templateFile(), ctx.templatesBasePath());
    }
    return hasInline ? prompt.template() : null;
  }

  /**
   * Reads a UTF-8 text file, resolving a relative path under the templates base. A
   * relative path must stay under its base: this is the one field that turns a string
   * into a file read, and it is rendered straight into a prompt.
   */
  private static String readFileContent(String stepId, String filePath, Path basePath) {
    Path p = Path.of(filePath);
    Path resolved;
    if (p.isAbsolute() || basePath == null) {
      resolved = p;
      if (p.isAbsolute()) {
        LOGGER.info("Reading template_file from an absolute path: {}", resolved);
      }
    } else {
      Path base = basePath.toAbsolutePath().normalize();
      resolved = base.resolve(p).normalize();
      if (!resolved.startsWith(base)) {
        throw new IllegalArgumentException(
          "Step '" +
            stepId +
            "': refusing template_file '" +
            filePath +
            "': resolves outside " +
            base
        );
      }
    }
    try {
      String content = Files.readString(resolved, StandardCharsets.UTF_8);
      LOGGER.debug("Loaded template_file from '{}' ({} chars)", resolved, content.length());
      return content;
    } catch (IOException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': failed to read template_file '" + resolved + "': " + e.getMessage(),
        e
      );
    }
  }

  // -----------------------------------------------------------------------
  // Tags and sampling
  // -----------------------------------------------------------------------

  /** Resolves a reference-only tag set (bare string in YAML) against the workspace section. */
  private static TagsYaml resolveTags(String stepId, TagsYaml tags, StepCodecContext ctx) {
    if (tags == null || !tags.isReference()) {
      return tags;
    }
    Map<String, Object> named = ctx.section(TAGS_SECTION).get(tags.id());
    if (named == null) {
      throw new IllegalArgumentException(
        "Step '" +
          stepId +
          "': unknown tags id '" +
          tags.id() +
          "': declare it under workspace tags:"
      );
    }
    try {
      return MAPPER.convertValue(named, TagsYaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid tags entry '" + tags.id() + "': " + e.getMessage(),
        e
      );
    }
  }

  /**
   * Maps marker lists to a wire {@link TagConfig}: the first open marker is the primary
   * tag and the rest ride along as alternatives. {@code null} when no open marker is set.
   */
  private static TagConfig toTagConfig(
    List<String> opens,
    List<String> closes,
    Boolean repeatable
  ) {
    var openList = nonBlank(opens);
    if (openList.isEmpty()) {
      return null;
    }
    var closeList = nonBlank(closes);
    var tag = TagConfig.newBuilder()
      .setOpenTag(openList.getFirst())
      .setCloseTag(closeList.isEmpty() ? "" : closeList.getFirst());
    openList.stream().skip(1).forEach(tag::addOpenTagAlternatives);
    closeList.stream().skip(1).forEach(tag::addCloseTagAlternatives);
    // Left unset when the workspace is silent, so the engine's own rule applies.
    if (repeatable != null) {
      tag.setRepeatable(repeatable);
    }
    return tag.build();
  }

  private static List<String> nonBlank(List<String> values) {
    return values == null
      ? List.of()
      : values
        .stream()
        .filter(Objects::nonNull)
        .filter(v -> !v.isBlank())
        .toList();
  }

  /** Stop strings are carried separately on the config. */
  private static SamplingParams toSamplingParams(SamplingYaml sampling) {
    var sp = SamplingParams.newBuilder();
    if (sampling != null) {
      if (sampling.maxTokens() > 0) sp.setMaxTokens(sampling.maxTokens());
      if (sampling.temperature() > 0) sp.setTemperature(sampling.temperature());
      if (sampling.topP() > 0) sp.setTopP(sampling.topP());
      if (sampling.presencePenalty() != 0) sp.setPresencePenalty(sampling.presencePenalty());
      if (sampling.frequencyPenalty() != 0) sp.setFrequencyPenalty(sampling.frequencyPenalty());
    }
    return sp.build();
  }

  // -----------------------------------------------------------------------
  // YAML shapes
  // -----------------------------------------------------------------------

  /** The YAML shape of the block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("prompt") PromptYaml prompt,
    @JsonProperty("sampling") SamplingYaml sampling,
    @JsonProperty("tags") TagsYaml tags,
    @JsonProperty("context") Map<String, Object> context,
    @JsonProperty("inject_tools") Boolean injectTools,
    @JsonProperty("server_tools") Boolean serverTools,
    @JsonProperty("strip_thinking") Boolean stripThinking,
    @JsonProperty("stream_thinking") Boolean streamThinking,
    @JsonProperty("system") String system,
    @JsonProperty("trim_history") Boolean trimHistory,
    @JsonProperty("tool_extraction_template") String toolExtractionTemplate,
    @JsonProperty("chat_template") String chatTemplate
  ) {}

  /** One entry of {@code prompt.messages}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MessageYaml(
    @JsonProperty("role") String role,
    @JsonProperty("content") String content
  ) {}

  /** The {@code prompt:} block: raw template (three exclusive forms) or messages. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PromptYaml(
    @JsonProperty("messages") List<MessageYaml> messages,
    @JsonProperty("template") String template,
    @JsonProperty("template_file") String templateFile,
    @JsonProperty("template_id") String templateId
  ) {}

  /** The {@code sampling:} block. Zero leaves the engine default in place. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SamplingYaml(
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("temperature") float temperature,
    @JsonProperty("top_p") float topP,
    @JsonProperty("presence_penalty") float presencePenalty,
    @JsonProperty("frequency_penalty") float frequencyPenalty,
    @JsonProperty("stop") List<String> stop
  ) {}

  /**
   * A reasoning/tool tag set, inline or a workspace {@code tags:} entry. Each marker key
   * accepts a single string or a list; a bare string as the whole value is a reference.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TagsYaml(
    @JsonProperty("id") String id,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("reasoning_open")
    List<String> reasoningOpen,
    @JsonProperty("reasoning_repeatable") Boolean reasoningRepeatable,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("reasoning_close")
    List<String> reasoningClose,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("tool_open")
    List<String> toolOpen,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("tool_close")
    List<String> toolClose
  ) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static TagsYaml ref(String id) {
      return new TagsYaml(id, null, null, null, null, null);
    }

    boolean isReference() {
      return (
        id != null &&
        reasoningOpen == null &&
        reasoningClose == null &&
        toolOpen == null &&
        toolClose == null &&
        reasoningRepeatable == null
      );
    }
  }
}
