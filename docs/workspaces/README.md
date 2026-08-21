# Workspaces

> The YAML document that declares what a server publishes: models, pipelines, templates, tag sets, remote endpoints and includes, and how the loader validates it.

## Overview

A workspace is one YAML file with a single root key, `workspace:`. It declares the models to
load, the pipelines that run over them, reusable templates and tag sets, and optional remote
gRPC endpoints. `ai.workspace.path` names the file the server loads at boot.

Models carry a stable logical id (`llm`, `pii`, `toxicity`, `router`) while `name` points at
the source, usually a HuggingFace repo. Pipelines reference the id, so the same pipeline runs
against any backend a workspace binds to that id. Larger setups compose a workspace from
fragments with `includes:`, resolved from `models/`, `pipelines/` and `templates/` folders
beside the file.

Per-type and per-step keys are documented once, in the reference:
[model types](../reference/models/README.md), [step types](../reference/steps/README.md),
[templates](../reference/templates/README.md). This page covers the document itself.

## Key types

- `WorkspaceDefinition` / `WorkspaceRoot`: the Jackson mapping. `@JsonProperty` names are the YAML keys; unknown keys are ignored.
- `YamlWorkspaceLoader`: parses the file, resolves includes and globs, builds the template and tag registries, validates ids and publication metadata, and produces model-load requests and pipeline definitions.
- `ModelDefinition`: one `models:` entry; `PipelineDefinition` and `StepDefinition`: one `pipelines:` entry and its steps; `TemplateDefinition`: one `templates:` entry; `TagsDef`: one `tags:` entry.
- `RemoteConfig` / `RemoteEndpoint`: the `remote:` block.
- `ModelType`: every `type:` string (parsed case-insensitively) and its mapping to a proto `ModelLoadRequest`.
- `Publication`: the closed sets for `task:` and `modalities:`.
- `MemoryCheckPolicyType`: `fail`, `warn`, `disabled`.

## Usage

A minimal server workspace:

```yaml
workspace:
  name: my-workspace
  models:
    - id: llm                       # logical id: what pipelines reference
      name: Qwen/Qwen3-0.6B-GGUF    # HuggingFace repo, or a local path
      type: llama_cpp               # selects the llama_cpp: block below
      memory_check: warn
      llama_cpp:
        path: Qwen3-0.6B-Q8_0.gguf
        n_ctx: 4096
        n_seq_max: 1
        n_gpu_layers: 999
  pipelines:
    - id: agent
      entry: generate               # first step
      steps:
        - id: generate
          type: infer
          role: output              # this step's output is the response
          config:
            model_id: llm
            output_field: generate.output
```

Composition from fragments (`examples/modular/server-llamacpp.yaml`):

```yaml
workspace:
  name: server-llamacpp
  includes:
    models:
      - llama/llm-qwen3-0.6b.yaml   # resolved in ./models/; subfolders allowed
    templates:
      - tool-system.yaml            # resolved in ./templates/
    pipelines:                      # resolved in ./pipelines/; globs allowed
      - infer.yaml
      - tool-calling.yaml
```

A client workspace whose models live on other servers (adapted from `examples/modular/client-safety-llamacpp.yaml`, with a default endpoint, TLS and credentials added to show every key):

```yaml
workspace:
  name: client
  remote:
    default:                        # used by entries with no server:
      host: 127.0.0.1
      port: 9090
    servers:
      - id: safety
        host: 127.0.0.1
        port: 9092
        ssl: true                   # TLS; trust and client cert come from grpc.client.ssl.*
        username: pii               # optional Basic auth sent as gRPC metadata
        password: <password>
  models:
    - id: llm
      type: remote_llm              # default endpoint
    - id: pii
      type: remote_classifier
      server: safety                # named endpoint
```

Validate a workspace without starting a server: `task test:examples` runs
`ExamplesWorkspaceTest`, which loads every file under `examples/` through the real loader.
Point the same loader at your own file with `YamlWorkspaceLoader.load(path, templatesPath)`.

## Options

### Root keys

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `workspace.name` | string | unset | Informational; logged at load. |
| `workspace.remote` | object | unset | `default:` endpoint and/or `servers:` list. See below. |
| `workspace.models` | list | `[]` | Model entries. |
| `workspace.pipelines` | list | `[]` | Pipeline entries. |
| `workspace.templates` | list | `[]` | Named Jinja templates. |
| `workspace.tags` | list | `[]` | Named tag sets an `infer` step references by id as its whole `tags:` value. Base file only; not merged from includes. |
| `workspace.includes` | object | unset | `models:`, `pipelines:`, `templates:` lists of file names or globs. |

### `remote` endpoints

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `remote.default` | endpoint | unset | Endpoint for `remote_*` entries that set no `server:`. |
| `remote.servers[]` | list of endpoints | `[]` | Named endpoints. |
| `endpoint.id` | string | unset | Name referenced by `server:` (not needed on `default`). |
| `endpoint.host` / `endpoint.port` | string / int | unset | gRPC address. |
| `endpoint.ssl` | bool | `false` | Reach the endpoint over TLS. Trust material and any client certificate come from `grpc.client.ssl.*` in `gravitee.yml`. |
| `endpoint.username` / `endpoint.password` | string | unset | HTTP Basic credentials sent as gRPC metadata. Pair them with `ssl: true` off loopback. |
| `endpoint.http2_keep_alive_timeout` | int | `-1` | Seconds an idle HTTP/2 connection is held; `-1` keeps it open indefinitely. |

### Common model keys

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | Logical id pipelines reference. |
| `name` | string | unset | HuggingFace repo or local path the files come from. |
| `type` | string | required | One of the [model types](../reference/models/README.md); case-insensitive. Selects which config block is read. |
| `<type>` | object | unset | The block named after the type (`llama_cpp:`, `vllm:`, `onnx_classifier:`, ...). Keys per type are in the reference. |
| `server` | string | `default` endpoint | Endpoint id for `remote_*` types. |
| `memory_check` | string | `warn` | Pre-load memory check: `fail` aborts the load, `warn` logs, `disabled` skips. Unknown values fall back to `warn`. |
| `download.exclude` | list of globs | `[]` | Repository files to skip where a resolver picks files out of a listing (`vllm`, `gliner_*`, the sibling listings of `onnx_*`). Never drops a file named outright (`path`, `model_path`, `tokenizer_path`). |
| `task` | string | engine's own | Published task slug. See Publication. |
| `visible` | bool | `true` | Catalogue membership. See Publication. |
| `modalities` | list | detected | Accepted input modalities. See Publication. |

### Pipeline keys

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | Pipeline id; what clients call. |
| `name` | string | unset | Display name. |
| `entry` | string | required | Id of the first step. |
| `steps[]` | list | required | Step entries. |
| `task` / `visible` / `modalities` | | inherited / `true` / union | Publication, as for models. A pipeline's default `task` is the one of the model behind its `role: output` step (falling back to the entry step); its default `modalities` are the union over its model-bound steps. |
| `server` | string | unset | Makes the entry a proxy for a pipeline of the same id on that endpoint; no local `steps`. |
| `remote.system_prompt` | string | unset | Proxy only: system prompt prepended on the remote call. |
| `remote.forward_messages` | bool | `false` | Proxy only: forward the chat messages (system prompt and history) instead of the flat prompt string. |

### Step keys

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | Must match `[A-Za-z_][A-Za-z0-9_]*`: it becomes a Jinja identifier (`{{ generate.output }}`). Hyphens are rejected. |
| `type` | string | required | One of the [step types](../reference/steps/README.md). Selects the shape of `config:`. |
| `role` | string | `output` | `output`: the step's output is the response; `thinking`: reasoning-only; `internal`: neither. Unset parses as `output`. |
| `next_step` | string | unset | Successor for linear chains; routing steps carry their own edges in `config`. |
| `config` | object | required | Step configuration; keys per type are in the reference. |

### Templates

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | Referenced by a step's `prompt.template_id` or `chat_template`. (`tool_extraction_template` takes a built-in name or inline source, not a template id.) |
| `content` | string | unset | Inline Jinja source. |
| `file` | string | unset | Path to a `.jinja` or `.jinja2` file, relative to the workspace file's directory. Exactly one of `content` or `file`. |

Templates are materialised at load time; the engine only ever sees the resolved source.
Built-in templates and the variables available are in [Templates](../reference/templates/README.md).

### Includes

| Key | Resolved in | Purpose |
| --- | --- | --- |
| `includes.models[]` | `<workspace dir>/models/` | Each file contributes only its `workspace.models`. |
| `includes.pipelines[]` | `<workspace dir>/pipelines/` | Each file contributes only its `workspace.pipelines`. |
| `includes.templates[]` | `<workspace dir>/templates/` | Each file contributes only its `workspace.templates`. |

Entries are file names or globs (`llama/*.yaml`), expanded alphabetically. Subfolders are
allowed (`classifier/pii-bert.yaml`). Includes are not recursive: an included file's own
`includes:` is ignored. A glob whose directory does not exist logs a warning and contributes
nothing.

Because ids are logical, several fragments may share one (`examples/modular/models/llama/llm-*.yaml`
and `vllm/llm-*.yaml` all publish `llm`). A server lists the one it wants explicitly rather
than globbing a folder that holds several.

### Publication

`task`, `visible` and `modalities` work the same on a model and on a pipeline.

- `task` is the slug `/v1/models` and `ListModels` advertise: `text-generation`, `text-classification`, `token-classification`, `feature-extraction`, `reranking`. Any other value fails the load. Unset, a model reports its engine's task and a pipeline inherits from its output step. Nothing is ever advertised as `pipeline`; a multimodal generation model stays `text-generation`.
- `visible: false` removes the entry from the listings, from lookup by id and from HTTP resolution (`model_not_found`). It stays callable as a pipeline's model, as a sub-pipeline and over gRPC from another server. Publish the pipeline, hide its parts. `http.expose-pipelines: false` hides every pipeline at once; `visible` is the per-entry switch.
- `modalities` lists what the entry accepts: `text`, `image`, `audio`. Unset, it is detected: llama.cpp asks the loaded projector, vLLM reads `vision_config` / `audio_config` from the checkpoint's `config.json`, a `remote_llm` reads its `GetModel` probe, a pipeline takes the union over its model-bound steps. Declare it only where detection cannot run (a `remote_*` proxy, a vLLM model whose weights were never resolved locally). The HTTP API refuses media the target cannot read with `400 unsupported_modality`. Any other slug fails the load.

## Notes

**Load-time errors.** These stop the workspace with an `IllegalArgumentException` naming the offending entry:

| Error | Trigger |
| --- | --- |
| `Workspace file missing top-level 'workspace:' key` | No `workspace:` root. |
| `Unknown model type '...'` | `type:` is not a `ModelType`. The model is skipped with a WARN, not fatal. |
| `'<id>' declares unknown task '...'` / `unknown modality '...'` | `task:` or `modalities:` outside the closed sets. |
| `Invalid step id '...'` | A step id that is not a valid Jinja identifier. |
| `prompt.template_id, prompt.template_file and prompt.template are mutually exclusive` | More than one prompt source on a step. |
| `prompt.template_id '...' not found in workspace templates` | Reference to an undeclared template. |
| `Template '<id>': content and file are mutually exclusive` | Both set on a template. Neither set logs a WARN and skips it. |
| `workspace tags entries require an id` / `duplicate workspace tags id` / `unknown tags id '...'` | Malformed or dangling tag-set references. |
| `Refusing <field> '...': resolves outside <dir>` | A `file:` or `template_file:` path escaping the workspace directory. |

**Load failures are not fatal.** A model whose files cannot be resolved (wrong repo, wrong
`path`) is logged as `failed to load` by `WorkspaceLoaderComponent` and the server comes up
without it. A pipeline whose model is missing fails to register the same way. An inert
server with `/v1/models` empty almost always means one of these lines is in the log.

**Structure is validated, weights are not.** `ExamplesWorkspaceTest` (and `task test:examples`)
parses and resolves every `examples/**.yaml`, including the modular fragments, through the
real loader. It catches a wrong step type, a dangling `template_id`, a bad `task` slug. It
never downloads weights, so a wrong HuggingFace repo name passes there and fails at boot.

**Unknown keys are ignored.** A misspelt key does not error; it is silently dropped. When an
option seems to have no effect, check its spelling against the reference page.

**Numeric zero means engine default.** Omitted numeric fields parse as `0` and are not sent
to the engine; a numeric option cannot be set to `0` explicitly. Most booleans are forwarded
only when `true`; the three-valued ones (`enable_prefix_caching`, `enable_sleep_mode`,
`offload_kqv`, `use_mlock`, `prompt_cache`) distinguish unset from `false`.

**Remote pipeline proxies.** A pipeline with `server:` and no `steps` forwards `InferPipeline`
to a pipeline of the same id on that endpoint. See
[Remote and multi-server](../guides/remote-and-multi-server/README.md).

## See also

- [Concepts](../concepts/README.md): what a model, pipeline and template are.
- [Pipelines](../concepts/pipelines/README.md): the DAG, roles, `entry` and `next_step`.
- [Model types](../reference/models/README.md) and [step types](../reference/steps/README.md): every key per block.
- [Templates](../reference/templates/README.md): built-ins, variables, `chat_template` and `tool_extraction_template`.
- [Context fields](../reference/context-fields.md): what steps read and write.
- [Getting started](../getting-started/README.md): pointing the server at a workspace.
- [examples/](../../examples/README.md): every runnable workspace, including `modular/`.
