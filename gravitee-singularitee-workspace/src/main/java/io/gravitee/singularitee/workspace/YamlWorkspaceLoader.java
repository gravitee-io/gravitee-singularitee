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
package io.gravitee.singularitee.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.workspace.WorkspaceDefinition.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a workspace YAML file and converts it into lists of
 * {@code ModelLoadRequest} messages
 * ready to be dispatched to the server's service layer.
 *
 * <p>Models are translated first (in declaration order) so that pipeline
 * definitions can reference their stable IDs immediately.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class YamlWorkspaceLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(YamlWorkspaceLoader.class);

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  /**
   * Step IDs become Jinja2 identifiers (e.g. {@code {{ pii_guard.output }}})
   * in downstream templates. Jinja2 identifiers must match {@code [A-Za-z_][A-Za-z0-9_]*},
   * so hyphens and other non-identifier characters would produce cryptic parse
   * errors at render time. We fail fast at load time instead.
   */
  private static final java.util.regex.Pattern STEP_ID_PATTERN = java.util.regex.Pattern.compile(
    "^[A-Za-z_][A-Za-z0-9_]*$"
  );

  private YamlWorkspaceLoader() {}

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Parses the workspace YAML at the given path.
   *
   * <p>{@code template_file} references are resolved relative to the
   * YAML file's parent directory.
   *
   * <p>{@code includes} are resolved relative to the YAML file's parent
   * directory, and their contents are merged with the main workspace.
   *
   * @param path path to the {@code .yaml} / {@code .yml} workspace file
   * @return parsed result containing model and pipeline publish requests
   * @throws IOException if the file cannot be read or parsed
   */
  public static WorkspaceRequests load(Path path) throws IOException {
    return load(path, null);
  }

  /**
   * Parses the workspace YAML at the given path, resolving
   * {@code template_file} references against the given templates directory
   * and {@code includes} against the YAML file's parent directory.
   *
   * @param path              path to the {@code .yaml} / {@code .yml} workspace file
   * @param templatesPath     base directory for {@code template_file} resolution;
   *                          when {@code null}, defaults to the YAML file's parent directory
   * @return parsed result containing model and pipeline publish requests
   * @throws IOException if the file cannot be read or parsed
   */
  public static WorkspaceRequests load(Path path, Path templatesPath) throws IOException {
    LOGGER.info("Loading workspace: {}", path.toAbsolutePath());
    WorkspaceDefinition def = YAML_MAPPER.readValue(path.toFile(), WorkspaceDefinition.class);

    // Resolve includes and merge with main workspace
    Path workspaceDir = path.getParent();
    WorkspaceDefinition.WorkspaceRoot mergedRoot = resolveIncludes(def.workspace(), workspaceDir);

    // Override workspace root with merged version
    def = new WorkspaceDefinition(mergedRoot);

    return buildRequests(def, workspaceDir, templatesPath, path.toString());
  }

  /**
   * Parses a workspace YAML from an inline string.
   *
   * <p>Use this when the workspace definition is embedded directly in the endpoint
   * configuration rather than stored as a file on disk.
   *
   * @param yaml the YAML content (the full workspace document)
   * @return parsed result containing model and pipeline publish requests
   * @throws IOException if the content cannot be parsed
   */
  public static WorkspaceRequests loadFromString(String yaml) throws IOException {
    return loadFromString(yaml, null, null);
  }

  /**
   * Parses a workspace YAML from an inline string, resolving any
   * {@code template_file} references relative to the given base path.
   *
   * @param yaml     the YAML content (the full workspace document)
   * @param basePath base directory for relative file references; may be
   *                 {@code null} when no file references are expected
   * @return parsed result containing model and pipeline publish requests
   * @throws IOException if the content cannot be parsed
   */
  public static WorkspaceRequests loadFromString(String yaml, Path basePath) throws IOException {
    return loadFromString(yaml, basePath, null);
  }

  /**
   * Parses a workspace YAML from an inline string, resolving
   * {@code template_file} references against the given templates directory
   * and other file references against the base path.
   *
   * @param yaml              the YAML content (the full workspace document)
   * @param basePath          base directory for general file references; may be
   *                          {@code null} when no file references are expected
   * @param templatesPath     base directory for {@code template_file} resolution;
   *                          when {@code null}, defaults to {@code basePath}
   * @return parsed result containing model and pipeline publish requests
   * @throws IOException if the content cannot be parsed
   */
  public static WorkspaceRequests loadFromString(String yaml, Path basePath, Path templatesPath)
    throws IOException {
    LOGGER.info("Loading workspace from inline YAML string");
    WorkspaceDefinition def = YAML_MAPPER.readValue(yaml, WorkspaceDefinition.class);
    return buildRequests(def, basePath, templatesPath, "<inline>");
  }

  /**
   * Builds {@link WorkspaceRequests} from a pre-assembled {@link WorkspaceDefinition.WorkspaceRoot},
   * bypassing all YAML parsing and file I/O.
   *
   * <p>Used by the APIM gateway plugin when the workspace is constructed programmatically
   * from structured configuration fields rather than read from a YAML file or inline string.
   * Templates are always inline {@code content:} strings in this path — no {@code template_file:}
   * resolution is performed.
   *
   * @param root the fully-assembled workspace root
   * @return parsed result containing model load requests and pipeline definitions
   */
  public static WorkspaceRequests loadFromRoot(WorkspaceDefinition.WorkspaceRoot root) {
    return buildRequests(new WorkspaceDefinition(root), null, null, "<assembled>");
  }

  private static WorkspaceRequests buildRequests(
    WorkspaceDefinition def,
    Path basePath,
    Path templatesPath,
    String source
  ) {
    if (def.workspace() == null) {
      throw new IllegalArgumentException(
        "Workspace file missing top-level 'workspace:' key: " + source
      );
    }

    var root = def.workspace();
    LOGGER.info("Workspace name: {}", root.name());

    // Use the caller-supplied templates path for template_file resolution.
    // When null, fall back to the normal basePath (YAML parent dir).
    Path templatesBasePath = templatesPath != null ? templatesPath : basePath;
    if (templatesPath != null) {
      LOGGER.info("Templates base path: {}", templatesBasePath);
    }

    var modelSplit = parseModels(root);

    // Build template registry: id → resolved content string.
    // Templates declared in the root (or merged from includes) are resolved here
    // once, before pipelines are parsed, so template_id references resolve correctly.
    Map<String, String> templateRegistry = buildTemplateRegistry(root, basePath, templatesBasePath);
    Map<String, TagsDef> tagRegistry = buildTagRegistry(root);

    List<Pipeline> pipelines = parsePipelines(
      root,
      templatesBasePath,
      templateRegistry,
      tagRegistry
    );
    var remotes = parseRemotes(root);

    LOGGER.info(
      "Workspace loaded: {} local model(s), {} remote model(s), {} client-local model(s), {} pipeline(s), {} remote endpoint(s)",
      modelSplit.local().size(),
      modelSplit.remote().size(),
      modelSplit.clientLocal().size(),
      pipelines.size(),
      remotes.size()
    );
    return new WorkspaceRequests(
      root.name(),
      modelSplit.local(),
      pipelines,
      modelSplit.remote(),
      modelSplit.clientLocal(),
      remotes
    );
  }

  /**
   * Parsed workspace result.
   *
   * @param name               workspace name (for logging)
   * @param models             local GPU-bound model load requests, in declaration order
   * @param pipelines          pipeline definitions (proto), in declaration order
   * @param remoteModels       remote model definitions (proxies to another Singularitee)
   * @param clientLocalModels  client-local model definitions (pure-Java, in-process engines)
   * @param remotes            remote endpoint definitions keyed by server ID
   */
  public record WorkspaceRequests(
    String name,
    List<ModelLoadRequest> models,
    List<Pipeline> pipelines,
    List<WorkspaceDefinition.ModelDefinition> remoteModels,
    List<ClientLocalModelData> clientLocalModels,
    java.util.Map<String, WorkspaceDefinition.RemoteEndpoint> remotes
  ) {}

  /**
   * A client-local model definition ({@code regex} or
   * {@code composite_classifier}).
   *
   * <p>Carries the original {@link WorkspaceDefinition.ModelDefinition} for
   * access to common fields ({@code id}, {@code name}, {@code type}, and the
   * type-specific sub-blocks). These model types have no external file
   * references to resolve.
   *
   * @param definition the raw YAML model definition
   */
  public record ClientLocalModelData(WorkspaceDefinition.ModelDefinition definition) {}

  // ---------------------------------------------------------------------------
  // Model parsing
  // ---------------------------------------------------------------------------

  private record ModelSplit(
    List<ModelLoadRequest> local,
    List<WorkspaceDefinition.ModelDefinition> remote,
    List<ClientLocalModelData> clientLocal
  ) {}

  private static ModelSplit parseModels(WorkspaceDefinition.WorkspaceRoot root) {
    if (root.models() == null) return new ModelSplit(List.of(), List.of(), List.of());

    List<ModelLoadRequest> local = new ArrayList<>();
    List<WorkspaceDefinition.ModelDefinition> remote = new ArrayList<>();
    List<ClientLocalModelData> clientLocal = new ArrayList<>();

    for (ModelDefinition m : root.models()) {
      try {
        ModelType modelType = ModelType.parse(m.type() == null ? "" : m.type());
        if (modelType.isRemote()) {
          remote.add(m);
          LOGGER.info(
            "Remote model declared: id='{}', type='{}', server='{}'",
            m.id(),
            m.type(),
            m.server()
          );
        } else if (modelType.isClientLocal()) {
          clientLocal.add(new ClientLocalModelData(m));
          LOGGER.info("Client-local model declared: id='{}', type='{}'", m.id(), m.type());
        } else {
          local.add(toModelLoadRequest(m));
        }
      } catch (Exception e) {
        LOGGER.warn("Skipping model '{}': {}", m.name(), e.getMessage());
      }
    }
    return new ModelSplit(local, remote, clientLocal);
  }

  /** Drops nulls and blanks from a marker list; the first survivor is the primary marker. */
  private static List<String> nonBlank(List<String> markers) {
    return markers == null
      ? List.of()
      : markers
        .stream()
        .filter(t -> t != null && !t.isBlank())
        .toList();
  }

  private static ModelLoadRequest toModelLoadRequest(ModelDefinition m) {
    String modelId = m.id() != null ? m.id() : "";
    String modelName = m.name() != null ? m.name() : "";
    String modelPath = resolveModelPath(m) != null ? resolveModelPath(m) : "";
    MemoryCheckPolicyType policy = MemoryCheckPolicyType.parseType(m.memoryCheck());
    ModelType modelType = ModelType.parse(m.type() == null ? "" : m.type());
    return modelType
      .toModelLoadRequest(modelId, modelName, modelPath, policy, m)
      .withDownloadExclude(downloadExclude(m))
      .withPublication(m.task() == null ? "" : m.task(), m.isVisible());
  }

  /**
   * Reads {@code download.exclude:} off a model definition.
   *
   * <p>Applied here rather than inside each {@link ModelType} branch: the excludes
   * are model-level and mean the same thing for every engine, so one place keeps
   * them from being forgotten when a new model type is added.
   */
  private static List<String> downloadExclude(ModelDefinition m) {
    if (m.download() == null || m.download().exclude() == null) {
      return List.of();
    }
    return m
      .download()
      .exclude()
      .stream()
      .filter(p -> p != null && !p.isBlank())
      .toList();
  }

  /**
   * Resolves the model path from the engine-specific sub-block.
   * Currently only llama_cpp models carry a path (the GGUF filename).
   */
  private static String resolveModelPath(ModelDefinition m) {
    if (m.llamaCpp() != null && m.llamaCpp().path() != null) {
      return m.llamaCpp().path();
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Pipeline parsing
  // ---------------------------------------------------------------------------

  private static List<Pipeline> parsePipelines(
    WorkspaceDefinition.WorkspaceRoot root,
    Path templatesBasePath,
    Map<String, String> templateRegistry,
    Map<String, TagsDef> tagRegistry
  ) {
    if (root.pipelines() == null) return List.of();
    List<Pipeline> result = new ArrayList<>();
    for (PipelineDefinition p : root.pipelines()) {
      try {
        if (p.isRemote()) {
          result.add(toRemotePipelineProxy(p));
          LOGGER.info("Remote pipeline declared: id='{}', server='{}'", p.id(), p.server());
        } else {
          result.add(toPipeline(p, templatesBasePath, templateRegistry, tagRegistry));
        }
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        LOGGER.warn("Skipping pipeline '{}': {}", p.name(), e.getMessage());
      }
    }
    return result;
  }

  private static Pipeline toRemotePipelineProxy(PipelineDefinition p) {
    String stepId = "_remote";

    var subPipelineBuilder = SubPipelineStepConfig.newBuilder()
      .setPipelineId(p.id())
      .setRemoteId(p.server());

    if (p.remote() != null) {
      subPipelineBuilder.setForwardMessages(p.remote().forwardMessages());
      if (p.remote().systemPrompt() != null && !p.remote().systemPrompt().isBlank()) {
        subPipelineBuilder.setSystemPrompt(p.remote().systemPrompt());
      }
    }

    var step = PipelineStep.newBuilder()
      .setStepId(stepId)
      .setType(StepType.STEP_TYPE_SUB_PIPELINE)
      .setSubPipeline(subPipelineBuilder.build())
      .build();

    return Pipeline.newBuilder()
      .setPipelineId(p.id())
      .setPipelineName(p.name() != null ? p.name() : p.id() + " (remote)")
      .setEntryStepId(stepId)
      .addSteps(step)
      .setTask(p.task() == null ? "" : p.task())
      .setHidden(!p.isVisible())
      .build();
  }

  private static Pipeline toPipeline(
    PipelineDefinition p,
    Path templatesBasePath,
    Map<String, String> templateRegistry,
    Map<String, TagsDef> tagRegistry
  ) {
    var pipelineBuilder = Pipeline.newBuilder();

    if (p.id() != null && !p.id().isBlank()) pipelineBuilder.setPipelineId(p.id());
    if (p.name() != null) pipelineBuilder.setPipelineName(p.name());
    if (p.entry() != null) pipelineBuilder.setEntryStepId(p.entry());
    if (p.task() != null && !p.task().isBlank()) pipelineBuilder.setTask(p.task());
    pipelineBuilder.setHidden(!p.isVisible());

    if (p.steps() != null) {
      for (StepDefinition s : p.steps()) {
        pipelineBuilder.addSteps(toStep(s, templatesBasePath, templateRegistry, tagRegistry));
        if (s.nextStep() != null && !s.nextStep().isBlank()) {
          pipelineBuilder.putEdges(s.id(), s.nextStep());
        }
      }
    }

    return pipelineBuilder.build();
  }

  private static PipelineStep toStep(
    StepDefinition s,
    Path templatesBasePath,
    Map<String, String> templateRegistry,
    Map<String, TagsDef> tagRegistry
  ) {
    var b = PipelineStep.newBuilder();
    if (s.id() != null) {
      validateStepId(s.id());
      b.setStepId(s.id());
    }
    if (s.role() != null && !s.role().isBlank()) {
      b.setRole(StepRoleKey.parse(s.role()));
    }

    String typeStr = s.type() == null ? "" : s.type();
    StepTypeKey stepType = StepTypeKey.parse(typeStr);
    b.setType(stepType.getProtoType());

    if (s.config() != null) {
      switch (s.config()) {
        case InferConfig d -> b.setInferConfig(
          toInferStep(d, templatesBasePath, templateRegistry, tagRegistry)
        );
        case ClassifyConfig d -> b.setClassifyConfig(toClassifyStep(d));
        case EmbedConfig d -> b.setEmbedConfig(toEmbedStep(d));
        case RouteConfig d -> b.setRouteConfig(toRouteStep(d));
        case GuardConfig d -> b.setGuardConfig(toGuardStep(d));
        case LlmGuardConfig d -> b.setLlmGuardConfig(
          toLlmGuardStep(d, templatesBasePath, templateRegistry)
        );
        case BreakConfig d -> b.setBreakConfig(toBreakStep(d));
        case LoopConfig d -> b.setLoopConfig(toLoopStep(s, d));
        case SubPipelineConfig d -> b.setSubPipeline(toSubPipelineStep(d));
        case RegexGuardConfig d -> b.setRegexGuardConfig(toRegexGuardStep(d));
        case ToolSelectConfig d -> b.setToolSelectConfig(toToolSelectStep(d));
        case TodoConfig d -> b.setTodoConfig(toTodoStep(d));
      }
    }
    return b.build();
  }

  private static InferStepConfig toInferStep(
    InferConfig d,
    Path basePath,
    Map<String, String> templateRegistry,
    Map<String, TagsDef> tagRegistry
  ) {
    var b = InferStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    if (d.outputField() != null) b.setOutputField(d.outputField());

    // Raw template: template_id > template_file > inline template (mutually exclusive).
    String resolvedTemplate = resolveTemplate(d.prompt(), basePath, templateRegistry);
    if (resolvedTemplate != null && !resolvedTemplate.isBlank()) {
      b.setRawTemplate(resolvedTemplate);
    } else {
      // Messages: step messages only (personas removed)
      List<MessageEntry> messages = d.prompt() != null ? d.prompt().messages() : null;
      if (messages != null) {
        for (var msg : messages) {
          b.addMessages(
            MessageDef.newBuilder()
              .setRole(msg.role() != null ? msg.role() : "user")
              .setContent(msg.content() != null ? msg.content() : "")
              .build()
          );
        }
      }
    }

    // Sampling: from step config only (personas removed)
    SamplingDef sampling = d.sampling();
    var sp = toSamplingParams(sampling);
    b.setSamplingParams(sp);
    if (sampling != null && sampling.stop() != null) {
      b.addAllStop(sampling.stop());
    }

    // Tags section — a bare string value is a reference into the workspace's
    // named `tags:` entries, resolved here so the proto always carries the
    // expanded TagConfig.
    var tags = resolveTags(d.tags(), tagRegistry);
    if (tags != null) {
      var reasoningOpens = nonBlank(tags.reasoningOpen());
      if (!reasoningOpens.isEmpty()) {
        // First entry is the primary open_tag; any others ride along as alternatives.
        var closes = nonBlank(tags.reasoningClose());
        var reasoning = TagConfig.newBuilder()
          .setOpenTag(reasoningOpens.getFirst())
          .setCloseTag(closes.isEmpty() ? "" : closes.getFirst());
        reasoningOpens.stream().skip(1).forEach(reasoning::addOpenTagAlternatives);
        closes.stream().skip(1).forEach(reasoning::addCloseTagAlternatives);
        // Left unset when the workspace is silent, so the engine's own rule applies.
        if (tags.reasoningRepeatable() != null) {
          reasoning.setRepeatable(tags.reasoningRepeatable());
        }
        b.setReasoningTags(reasoning.build());
      }
      var toolOpens = tags.toolOpen() == null
        ? java.util.List.<String>of()
        : tags
          .toolOpen()
          .stream()
          .filter(t -> t != null && !t.isBlank())
          .toList();
      if (!toolOpens.isEmpty()) {
        // First entry is the primary open_tag; any others ride along as alternatives.
        var toolCloses = nonBlank(tags.toolClose());
        var toolTags = TagConfig.newBuilder()
          .setOpenTag(toolOpens.getFirst())
          .setCloseTag(toolCloses.isEmpty() ? "" : toolCloses.getFirst());
        toolOpens.stream().skip(1).forEach(toolTags::addOpenTagAlternatives);
        toolCloses.stream().skip(1).forEach(toolTags::addCloseTagAlternatives);
        b.setToolCallTags(toolTags.build());
      }
    }

    // Per-step context variables (arbitrary typed values for Jinja4j)
    if (d.context() != null && !d.context().isEmpty()) {
      b.setContext(mapToStruct(d.context()));
    }

    // Per-step tool injection toggle. Omitting the key leaves the proto's
    // optional field unset, which the executor treats as "true" (backwards
    // compatible with workspaces written before this field existed).
    if (d.injectTools() != null) {
      b.setInjectTools(d.injectTools());
    }

    if (d.serverTools() != null) {
      b.setExposeServerTools(d.serverTools());
    }

    // Per-step thinking suppression. When true, tokens emitted between the
    // reasoning open/close tags are neither streamed to the client nor stored
    // in the pipeline context. The tag pair is taken from reasoning_tags;
    // when unset the executor defaults to <think>…</think>.
    if (d.stripThinking() != null) {
      b.setStripThinking(d.stripThinking());
    }

    // Live deliberation for internal steps: forward ONLY the thinking channel.
    if (d.streamThinking() != null) {
      b.setStreamThinking(d.streamThinking());
    }

    // One-liner default system prompt — the executor prepends it only when the
    // request carries no system message.
    if (d.system() != null && !d.system().isBlank()) {
      b.setSystemPrompt(d.system());
    }

    // Context-window history trimming. Omitting the key leaves the proto's
    // optional field unset, which the executor treats as "true" (enabled).
    if (d.trimHistory() != null) {
      b.setTrimHistory(d.trimHistory());
    }

    // Tool-call extraction template: built-in name or inline Jinja source.
    if (d.toolExtractionTemplate() != null && !d.toolExtractionTemplate().isBlank()) {
      b.setToolExtractionTemplate(d.toolExtractionTemplate());
    }

    // Per-step chat-template override: a workspace `templates:` id resolves to
    // the registered content; anything else is treated as inline Jinja source.
    if (d.chatTemplate() != null && !d.chatTemplate().isBlank()) {
      b.setChatTemplate(templateRegistry.getOrDefault(d.chatTemplate(), d.chatTemplate()));
    }

    return b.build();
  }

  /** Named tag sets declared at workspace level, keyed by id. */
  private static Map<String, TagsDef> buildTagRegistry(WorkspaceDefinition.WorkspaceRoot root) {
    Map<String, TagsDef> registry = new LinkedHashMap<>();
    if (root.tags() == null) return registry;
    for (var t : root.tags()) {
      if (t == null || t.id() == null || t.id().isBlank()) {
        throw new IllegalArgumentException("workspace tags entries require an id");
      }
      if (registry.put(t.id(), t) != null) {
        throw new IllegalArgumentException("duplicate workspace tags id: " + t.id());
      }
    }
    return registry;
  }

  /** Resolves a reference-only TagsDef (bare string in YAML) against the registry. */
  private static TagsDef resolveTags(TagsDef tags, Map<String, TagsDef> tagRegistry) {
    if (tags == null || !tags.isReference()) {
      return tags;
    }
    var named = tagRegistry.get(tags.id());
    if (named == null) {
      throw new IllegalArgumentException(
        "unknown tags id '" + tags.id() + "' — declare it under workspace tags:"
      );
    }
    return named;
  }

  /** Converts a SamplingDef to a proto SamplingParams (stop tokens excluded — handled separately). */
  private static SamplingParams toSamplingParams(SamplingDef sampling) {
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

  // ---------------------------------------------------------------------------
  // Step config builders
  // ---------------------------------------------------------------------------

  private static ClassifyStepConfig toClassifyStep(ClassifyConfig d) {
    var b = ClassifyStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    if (d.inputField() != null) b.setInputField(d.inputField());
    if (d.outputField() != null) b.setOutputField(d.outputField());
    if (d.threshold() > 0) b.setThreshold(d.threshold());
    return b.build();
  }

  private static TodoStepConfig toTodoStep(TodoConfig d) {
    var b = TodoStepConfig.newBuilder();
    if (d.handledStep() != null && !d.handledStep().isBlank()) {
      b.setHandledStepId(d.handledStep());
    }
    return b.build();
  }

  private static ToolSelectStepConfig toToolSelectStep(ToolSelectConfig d) {
    var b = ToolSelectStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    if (d.inputField() != null) b.setInputField(d.inputField());
    if (d.batchSize() > 0) b.setBatchSize(d.batchSize());
    if (d.threshold() > 0) b.setThreshold(d.threshold());
    if (d.labelTemplate() != null) b.setLabelTemplate(d.labelTemplate());
    if (d.alwaysInclude() != null) b.addAllAlwaysInclude(d.alwaysInclude());
    if (d.trimDescriptions() != null) b.setTrimDescriptions(d.trimDescriptions());
    if (d.descriptionTemplate() != null) b.setDescriptionTemplate(d.descriptionTemplate());
    return b.build();
  }

  private static EmbedStepConfig toEmbedStep(EmbedConfig d) {
    var b = EmbedStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    if (d.inputField() != null) b.setInputField(d.inputField());
    if (d.outputField() != null) b.setOutputField(d.outputField());
    return b.build();
  }

  private static RouteStepConfig toRouteStep(RouteConfig d) {
    var b = RouteStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    if (d.strategy() != null) b.setStrategy(parseRoutingStrategy(d.strategy()));
    if (d.inputField() != null) b.setInputField(d.inputField());
    if (d.defaultStep() != null) b.setDefaultStepId(d.defaultStep());
    if (d.rules() != null) {
      for (var rule : d.rules()) {
        var ruleBuilder = RouteRule.newBuilder()
          .setLabel(rule.label() != null ? rule.label() : "")
          .setNextStepId(rule.nextStep() != null ? rule.nextStep() : "");
        if (rule.sentences() != null && !rule.sentences().isEmpty()) {
          ruleBuilder.addAllSentences(rule.sentences());
        }
        b.addRules(ruleBuilder.build());
      }
    }
    return b.build();
  }

  private static GuardStepConfig toGuardStep(GuardConfig d) {
    var b = GuardStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    if (d.inputField() != null) b.setInputField(d.inputField());
    b.setAction(parseGuardAction(d.action()));
    if (d.outputField() != null && !d.outputField().isBlank()) b.setOutputField(d.outputField());

    // Multi-trigger support: prefer triggers list over single trigger
    if (d.triggers() != null && !d.triggers().isEmpty()) {
      for (var t : d.triggers()) {
        var tb = GuardTrigger.newBuilder();
        if (t.label() != null) tb.setLabel(t.label());
        if (t.score() > 0) tb.setScore(t.score());
        b.addTriggers(tb.build());
      }
    } else if (d.trigger() != null) {
      // Legacy single-trigger fallback — also populate triggers list for uniformity
      var tb = GuardTrigger.newBuilder();
      if (d.trigger().label() != null) tb.setLabel(d.trigger().label());
      if (d.trigger().score() > 0) tb.setScore(d.trigger().score());
      b.addTriggers(tb.build());
      // Also set deprecated fields for backward compat
      if (d.trigger().label() != null) b.setTriggerLabel(d.trigger().label());
      if (d.trigger().score() > 0) b.setTriggerScore(d.trigger().score());
    }
    if (d.message() != null && !d.message().isBlank()) b.setMessage(d.message());
    b.setRedactWithEntityType(d.redactWithEntityType());
    return b.build();
  }

  private static LlmGuardStepConfig toLlmGuardStep(
    LlmGuardConfig d,
    Path basePath,
    Map<String, String> templateRegistry
  ) {
    var b = LlmGuardStepConfig.newBuilder();
    if (d.modelId() != null) b.setModelId(d.modelId());
    b.setAction(parseGuardAction(d.action()));
    if (d.safeToken() != null && !d.safeToken().isBlank()) b.setSafeToken(d.safeToken());
    if (d.message() != null && !d.message().isBlank()) b.setMessage(d.message());

    // Raw template: template_id > template_file > inline template (mutually exclusive).
    String resolvedTemplate = resolveTemplate(d.prompt(), basePath, templateRegistry);
    if (resolvedTemplate != null && !resolvedTemplate.isBlank()) {
      b.setRawTemplate(resolvedTemplate);
    } else {
      // Messages: step messages only (personas removed)
      List<MessageEntry> messages = d.prompt() != null ? d.prompt().messages() : null;
      if (messages != null) {
        for (var msg : messages) {
          b.addMessages(
            MessageDef.newBuilder()
              .setRole(msg.role() != null ? msg.role() : "user")
              .setContent(msg.content() != null ? msg.content() : "")
              .build()
          );
        }
      }
    }

    // Sampling: from step config only (personas removed)
    SamplingDef sampling = d.sampling();
    if (sampling != null) {
      b.setSamplingParams(toSamplingParams(sampling));
    }

    // Per-step template variables (merged into the Jinja2 rendering context
    // by LlmGuardStepExecutor, e.g. `context: { categories: [...] }`).
    if (d.context() != null && !d.context().isEmpty()) {
      b.setContext(mapToStruct(d.context()));
    }

    return b.build();
  }

  private static BreakStepConfig toBreakStep(BreakConfig d) {
    var b = BreakStepConfig.newBuilder();
    if (d.outputField() != null) b.setOutputField(d.outputField());

    // Condition sub-group
    if (d.condition() != null) {
      b.setCondition(parseBreakCondition(d.condition().type()));
      if (d.condition().inputField() != null) b.setInputField(d.condition().inputField());
      if (d.condition().matchValue() != null) b.setMatchValue(d.condition().matchValue());
      if (d.condition().threshold() != 0) b.setThreshold(d.condition().threshold());
    }
    return b.build();
  }

  private static LoopStepConfig toLoopStep(StepDefinition step, LoopConfig d) {
    var b = LoopStepConfig.newBuilder();
    if (d.loopbackStep() != null) b.setTargetStepId(d.loopbackStep());

    // next_step (exit) comes from the top-level step definition
    if (step.nextStep() != null) b.setNextStepId(step.nextStep());

    if (d.maxIterations() > 0) b.setMaxIterations(d.maxIterations());

    if (d.fallbackStep() != null) b.setFallbackStepId(d.fallbackStep());

    // Condition sub-group
    if (d.condition() != null) {
      b.setCondition(parseBreakCondition(d.condition().type()));
      if (d.condition().inputField() != null) b.setInputField(d.condition().inputField());
      if (d.condition().matchValue() != null) b.setMatchValue(d.condition().matchValue());
      if (d.condition().threshold() != 0) b.setThreshold(d.condition().threshold());
    }

    // Optional message injected as a USER (or configured role) turn into
    // pctx.messages() every time the loop branches back to target_step_id.
    // Enables conversational refinement (e.g. CoT). The content supports
    // Jinja2 interpolation and is rendered by LoopStepExecutor at loop-back time.
    if (d.loopbackMessage() != null) {
      var m = d.loopbackMessage();
      b.setLoopbackMessage(
        MessageDef.newBuilder()
          .setRole(m.role() != null && !m.role().isBlank() ? m.role() : "user")
          .setContent(m.content() != null ? m.content() : "")
          .build()
      );
    }

    // Retry-edge sampling override: applied by LoopStepExecutor only when
    // branching back to loopback_step (request override still wins).
    if (d.retrySamplingParams() != null) {
      b.setRetrySamplingParams(toSamplingParams(d.retrySamplingParams()));
    }

    return b.build();
  }

  private static SubPipelineStepConfig toSubPipelineStep(SubPipelineConfig d) {
    var b = SubPipelineStepConfig.newBuilder();
    if (d.pipelineId() != null) b.setPipelineId(d.pipelineId());
    if (d.inputField() != null) b.setInputField(d.inputField());
    if (d.outputField() != null) b.setOutputField(d.outputField());
    if (d.server() != null && !d.server().isBlank()) b.setRemoteId(d.server());
    if (d.systemPrompt() != null && !d.systemPrompt().isBlank()) b.setSystemPrompt(
      d.systemPrompt()
    );
    b.setForwardMessages(d.forwardMessages());
    return b.build();
  }

  private static RegexGuardStepConfig toRegexGuardStep(WorkspaceDefinition.RegexGuardConfig d) {
    var b = RegexGuardStepConfig.newBuilder();
    if (d.inputField() != null) b.setInputField(d.inputField());
    b.setAction(parseGuardAction(d.action()));
    b.setRedactWithEntityType(d.redactWithEntityType());
    if (d.outputField() != null && !d.outputField().isBlank()) b.setOutputField(d.outputField());
    if (d.message() != null && !d.message().isBlank()) b.setMessage(d.message());

    if (d.patterns() != null) {
      for (var e : d.patterns()) {
        b.addPatterns(
          io.gravitee.singularitee.protocol.RegexEntityDef.newBuilder()
            .setName(e.name() != null ? e.name() : "")
            .setPattern(e.pattern() != null ? e.pattern() : "")
            .build()
        );
      }
    }

    return b.build();
  }

  // ---------------------------------------------------------------------------
  // Remote endpoint parsing
  // ---------------------------------------------------------------------------

  private static java.util.Map<String, WorkspaceDefinition.RemoteEndpoint> parseRemotes(
    WorkspaceDefinition.WorkspaceRoot root
  ) {
    var result = new java.util.LinkedHashMap<String, WorkspaceDefinition.RemoteEndpoint>();
    if (root.remote() == null) return result;

    var rc = root.remote();
    if (rc.defaultEndpoint() != null) {
      result.put("default", rc.defaultEndpoint());
    }
    if (rc.servers() != null) {
      for (var ep : rc.servers()) {
        if (ep.id() != null && !ep.id().isBlank()) {
          result.put(ep.id(), ep);
        }
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // File-loading helpers
  // ---------------------------------------------------------------------------

  /**
   * Resolves a {@link PromptDef} to a raw template string.
   *
   * <p>Precedence (mutually exclusive — more than one set is a load-time error):
   * <ol>
   *   <li>{@code template_id} — looks up a named template from the workspace registry.</li>
   *   <li>{@code template_file} — reads the file content (UTF-8) from disk.</li>
   *   <li>{@code template} — returns the inline string as-is.</li>
   *   <li>Otherwise {@code null} is returned (the messages path applies).</li>
   * </ol>
   */
  private static String resolveTemplate(
    PromptDef prompt,
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
        "prompt.template_id, prompt.template_file and prompt.template are mutually exclusive — use only one"
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

  /**
   * Builds an id → content registry from all {@code templates:} declared in the
   * (already-merged) workspace root. Each template's content is resolved from
   * either its inline {@code content:} field or its {@code file:} path.
   */
  private static Map<String, String> buildTemplateRegistry(
    WorkspaceDefinition.WorkspaceRoot root,
    Path workspaceDir,
    Path templatesBasePath
  ) {
    Map<String, String> registry = new LinkedHashMap<>();
    if (root.templates() == null) return registry;
    for (var t : root.templates()) {
      if (t.id() == null || t.id().isBlank()) {
        LOGGER.warn("Skipping template with missing id");
        continue;
      }
      boolean hasContent = t.content() != null && !t.content().isBlank();
      boolean hasFile = t.file() != null && !t.file().isBlank();
      if (hasContent && hasFile) {
        throw new IllegalArgumentException(
          "Template '" + t.id() + "': content and file are mutually exclusive — use only one"
        );
      }
      if (!hasContent && !hasFile) {
        LOGGER.warn("Template '{}' has neither content nor file — skipped", t.id());
        continue;
      }
      // Workspace-declared templates resolve relative to the WORKSPACE file first
      // (a workspace should be self-contained); the configured templates base is
      // the fallback for shared/distribution templates.
      String content = hasFile
        ? readFileContent(
          t.file(),
          resolveTemplateBase(t.file(), workspaceDir, templatesBasePath),
          "template file"
        )
        : t.content();
      registry.put(t.id(), content);
      LOGGER.debug("Registered template '{}'", t.id());
    }
    if (!registry.isEmpty()) {
      LOGGER.info("Template registry built: {} template(s)", registry.size());
    }
    return registry;
  }

  /**
   * Reads a UTF-8 text file, resolving its path relative to {@code basePath}
   * when the path is not absolute. Throws {@link IllegalArgumentException} if
   * the file cannot be read.
   */

  /** Prefers the workspace directory when the relative file exists there. */
  private static Path resolveTemplateBase(String file, Path workspaceDir, Path templatesBasePath) {
    Path p = Path.of(file);
    if (p.isAbsolute() || workspaceDir == null) {
      return templatesBasePath;
    }
    return Files.exists(workspaceDir.resolve(p)) ? workspaceDir : templatesBasePath;
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
      // A RELATIVE path must stay under its base. Workspace YAML is operator-owned
      // today, but this is the one field that turns a string into a file read, and
      // '../../etc/passwd' would otherwise be rendered straight into a prompt.
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

  // ---------------------------------------------------------------------------
  // Enum helpers
  // ---------------------------------------------------------------------------

  private static RoutingStrategy parseRoutingStrategy(String value) {
    if (value == null) return RoutingStrategy.ROUTING_STRATEGY_CLASSIFIER;
    return switch (value.toLowerCase()) {
      case "embedding_knn" -> RoutingStrategy.ROUTING_STRATEGY_EMBEDDING_KNN;
      case "llm_structured" -> RoutingStrategy.ROUTING_STRATEGY_LLM_STRUCTURED;
      default -> RoutingStrategy.ROUTING_STRATEGY_CLASSIFIER;
    };
  }

  private static GuardAction parseGuardAction(String value) {
    if (value == null) return GuardAction.GUARD_ACTION_REJECT;
    return switch (value.toLowerCase()) {
      case "redact" -> GuardAction.GUARD_ACTION_REDACT;
      case "warn" -> GuardAction.GUARD_ACTION_WARN;
      default -> GuardAction.GUARD_ACTION_REJECT;
    };
  }

  private static BreakCondition parseBreakCondition(String value) {
    if (value == null) return BreakCondition.BREAK_CONDITION_UNSPECIFIED;
    return switch (value.toLowerCase()) {
      case "equals" -> BreakCondition.BREAK_CONDITION_EQUALS;
      case "contains" -> BreakCondition.BREAK_CONDITION_CONTAINS;
      case "label_equals" -> BreakCondition.BREAK_CONDITION_LABEL_EQUALS;
      case "score_above" -> BreakCondition.BREAK_CONDITION_SCORE_ABOVE;
      case "score_below" -> BreakCondition.BREAK_CONDITION_SCORE_BELOW;
      case "not_empty" -> BreakCondition.BREAK_CONDITION_NOT_EMPTY;
      case "empty" -> BreakCondition.BREAK_CONDITION_EMPTY;
      default -> BreakCondition.BREAK_CONDITION_UNSPECIFIED;
    };
  }

  /**
   * Converts a Java {@code Map<String, Object>} to a protobuf {@code Struct}.
   * Supports String, Number, Boolean, List, and nested Map values.
   */
  @SuppressWarnings("unchecked")
  private static com.google.protobuf.Struct mapToStruct(java.util.Map<String, Object> map) {
    var builder = com.google.protobuf.Struct.newBuilder();
    for (var entry : map.entrySet()) {
      builder.putFields(entry.getKey(), toProtoValue(entry.getValue()));
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static com.google.protobuf.Value toProtoValue(Object obj) {
    if (obj == null) {
      return com.google.protobuf.Value.newBuilder()
        .setNullValue(com.google.protobuf.NullValue.NULL_VALUE)
        .build();
    }
    if (obj instanceof String s) {
      return com.google.protobuf.Value.newBuilder().setStringValue(s).build();
    }
    if (obj instanceof Boolean b) {
      return com.google.protobuf.Value.newBuilder().setBoolValue(b).build();
    }
    if (obj instanceof Number n) {
      return com.google.protobuf.Value.newBuilder().setNumberValue(n.doubleValue()).build();
    }
    if (obj instanceof java.util.Map<?, ?> m) {
      return com.google.protobuf.Value.newBuilder()
        .setStructValue(mapToStruct((java.util.Map<String, Object>) m))
        .build();
    }
    if (obj instanceof java.util.List<?> list) {
      var listBuilder = com.google.protobuf.ListValue.newBuilder();
      for (Object item : list) {
        listBuilder.addValues(toProtoValue(item));
      }
      return com.google.protobuf.Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    // Fallback: toString
    return com.google.protobuf.Value.newBuilder().setStringValue(obj.toString()).build();
  }

  /**
   * Resolves typed include directives and merges them with the main workspace.
   *
   * <p>Each sub-key ({@code models:}, {@code pipelines:}, {@code templates:}) lists
   * file names (or glob patterns) resolved relative to the hardcoded subfolder that
   * matches the keyword:
   * <ul>
   *   <li>{@code models:}    → {@code {workspaceDir}/models/}</li>
   *   <li>{@code pipelines:} → {@code {workspaceDir}/pipelines/}</li>
   *   <li>{@code templates:} → {@code {workspaceDir}/templates/}</li>
   * </ul>
   * Only the section matching the key is extracted from each file — a file listed
   * under {@code models:} contributes only its {@code workspace.models} list, and so on.
   *
   * <p>Glob patterns (e.g. {@code *.yaml}) are expanded alphabetically (deterministic ordering).
   */
  private static WorkspaceDefinition.WorkspaceRoot resolveIncludes(
    WorkspaceDefinition.WorkspaceRoot mainRoot,
    Path baseDir
  ) throws IOException {
    if (mainRoot.includes() == null) {
      return mainRoot;
    }

    var inc = mainRoot.includes();
    boolean hasAny =
      (inc.models() != null && !inc.models().isEmpty()) ||
      (inc.pipelines() != null && !inc.pipelines().isEmpty()) ||
      (inc.templates() != null && !inc.templates().isEmpty());
    if (!hasAny) {
      return mainRoot;
    }

    List<ModelDefinition> models = new ArrayList<>(
      mainRoot.models() != null ? mainRoot.models() : List.of()
    );
    List<PipelineDefinition> pipelines = new ArrayList<>(
      mainRoot.pipelines() != null ? mainRoot.pipelines() : List.of()
    );
    List<WorkspaceDefinition.TemplateDefinition> templates = new ArrayList<>(
      mainRoot.templates() != null ? mainRoot.templates() : List.of()
    );
    RemoteConfig remote = mainRoot.remote();
    String name = mainRoot.name();

    Path modelsDir = baseDir.resolve("models");
    Path pipelinesDir = baseDir.resolve("pipelines");
    Path templatesDir = baseDir.resolve("templates");

    // ── models ────────────────────────────────────────────────────────────
    if (inc.models() != null) {
      for (String pattern : inc.models()) {
        for (Path file : expandGlob(modelsDir, pattern)) {
          WorkspaceDefinition.WorkspaceRoot root = loadIncludeRoot(file);
          if (root == null) continue;
          if (root.models() != null) {
            models.addAll(root.models());
            LOGGER.info("Added {} model(s) from {}", root.models().size(), file.getFileName());
          }
          if (remote == null && root.remote() != null) remote = root.remote();
          if ((name == null || name.isBlank()) && root.name() != null && !root.name().isBlank()) {
            name = root.name();
          }
        }
      }
    }

    // ── pipelines ─────────────────────────────────────────────────────────
    if (inc.pipelines() != null) {
      for (String pattern : inc.pipelines()) {
        for (Path file : expandGlob(pipelinesDir, pattern)) {
          WorkspaceDefinition.WorkspaceRoot root = loadIncludeRoot(file);
          if (root == null) continue;
          if (root.pipelines() != null) {
            pipelines.addAll(root.pipelines());
            LOGGER.info(
              "Added {} pipeline(s) from {}",
              root.pipelines().size(),
              file.getFileName()
            );
          }
        }
      }
    }

    // ── templates ─────────────────────────────────────────────────────────
    if (inc.templates() != null) {
      for (String pattern : inc.templates()) {
        for (Path file : expandGlob(templatesDir, pattern)) {
          WorkspaceDefinition.WorkspaceRoot root = loadIncludeRoot(file);
          if (root == null) continue;
          if (root.templates() != null) {
            templates.addAll(root.templates());
            LOGGER.info(
              "Added {} template(s) from {}",
              root.templates().size(),
              file.getFileName()
            );
          }
        }
      }
    }

    return new WorkspaceDefinition.WorkspaceRoot(
      name,
      remote,
      models.isEmpty() ? null : models,
      pipelines.isEmpty() ? null : pipelines,
      templates.isEmpty() ? null : templates,
      mainRoot.tags(), // named tag sets come from the base file only (not merged from includes)
      null // clear includes — no recursive processing
    );
  }

  /**
   * Loads a single include file and returns its {@code workspace} root, or
   * {@code null} (with a warning) if the file is missing or malformed.
   */
  private static WorkspaceDefinition.WorkspaceRoot loadIncludeRoot(Path file) throws IOException {
    if (!Files.exists(file)) {
      LOGGER.warn("Include file not found: {}", file.toAbsolutePath());
      return null;
    }
    LOGGER.info("Loading include file: {}", file.toAbsolutePath());
    WorkspaceDefinition def = YAML_MAPPER.readValue(file.toFile(), WorkspaceDefinition.class);
    if (def.workspace() == null) {
      LOGGER.warn("Include file {} missing top-level 'workspace:' key — skipped", file);
      return null;
    }
    return def.workspace();
  }

  /**
   * Expands a glob pattern relative to {@code baseDir} and returns matching paths
   * sorted alphabetically (deterministic ordering across machines/runs).
   *
   * <p>If the pattern contains no wildcard characters it is treated as a literal
   * path (no directory listing required).
   */
  private static List<Path> expandGlob(Path baseDir, String pattern) throws IOException {
    if (pattern == null || pattern.isBlank()) return List.of();

    boolean hasWildcard = pattern.contains("*") || pattern.contains("?") || pattern.contains("{");
    if (!hasWildcard) {
      // Literal path — return as single-element list (existence checked by caller)
      return List.of(baseDir.resolve(pattern));
    }

    // Separate directory prefix from glob segment so we open the right dir
    int lastSlash = pattern.lastIndexOf('/');
    Path searchDir = lastSlash >= 0 ? baseDir.resolve(pattern.substring(0, lastSlash)) : baseDir;
    String globSegment = lastSlash >= 0 ? pattern.substring(lastSlash + 1) : pattern;

    if (!Files.isDirectory(searchDir)) {
      LOGGER.warn("Include glob directory not found: {}", searchDir.toAbsolutePath());
      return List.of();
    }

    List<Path> results = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(searchDir, globSegment)) {
      for (Path p : stream) {
        if (Files.isRegularFile(p)) results.add(p);
      }
    }
    results.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
    return results;
  }

  /**
   * Fails fast when a step ID cannot be used as a Jinja2 identifier.
   * Step outputs are exposed in the rendering context under a key derived from
   * the step ID (e.g. {@code {{ my_step.output }}}); hyphens, dots and other
   * non-identifier characters would produce hard-to-debug template parse errors.
   */
  private static void validateStepId(String stepId) {
    if (stepId == null || stepId.isBlank()) return;
    if (!STEP_ID_PATTERN.matcher(stepId).matches()) {
      throw new IllegalArgumentException(
        "Invalid step id '" +
          stepId +
          "': must match [A-Za-z_][A-Za-z0-9_]* so it can be referenced as a Jinja2 identifier " +
          "(e.g. '{{ " +
          stepId +
          ".output }}'). Replace hyphens with underscores."
      );
    }
  }
}
