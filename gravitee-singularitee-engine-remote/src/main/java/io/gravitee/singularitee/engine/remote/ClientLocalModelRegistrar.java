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
package io.gravitee.singularitee.engine.remote;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.ConfidenceSignal;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.ConfidenceSignals;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.classifier.CompositeClassifierEngine;
import io.gravitee.singularitee.engine.classifier.LlmSpans;
import io.gravitee.singularitee.engine.classifier.LlmTokenClassifierEngine;
import io.gravitee.singularitee.engine.classifier.RegexClassifierEngine;
import io.gravitee.singularitee.workspace.ModelType;
import io.gravitee.singularitee.workspace.WorkspaceDefinition;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.CompositeClassifierDef;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.LlmAnswerDef;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.LlmTokenClassifierDef;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.ModelDefinition;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.RegexDef;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.RegexPatternDef;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader.ClientLocalModelData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers client-local models (pure-Java {@link ClassifierEngine}s such as
 * stop words, regex, or composite) with a {@link ModelRegistry}.
 *
 * <p>Used by both the standalone server (via {@code WorkspaceLoaderComponent})
 * and the client-side pipeline executor ({@code ClientPipelineExecutor}) so
 * that client-local models are available on both sides of the deployment.
 *
 * <p>Registration happens in two passes:
 * <ol>
 *   <li>Simple engines ({@code regex}, and {@code llm_token_classifier} over a
 *       text-generation model registered earlier) are built and registered first.</li>
 *   <li>Composite engines ({@code composite_classifier}) are built second,
 *       resolving each of their declared sub-model IDs against the registry
 *       so composite references to simple engines (and even to other
 *       composites declared earlier) always succeed.</li>
 * </ol>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ClientLocalModelRegistrar {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClientLocalModelRegistrar.class);

  /**
   * Callback used to hand a freshly-built engine to the caller's preferred
   * registration mechanism (direct {@code ModelRegistry.register(...)} on the
   * client, {@code GraviteeModelServiceImpl.registerPrebuiltModel(...)} on
   * the server; both end up in the same {@link ModelRegistry}).
   */
  @FunctionalInterface
  public interface Registrar {
    /**
     * Registers the engine with its publication metadata and returns the resolved
     * model ID.
     *
     * @param task       declared task slug, or blank to defer to the engine
     * @param visible    whether the model joins the public catalogue
     * @param modalities declared input modalities, or empty to defer to the engine
     */
    String register(
      String modelId,
      String modelName,
      ModelEngine engine,
      String task,
      boolean visible,
      List<String> modalities
    );
  }

  static final int DEFAULT_LLM_MAX_TOKENS = 1024;
  static final int DEFAULT_LLM_CHUNK_TOKENS = 1024;
  static final int DEFAULT_LLM_REPEAT_MIN_LENGTH = 4;
  /** Span score signal when {@code score} is unset; {@value #NO_LLM_SCORE} turns scoring off. */
  static final String DEFAULT_LLM_SCORE = "max_entropy";
  static final String NO_LLM_SCORE = "none";

  private ClientLocalModelRegistrar() {}

  /**
   * Registers every client-local model in the given list.
   *
   * <p>Simple engines are registered first, composites second. If a composite
   * references a model ID that is not registered at the time of composite
   * construction, or references a model that is not a {@link ClassifierEngine},
   * the composite is skipped with a warning.
   *
   * @param models   client-local model definitions from the workspace YAML
   * @param registry the registry used to resolve composite delegates
   * @param registrar the callback that actually registers the built engine
   */
  public static void register(
    List<ClientLocalModelData> models,
    ModelRegistry registry,
    Registrar registrar
  ) {
    if (models == null || models.isEmpty()) return;

    // Pass 1: simple engines (regex, llm_token_classifier)
    List<ClientLocalModelData> composites = new ArrayList<>();
    for (ClientLocalModelData data : models) {
      ModelDefinition def = data.definition();
      ModelType type;
      try {
        type = ModelType.parse(def.type());
      } catch (Exception e) {
        LOGGER.warn("Skipping client-local model '{}': {}", def.id(), e.getMessage());
        continue;
      }

      try {
        switch (type) {
          case REGEX -> {
            RegexDef cfg = def.regex();
            List<RegexClassifierEngine.PatternEntry> entries = cfg != null && cfg.patterns() != null
              ? cfg
                .patterns()
                .stream()
                .map(ClientLocalModelRegistrar::toPatternEntry)
                .filter(java.util.Objects::nonNull)
                .toList()
              : List.of();
            var engine = new RegexClassifierEngine(entries);
            registrar.register(
              def.id(),
              displayName(def),
              engine,
              task(def),
              def.isVisible(),
              modalities(def)
            );
            LOGGER.info(
              "Client-local model registered: id='{}', type='regex', patterns={}",
              def.id(),
              entries.size()
            );
          }
          case LLM_TOKEN_CLASSIFIER -> registerLlmTokenClassifier(def, registry, registrar);
          case COMPOSITE_CLASSIFIER -> composites.add(data);
          default -> LOGGER.warn(
            "Skipping client-local model '{}': unexpected type '{}'",
            def.id(),
            type
          );
        }
      } catch (Exception e) {
        LOGGER.warn("Failed to register client-local model '{}': {}", def.id(), e.getMessage(), e);
      }
    }

    // Pass 2: composites (reference already-registered delegates)
    for (ClientLocalModelData data : composites) {
      ModelDefinition def = data.definition();
      try {
        CompositeClassifierDef cfg = def.compositeClassifier();
        if (cfg == null || cfg.models() == null || cfg.models().isEmpty()) {
          LOGGER.warn("Skipping composite_classifier '{}': no delegate models declared", def.id());
          continue;
        }

        List<ClassifierEngine> delegates = new ArrayList<>(cfg.models().size());
        boolean resolved = true;
        for (String delegateId : cfg.models()) {
          Optional<ModelRegistry.ModelEntry> entry = registry.get(delegateId);
          if (entry.isEmpty()) {
            LOGGER.warn(
              "composite_classifier '{}': delegate '{}' not registered, skipping composite",
              def.id(),
              delegateId
            );
            resolved = false;
            break;
          }
          if (entry.get().engine() instanceof ClassifierEngine ce) {
            delegates.add(ce);
          } else {
            LOGGER.warn(
              "composite_classifier '{}': delegate '{}' is not a ClassifierEngine (type={}), skipping composite",
              def.id(),
              delegateId,
              entry.get().engine().type()
            );
            resolved = false;
            break;
          }
        }

        if (!resolved) continue;

        var engine = new CompositeClassifierEngine(delegates);
        registrar.register(
          def.id(),
          displayName(def),
          engine,
          task(def),
          def.isVisible(),
          modalities(def)
        );
        LOGGER.info(
          "Client-local model registered: id='{}', type='composite_classifier', delegates={}",
          def.id(),
          cfg.models()
        );
      } catch (Exception e) {
        LOGGER.warn(
          "Failed to register composite_classifier '{}': {}",
          def.id(),
          e.getMessage(),
          e
        );
      }
    }
  }

  /**
   * Builds an {@code llm_token_classifier} over a text-generation model already in the registry.
   * Local and remote models register before any client-local one, so the reference resolves
   * here; one that does not, or that names a non-generating model, skips the classifier with a
   * WARN.
   */
  private static void registerLlmTokenClassifier(
    ModelDefinition def,
    ModelRegistry registry,
    Registrar registrar
  ) {
    LlmTokenClassifierDef cfg = def.llmTokenClassifier();
    if (cfg == null || cfg.modelId() == null || cfg.modelId().isBlank()) {
      LOGGER.warn(
        "Skipping llm_token_classifier '{}': llm_token_classifier.model_id is required",
        def.id()
      );
      return;
    }
    Optional<ModelRegistry.ModelEntry> entry = registry.get(cfg.modelId());
    if (entry.isEmpty()) {
      LOGGER.warn(
        "llm_token_classifier '{}': model '{}' not registered, skipping classifier",
        def.id(),
        cfg.modelId()
      );
      return;
    }
    if (!(entry.get().engine() instanceof TextGenEngine llm)) {
      LOGGER.warn(
        "llm_token_classifier '{}': model '{}' is not a text-generation model (type={}), skipping classifier",
        def.id(),
        cfg.modelId(),
        entry.get().engine().type()
      );
      return;
    }
    String scoreName = cfg.score() == null || cfg.score().isBlank()
      ? DEFAULT_LLM_SCORE
      : cfg.score().strip();
    ConfidenceSignal score = null;
    if (!NO_LLM_SCORE.equals(scoreName)) {
      score = ConfidenceSignals.byField(scoreName)
        .filter(signal -> signal.confidence(0.0).isPresent())
        .orElse(null);
      if (score == null) {
        LOGGER.warn(
          "llm_token_classifier '{}': unknown score '{}' (one of {}, or {}), skipping classifier",
          def.id(),
          scoreName,
          scoreSignals(),
          NO_LLM_SCORE
        );
        return;
      }
    }
    LlmSpans.Format format;
    try {
      format = answerFormat(cfg.answer());
    } catch (IllegalArgumentException e) {
      LOGGER.warn(
        "llm_token_classifier '{}': invalid answer block ({}), skipping classifier",
        def.id(),
        e.getMessage()
      );
      return;
    }
    var options = new LlmTokenClassifierEngine.Options(
      cfg.systemPrompt(),
      cfg.labels(),
      positiveOr(cfg.maxTokens(), DEFAULT_LLM_MAX_TOKENS),
      cfg.context(),
      cfg.chunkTokens() != null ? cfg.chunkTokens() : DEFAULT_LLM_CHUNK_TOKENS,
      cfg.repeatMinLength() != null ? cfg.repeatMinLength() : DEFAULT_LLM_REPEAT_MIN_LENGTH,
      score,
      format,
      cfg.structuredOutput() == null || cfg.structuredOutput()
    );
    var engine = new LlmTokenClassifierEngine(def.id(), llm, entry.get()::nextSequenceId, options);
    registrar.register(
      def.id(),
      displayName(def),
      engine,
      task(def),
      def.isVisible(),
      modalities(def)
    );
    LOGGER.info(
      "Client-local model registered: id='{}', type='llm_token_classifier', model='{}', labels={}, score={}, structured_output={}",
      def.id(),
      cfg.modelId(),
      options.labels().isEmpty() ? "any" : options.labels().size(),
      score == null ? "none" : score.field(),
      options.structuredOutput()
    );
  }

  /** The answer format of an {@code answer:} block, each unset key at its default. */
  static LlmSpans.Format answerFormat(LlmAnswerDef answer) {
    var d = LlmSpans.Format.DEFAULT;
    if (answer == null) return d;
    return new LlmSpans.Format(
      answer.spans() != null ? answer.spans() : d.spans(),
      answer.label() != null ? answer.label() : d.label(),
      answer.text() != null ? answer.text() : d.text(),
      answer.metadata()
    );
  }

  /** The signals a span score can name: those with a reading as a confidence. */
  private static List<String> scoreSignals() {
    return ConfidenceSignals.all()
      .stream()
      .filter(signal -> signal.confidence(0.0).isPresent())
      .map(ConfidenceSignal::field)
      .toList();
  }

  private static int positiveOr(Integer value, int fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private static RegexClassifierEngine.PatternEntry toPatternEntry(RegexPatternDef def) {
    if (def == null || def.pattern() == null || def.pattern().isBlank()) return null;
    String entityType = def.entityType() != null && !def.entityType().isBlank()
      ? def.entityType()
      : "MATCH";
    return new RegexClassifierEngine.PatternEntry(def.pattern(), entityType);
  }

  private static String task(WorkspaceDefinition.ModelDefinition def) {
    return def.task() != null ? def.task() : "";
  }

  private static List<String> modalities(WorkspaceDefinition.ModelDefinition def) {
    return def.modalities() != null ? def.modalities() : List.of();
  }

  private static String displayName(WorkspaceDefinition.ModelDefinition def) {
    return def.name() != null && !def.name().isBlank() ? def.name() : def.id();
  }
}
