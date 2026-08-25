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
import io.gravitee.singularitee.engine.api.pipeline.model.BranchingConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.MonitorGateConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
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
 * Loads a workspace YAML file and converts it into {@link ModelLoadRequest}s and proto
 * {@link Pipeline}s ready for the server's service layer.
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
   * Step IDs become Jinja identifiers (e.g. {@code {{ pii_guard.output }}})
   * in downstream templates. Jinja identifiers must match {@code [A-Za-z_][A-Za-z0-9_]*},
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
   * Parses the workspace YAML at the given path. {@code codecs} maps each step type string
   * to the codec that parses that step's {@code config:} block: the codecs of the step
   * plugins the caller's plugin registry assembled (the server's node registry, the
   * client's own, or a test suite's).
   */
  public static WorkspaceRequests load(
    Path path,
    Path templatesPath,
    Map<String, StepConfigCodec<?>> codecs
  ) throws IOException {
    LOGGER.info("Loading workspace: {}", path.toAbsolutePath());
    WorkspaceDefinition def = YAML_MAPPER.readValue(path.toFile(), WorkspaceDefinition.class);

    // Resolve includes and merge with main workspace
    Path workspaceDir = path.getParent();
    WorkspaceDefinition.WorkspaceRoot mergedRoot = resolveIncludes(def.workspace(), workspaceDir);

    // Override workspace root with merged version
    def = new WorkspaceDefinition(mergedRoot);

    return buildRequests(def, workspaceDir, templatesPath, path.toString(), codecs);
  }

  /** Inline-string variant of {@link #load(Path, Path, Map)}. */
  public static WorkspaceRequests loadFromString(
    String yaml,
    Path basePath,
    Path templatesPath,
    Map<String, StepConfigCodec<?>> codecs
  ) throws IOException {
    LOGGER.info("Loading workspace from inline YAML string");
    WorkspaceDefinition def = YAML_MAPPER.readValue(yaml, WorkspaceDefinition.class);
    return buildRequests(def, basePath, templatesPath, "<inline>", codecs);
  }

  private static WorkspaceRequests buildRequests(
    WorkspaceDefinition def,
    Path basePath,
    Path templatesPath,
    String source,
    Map<String, StepConfigCodec<?>> codecs
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

    // Build template registry: id to resolved content string.
    // Templates declared in the root (or merged from includes) are resolved here
    // once, before pipelines are parsed, so template_id references resolve correctly.
    Map<String, String> templateRegistry = buildTemplateRegistry(root, basePath, templatesBasePath);

    // Named workspace sections travel to the codecs as raw maps: a codec is plugin code and
    // knows nothing of this module's records. Building the typed registries first keeps
    // their validation (ids required, no duplicates).
    Map<String, Map<String, Map<String, Object>>> namedSections = new LinkedHashMap<>();
    namedSections.put("tags", rawSection(buildTagRegistry(root)));
    namedSections.putAll(rawExtraSections(root.extras()));
    var codecContext = new StepCodecContext(
      templateRegistry,
      templatesBasePath,
      namedSections,
      null
    );

    List<PipelineModel> pipelines = parsePipelines(root, codecContext, codecs);
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
    List<PipelineModel> pipelines,
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
      // Outside the try: a bad task or modality slug fails the workspace rather than
      // quietly dropping the model, since the value is what callers route on.
      Publication.validatedTask(m.id(), m.task());
      Publication.validatedModalities(m.id(), m.modalities());
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
      .withPublication(
        Publication.validatedTask(m.id(), m.task()),
        m.isVisible(),
        Publication.validatedModalities(m.id(), m.modalities())
      );
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

  private static List<PipelineModel> parsePipelines(
    WorkspaceDefinition.WorkspaceRoot root,
    StepCodecContext ctx,
    Map<String, StepConfigCodec<?>> codecs
  ) {
    if (root.pipelines() == null) return List.of();
    List<PipelineModel> result = new ArrayList<>();
    for (PipelineDefinition p : root.pipelines()) {
      try {
        if (p.isRemote()) {
          result.add(toRemotePipelineProxy(p, ctx, codecs));
          LOGGER.info("Remote pipeline declared: id='{}', server='{}'", p.id(), p.server());
        } else {
          result.add(toPipeline(p, ctx, codecs));
        }
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        LOGGER.warn("Skipping pipeline '{}': {}", p.name(), e.getMessage());
      }
    }
    return result;
  }

  /**
   * A remote pipeline is a one-step local pipeline whose sub_pipeline step executes the
   * named pipeline on the remote server; its config is built through the sub_pipeline
   * codec like any other step, so the loader knows nothing of that step's shape.
   */
  private static PipelineModel toRemotePipelineProxy(
    PipelineDefinition p,
    StepCodecContext ctx,
    Map<String, StepConfigCodec<?>> codecs
  ) {
    String stepId = "_remote";
    StepConfigCodec<?> codec = codecs.get(StepTypes.SUB_PIPELINE);
    if (codec == null) {
      throw new IllegalArgumentException(
        "Remote pipeline '" + p.id() + "': no plugin provides step type 'sub_pipeline'"
      );
    }
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("pipeline_id", p.id());
    raw.put("server", p.server());
    if (p.remote() != null) {
      raw.put("forward_messages", p.remote().forwardMessages());
      if (p.remote().systemPrompt() != null && !p.remote().systemPrompt().isBlank()) {
        raw.put("system_prompt", p.remote().systemPrompt());
      }
    }
    var step = new StepModel(
      stepId,
      StepTypes.SUB_PIPELINE,
      StepRole.STEP_ROLE_UNSPECIFIED,
      codec.parse(stepId, raw, ctx)
    );

    return new PipelineModel(
      p.id(),
      p.name() != null ? p.name() : p.id() + " (remote)",
      stepId,
      List.of(step),
      Map.of(),
      Publication.validatedTask(p.id(), p.task()),
      !p.isVisible(),
      Publication.validatedModalities(p.id(), p.modalities())
    );
  }

  private static PipelineModel toPipeline(
    PipelineDefinition p,
    StepCodecContext ctx,
    Map<String, StepConfigCodec<?>> codecs
  ) {
    List<StepModel> steps = new ArrayList<>();
    Map<String, String> edges = new LinkedHashMap<>();
    if (p.steps() != null) {
      for (StepDefinition s : p.steps()) {
        steps.add(toStep(s, ctx, codecs));
        if (s.nextStep() != null && !s.nextStep().isBlank()) {
          edges.put(s.id(), s.nextStep());
        }
      }
    }

    PipelineModel pipeline = new PipelineModel(
      p.id(),
      p.name(),
      p.entry(),
      steps,
      edges,
      Publication.validatedTask(p.id(), p.task()),
      !p.isVisible(),
      Publication.validatedModalities(p.id(), p.modalities())
    );
    validateMonitorGates(pipeline);
    return pipeline;
  }

  private static StepModel toStep(
    StepDefinition s,
    StepCodecContext ctx,
    Map<String, StepConfigCodec<?>> codecs
  ) {
    if (s.id() != null) {
      validateStepId(s.id());
    }
    StepRole role = s.role() != null && !s.role().isBlank()
      ? StepRoleKey.parse(s.role())
      : StepRole.STEP_ROLE_UNSPECIFIED;

    String type = s.type() == null ? "" : s.type().trim().toLowerCase(java.util.Locale.ENGLISH);
    if (type.isBlank()) {
      throw new IllegalArgumentException("Step '" + s.id() + "' requires a type");
    }
    StepConfigCodec<?> codec = codecs.get(type);
    if (codec == null) {
      throw new IllegalArgumentException(
        "Step '" +
          s.id() +
          "': no plugin provides step type '" +
          type +
          "'. Available: " +
          new java.util.TreeSet<>(codecs.keySet())
      );
    }

    // The envelope's next_step is visible to the codec as "next_step": a loop reads its
    // exit edge from it. Records ignore unknown keys, so other codecs are unaffected.
    Map<String, Object> raw = new LinkedHashMap<>();
    if (s.config() != null) raw.putAll(s.config());
    if (s.nextStep() != null && !s.nextStep().isBlank()) {
      raw.putIfAbsent(NEXT_STEP_KEY, s.nextStep());
    }
    Object config = codec.parse(s.id(), raw, ctx);

    return new StepModel(s.id(), type, role, config);
  }

  /** Key under which a codec sees the step envelope's {@code next_step}. */
  public static final String NEXT_STEP_KEY = "next_step";

  /** A typed registry as the raw maps a codec consumes. */
  private static <T> Map<String, Map<String, Object>> rawSection(Map<String, T> registry) {
    Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
    for (var e : registry.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> value = YAML_MAPPER.convertValue(e.getValue(), Map.class);
      raw.put(e.getKey(), value);
    }
    return raw;
  }

  /** Named tag sets declared at workspace level, keyed by id. */
  static Map<String, TagsDef> buildTagRegistry(WorkspaceDefinition.WorkspaceRoot root) {
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

  /**
   * Turns unmodeled top-level sections (workspace {@code extras}) into raw named sections a
   * step codec reads through {@link StepCodecContext#section}. A section is recognised only when
   * its value is a list whose every entry is a non-blank {@code id}-bearing map; anything else
   * (a scalar, a map, a list of non-{@code id} entries) is left untouched so an unrelated or
   * mistyped top-level key does not fail the load. The module never interprets the entries, so a
   * step plugin owns its own section schema; a malformed real section surfaces as an empty
   * {@link StepCodecContext#section} and the plugin's codec reports the domain error.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Map<String, Map<String, Object>>> rawExtraSections(
    Map<String, Object> extras
  ) {
    Map<String, Map<String, Map<String, Object>>> sections = new LinkedHashMap<>();
    if (extras == null) return sections;
    for (var section : extras.entrySet()) {
      if (!(section.getValue() instanceof List<?> entries) || entries.isEmpty()) {
        continue;
      }
      boolean allIdMaps = entries
        .stream()
        .allMatch(
          e -> e instanceof Map<?, ?> m && m.get("id") instanceof String id && !id.isBlank()
        );
      if (!allIdMaps) {
        continue;
      }
      Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
      for (Object entry : entries) {
        Map<String, Object> map = (Map<String, Object>) entry;
        String id = (String) map.get("id");
        if (byId.put(id, map) != null) {
          throw new IllegalArgumentException(
            "duplicate workspace " + section.getKey() + " id: " + id
          );
        }
      }
      sections.put(section.getKey(), byId);
    }
    return sections;
  }

  /**
   * Enforces the monitor_only contract at load time: a step that gates an action on a
   * downstream monitor (a {@link MonitorGateConfig}) must have a
   * guard or llm_guard step reachable from it, otherwise the monitor tier would silently
   * mean "unmonitored". Reachability follows the plain edges plus every branch target a step
   * config declares.
   */
  private static void validateMonitorGates(PipelineModel pipeline) {
    Map<String, StepModel> byId = new LinkedHashMap<>();
    for (StepModel s : pipeline.steps()) {
      byId.put(s.id(), s);
    }
    for (StepModel s : pipeline.steps()) {
      boolean hasMonitorTier =
        s.config() instanceof MonitorGateConfig gate && gate.requiresMonitor();
      if (!hasMonitorTier) continue;

      var visited = new java.util.HashSet<String>();
      var queue = new java.util.ArrayDeque<>(successors(pipeline, s));
      boolean monitored = false;
      while (!queue.isEmpty() && !monitored) {
        String next = queue.poll();
        if (next == null || next.isBlank() || !visited.add(next)) continue;
        StepModel step = byId.get(next);
        if (step == null) continue;
        if (StepTypes.GUARD.equals(step.type()) || StepTypes.LLM_GUARD.equals(step.type())) {
          monitored = true;
        } else {
          queue.addAll(successors(pipeline, step));
        }
      }
      if (!monitored) {
        throw new IllegalArgumentException(
          "Pipeline '" +
            pipeline.id() +
            "': step '" +
            s.id() +
            "' gates an action on a monitor but no guard or llm_guard step is reachable " +
            "after it. Add a monitor step on that branch or raise the gate."
        );
      }
    }
  }

  /** Every step id execution can branch to from the given step. */
  private static List<String> successors(PipelineModel pipeline, StepModel s) {
    List<String> next = new ArrayList<>();
    String edge = pipeline.edges().get(s.id());
    if (edge != null) next.add(edge);
    if (s.config() instanceof BranchingConfig branching) {
      next.addAll(branching.branchTargets());
    }
    return next;
  }

  // ---------------------------------------------------------------------------
  // Step config builders
  // ---------------------------------------------------------------------------

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
   * Builds an id to content registry from all {@code templates:} declared in the
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
          "Template '" + t.id() + "': content and file are mutually exclusive, use only one"
        );
      }
      if (!hasContent && !hasFile) {
        LOGGER.warn("Template '{}' has neither content nor file, skipped", t.id());
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

  /** Prefers the workspace directory when the relative file exists there. */
  private static Path resolveTemplateBase(String file, Path workspaceDir, Path templatesBasePath) {
    Path p = Path.of(file);
    if (p.isAbsolute() || workspaceDir == null) {
      return templatesBasePath;
    }
    return Files.exists(workspaceDir.resolve(p)) ? workspaceDir : templatesBasePath;
  }

  /**
   * Reads a UTF-8 text file, resolving a relative path under {@code basePath}.
   *
   * @throws IllegalArgumentException if the file cannot be read or escapes {@code basePath}
   */
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

  /**
   * Resolves typed include directives and merges them with the main workspace.
   *
   * <p>Each sub-key ({@code models:}, {@code pipelines:}, {@code templates:}) lists
   * file names (or glob patterns) resolved relative to the hardcoded subfolder that
   * matches the keyword:
   * <ul>
   *   <li>{@code models:} resolves under {@code {workspaceDir}/models/}</li>
   *   <li>{@code pipelines:} resolves under {@code {workspaceDir}/pipelines/}</li>
   *   <li>{@code templates:} resolves under {@code {workspaceDir}/templates/}</li>
   * </ul>
   * Only the section matching the key is extracted from each file: a file listed
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
      null, // clear includes: no recursive processing
      mainRoot.extras() // plugin-owned sections come from the base file only
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
      LOGGER.warn("Include file {} missing top-level 'workspace:' key, skipped", file);
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
      // Literal path: single-element list, existence checked by the caller.
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
   * Fails fast when a step ID cannot be used as a Jinja identifier.
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
          "': must match [A-Za-z_][A-Za-z0-9_]* so it can be referenced as a Jinja identifier " +
          "(e.g. '{{ " +
          stepId +
          ".output }}'). Replace hyphens with underscores."
      );
    }
  }
}
