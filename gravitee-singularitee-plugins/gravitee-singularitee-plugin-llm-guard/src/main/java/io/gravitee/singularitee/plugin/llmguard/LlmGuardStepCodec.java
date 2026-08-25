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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.protocol.SamplingParams;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the {@code config:} block of an {@code llm_guard} step. The prompt's
 * {@code template_id}, {@code template_file} and {@code template} are mutually exclusive
 * and produce a raw prompt; otherwise {@code messages} go through the chat template.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlmGuardStepCodec implements StepConfigCodec<LlmGuardStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlmGuardStepCodec.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("action") String action,
    @JsonProperty("safe_token") String safeToken,
    @JsonProperty("prompt") PromptYaml prompt,
    @JsonProperty("sampling") SamplingYaml sampling,
    @JsonProperty("message") String message,
    @JsonProperty("context") Map<String, Object> context
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PromptYaml(
    @JsonProperty("messages") List<MessageYaml> messages,
    @JsonProperty("template") String template,
    @JsonProperty("template_file") String templateFile,
    @JsonProperty("template_id") String templateId
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MessageYaml(
    @JsonProperty("role") String role,
    @JsonProperty("content") String content
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SamplingYaml(
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("temperature") float temperature,
    @JsonProperty("top_p") float topP,
    @JsonProperty("presence_penalty") float presencePenalty,
    @JsonProperty("frequency_penalty") float frequencyPenalty,
    @JsonProperty("stop") List<String> stop
  ) {}

  @Override
  public LlmGuardStepConfig parse(String stepId, Map<String, Object> config, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(config, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid llm_guard config: " + e.getMessage(),
        e
      );
    }

    String rawTemplate;
    try {
      rawTemplate = resolveTemplate(d.prompt(), ctx.templatesBasePath(), ctx.templates());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Step '" + stepId + "': " + e.getMessage(), e);
    }

    List<MessageTemplate> messages = List.of();
    if (rawTemplate == null || rawTemplate.isBlank()) {
      rawTemplate = "";
      List<MessageYaml> entries = d.prompt() != null ? d.prompt().messages() : null;
      if (entries != null) {
        messages = entries
          .stream()
          .map(m -> new MessageTemplate(m.role() != null ? m.role() : "user", m.content()))
          .toList();
      }
    }

    return new LlmGuardStepConfig(
      d.modelId(),
      GuardAction.parse(d.action()),
      d.safeToken() != null && !d.safeToken().isBlank() ? d.safeToken() : "",
      rawTemplate,
      messages,
      toSamplingParams(d.sampling()),
      d.message() != null && !d.message().isBlank() ? d.message() : "",
      d.context()
    );
  }

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

  private static String resolveTemplate(
    PromptYaml prompt,
    Path basePath,
    Map<String, String> templateRegistry
  ) {
    if (prompt == null) return null;
    boolean hasId = prompt.templateId() != null && !prompt.templateId().isBlank();
    boolean hasFile = prompt.templateFile() != null && !prompt.templateFile().isBlank();
    boolean hasInline = prompt.template() != null && !prompt.template().isBlank();
    long setCount = (hasId ? 1 : 0) + (hasFile ? 1 : 0) + (hasInline ? 1 : 0);
    if (setCount > 1) {
      throw new IllegalArgumentException(
        "prompt.template_id, prompt.template_file and prompt.template are mutually exclusive, use only one"
      );
    }
    if (hasId) {
      String content = templateRegistry.get(prompt.templateId());
      if (content == null) {
        throw new IllegalArgumentException(
          "prompt.template_id '" + prompt.templateId() + "' not found in workspace templates"
        );
      }
      return content;
    }
    if (hasFile) {
      return readFileContent(prompt.templateFile(), basePath, "template_file");
    }
    return hasInline ? prompt.template() : null;
  }

  private static String readFileContent(String filePath, Path basePath, String fieldName) {
    Path p = Path.of(filePath);
    Path resolved;
    if (p.isAbsolute() || basePath == null) {
      // An absolute path is an explicit, auditable operator choice (templates
      // mounted outside the workspace). Logged so it is visible in a deployment.
      resolved = p;
      if (p.isAbsolute()) {
        LOGGER.info("Reading {} from an absolute path: {}", fieldName, resolved);
      }
    } else {
      // A relative path must stay under its base: this is the one field that turns a
      // string into a file read, and a traversal would be rendered straight into a prompt.
      Path base = basePath.toAbsolutePath().normalize();
      resolved = base.resolve(p).normalize();
      if (!resolved.startsWith(base)) {
        throw new IllegalArgumentException(
          "Refusing " + fieldName + " '" + filePath + "': resolves outside " + base
        );
      }
    }
    try {
      String content = Files.readString(resolved, StandardCharsets.UTF_8);
      LOGGER.debug("Loaded {} from '{}' ({} chars)", fieldName, resolved, content.length());
      return content;
    } catch (IOException e) {
      throw new IllegalArgumentException(
        "Failed to read " + fieldName + " '" + resolved + "': " + e.getMessage(),
        e
      );
    }
  }
}
