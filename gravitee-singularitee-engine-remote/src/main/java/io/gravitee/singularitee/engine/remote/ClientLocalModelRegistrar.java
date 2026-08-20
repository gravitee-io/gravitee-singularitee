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

import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.engine.classifier.CompositeClassifierEngine;
import io.gravitee.singularitee.engine.classifier.RegexClassifierEngine;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.workspace.ModelType;
import io.gravitee.singularitee.workspace.WorkspaceDefinition;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.CompositeClassifierDef;
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
 *   <li>Simple engines ({@code regex}) are built and
 *       registered first.</li>
 *   <li>Composite engines ({@code composite_classifier}) are built second,
 *       resolving each of their declared sub-model IDs against the registry
 *       — so composite references to simple engines (and even to other
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
   * the server — both end up in the same {@link ModelRegistry}).
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

    // Pass 1 — simple engines (regex)
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

    // Pass 2 — composites (reference already-registered delegates)
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
              "composite_classifier '{}': delegate '{}' not registered — skipping composite",
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
              "composite_classifier '{}': delegate '{}' is not a ClassifierEngine (type={}) — skipping composite",
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
