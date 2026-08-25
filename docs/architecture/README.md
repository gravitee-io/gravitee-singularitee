# Architecture

> How Singularitee is put together, how a request executes, and how a pipeline runs. Setup lives in [Getting Started](../getting-started/README.md); every key and option lives in the [reference](../README.md#reference).

## Overview

Singularitee is a [gravitee-node](https://github.com/gravitee-io/gravitee-node) container (Spring 6, Vert.x 5, RxJava 3). It hosts models in-process through engine factories and exposes them through two fronts over the same registries:

- the **gRPC API** (primary, port 9090): four services over Vert.x's HTTP/2 server;
- the **HTTP API** (opt-in, port 8080): an OpenAI-compatible JSON API on a second Vert.x server.

A pipeline is a directed graph of steps that the engine walks reactively. The same pipeline definition can run on the server, or on a client that proxies each model call back over gRPC.

| Layer | Technology |
| --- | --- |
| Node, lifecycle, config | gravitee-node (`gravitee.yml`, `GRAVITEE_*` env vars, `-D` properties) |
| Runtime | Vert.x 5 event loop; model loading on worker threads |
| Transport | gRPC over HTTP/2 with TLS (PEM/JKS, SNI, mTLS) and Basic auth; HTTP/JSON with SSE streaming and Bearer auth |
| Reactive | RxJava 3: `Maybe<String>` for DAG traversal, `Single<T>` for unary RPCs, `Flowable<T>` for token streams |

## Modules

| Module (`gravitee-singularitee-*`) | Role |
| --- | --- |
| `protocol` | `.proto` contract and generated Vert.x gRPC stubs. Source of truth for the wire. |
| `engine-api` | The contracts a step plugin compiles against: the step SPI (`StepExecutor`, `StepConfigCodec`), the pipeline/step model, `PipelineContext`/`StepContext`, the engine model interfaces, the registries and template rendering. |
| `engine` | The runtime that wires and executes those contracts: `StepExecutorFactory`, `StepDispatcher` (the decorator chain), the platform decorators, `PipelineExecutor` (reactive DAG walker) and `ConversationStore`. Depends on `engine-api`; holds no step executors (those are plugins). |
| `plugin-api` | The step plugin SPI (`StepExecutorPlugin`, `StepDecoratorPlugin`, `StepExecutorServices`) and the plugin assembly with its license gate. |
| `plugins` | One module per core step: the twelve OSS steps, always shipped. Further steps can live in their own plugin repositories, loaded the same way. |
| `engine-remote` | `Remote*Engine` proxies for `remote_*` model types and `ClientPipelineExecutor`. |
| `inference` | Vendored engines (`-api`, `-llama-cpp`, `-vllm`, `-onnx`, `-math`) behind `EngineAdapter` and `AbstractBatchEngine`. |
| `workspace` | `YamlWorkspaceLoader`: YAML to model load requests and the Java pipeline model; step configs are parsed by the plugins' codecs. `ModelType` lives here. |
| `grpc` | gRPC service implementations, engine-adapter factories, HuggingFace resolvers. |
| `http` | OpenAI-compatible HTTP API. Pure translation, no inference logic. |
| `client` | `SingulariteeClient`: thin gRPC client, the only dependency a caller needs. |
| `standalone` | `bootstrap` (classloader entry point), `container` (`SingulariteeNode`, Spring config, server components), `distribution` (assembly). |

## Boot order

`GrpcServerComponent` and `HttpApiServerComponent` bind their ports before `WorkspaceLoaderComponent` loads the workspace, so `/health` answers immediately while weights download and load. Until loading completes, a shared `ReadinessState` gates inference: gRPC calls fail with `UNAVAILABLE` and the HTTP API returns `503 model_not_ready`. A model that fails to load is logged at WARN and skipped; the server stays up.

Before any of that, the node's boot phase bootstraps its `PluginRegistry` (it scans
`plugins/` for plugin zips) and reads the platform license (`license.key`, OSS when absent);
the main context then assembles the step executors from those plugins, gating each on its
manifest `feature`, and creates the dispatcher only once every core step type has an executor.
Two failures deliberately do not follow the WARN-and-continue rule: a misassembled plugin set
(two providers for a type, or a missing core step) fails at boot, and a workspace declaring a
step whose license feature is missing fails the workspace load with the feature named, so the
node never reports ready with a safety step silently absent.

## Execution modes

| Mode | Invocation | Where the DAG runs | Where models live |
| --- | --- | --- | --- |
| Server-side pipeline | `InferPipeline` RPC, or HTTP with a pipeline id as `model` | Server | Server |
| Direct model | `Infer`, `Classify`, `Embed`, `Rank` RPCs, or HTTP with a model id | n/a | Server |
| Client-side pipeline | `ClientPipelineExecutor` with a workspace declaring `remote:` endpoints | Client process | One or more remote servers |

In client-side mode the workspace declares `remote_*` models bound to named endpoints. `ClientPipelineExecutor` walks the DAG locally and each model call travels over gRPC to the server that hosts it. A multi-server client points different models at different endpoints inside one DAG (`examples/modular/client-safety-vllm.yaml`).

## How pipelines work

A pipeline is an `entry` step plus a list of `steps`. Linear steps follow their `next_step` edge; routing, looping, guard and todo steps name their own targets. Every step reads and writes a shared `PipelineContext`: the `prompt`, the running `messages` list, per-step outputs (`<step_id>.output`), diagnostic fields, and usage. A step that yields no next step is terminal.

**Termination** (`finish_reason`): normal exhaustion gives `STOP`; a `break` or `loop` condition gives `BREAK_CONDITION`; a guard rejection gives `GUARD_BLOCKED`. `CANCELLED` (client disconnect or slow consumer) and `STALLED` (backend decode failure) stay distinct on gRPC. Over HTTP these are mapped onto the closed OpenAI set: `GUARD_BLOCKED` becomes `content_filter` with HTTP 400, `CANCELLED` and `STALLED` render as `stop`.

**Diagnostics as context fields**: each step publishes what happened (`<step>.finish_reason`, `<step>.tool_parse_ok`, `<step>.label`, `<step>.iterations`, ...), so loop and break conditions can drive self-repair. The full catalogue is in [Context fields](../reference/context-fields.md).

**Infer roles** shape the conversation: `output` appends the response as an assistant turn; `thinking` keeps reasoning out of history; `internal` holds grader or router verdicts that never reach the client.

### Step types

| Type | Purpose | Reference |
| --- | --- | --- |
| `infer` | Stream text generation from a text-generation model | [infer](../reference/steps/infer.md) |
| `classify` | Classifier label and score into the context | [classify](../reference/steps/classify.md) |
| `embed` | Embedding vector into the context | [embed](../reference/steps/embed.md) |
| `route` | Dispatch to a named step by classifier label, embedding similarity or LLM answer | [route](../reference/steps/route.md) |
| `guard` | Classifier-based input guard: reject, warn or redact | [guard](../reference/steps/guard.md) |
| `llm_guard` | LLM-as-judge guard: safe iff the first token equals `safe_token` | [llm_guard](../reference/steps/llm_guard.md) |
| `regex_guard` | Model-free pattern guard with entity redaction | [regex_guard](../reference/steps/regex_guard.md) |
| `loop` | Bounded back-edge with an exit condition | [loop](../reference/steps/loop.md) |
| `break` | Conditional early halt returning a field | [break](../reference/steps/break.md) |
| `sub_pipeline` | Invoke another pipeline, locally or on a remote server | [sub_pipeline](../reference/steps/sub_pipeline.md) |
| `tool_select` | Shortlist the caller's tools with a zero-shot classifier | [tool_select](../reference/steps/tool_select.md) |
| `todo` | Execute server-owned plan tools and stream progress | [todo](../reference/steps/todo.md) |

Semantics of guards, routing, loops and sub-pipelines are in the [guides](../README.md#guides).

## Plugins

Every pipeline step executes from a gravitee plugin: a zip under `${gravitee.home}/plugins`
whose jar carries a `plugin.properties` (`type=step`, `class=` the `StepExecutorPlugin`
implementation, and an optional `feature=` when the step requires a license feature). The node's `PluginRegistry` scans the
directory at boot; gravitee's `PluginClassLoader` gives each plugin a `URLClassLoader` whose
parent is the container classloader, delegating parent-first: engine APIs, models and native
libraries (llama.cpp, ONNX Runtime, the embedded CPython of vLLM) always resolve from the main
classloader. This is not a style choice: those natives are process-global singletons, and a
second classloader touching them means an `UnsatisfiedLinkError` or a doubly initialised
interpreter. A plugin is therefore a decorator over parent-loaded APIs and nothing else, and
its jar carries only its own extra runtime deps (engine, protocol and plugin-api are
`provided`).

A `StepExecutorPlugin` contributes a step type, the `StepConfigCodec` that parses the step's
YAML `config:` block, and the `StepExecutor` built from the parent-owned
`StepExecutorServices`. Cross-cutting behaviour comes from `StepDecoratorPlugin`s
(`type=step-decorator`), applied to every step inside the platform decorators (tracing,
diagnostics), so a plugin step is always observed. `StepPlugins.assemble` turns the plugins
the registry discovered into the engine's executors and codecs. Rules enforced at boot: two
providers for one type is a misassembly and fails; a core type without an executor fails
("core step types without an executor"); a step can be switched off with
`step.<id>.enabled: false`; and a step whose manifest
`feature` the platform license does not enable stays unregistered, so a workspace declaring it fails to load with the feature
named (node licensing, the same `LicenseManager` the rest of Gravitee uses). Steps never cross
the wire: the pipeline DAG is a Java model built from YAML, and the proto only carries pipeline
metadata.

## Workspaces

```yaml
workspace:
  name: <string>
  remote:                         # client-side or multi-server only
    default: { host, port, username?, password? }
    servers: [ { id, host, port, username?, password? } ]
  models:    [ ... ]              # type + <type>: block; task?, visible?, modalities?
  pipelines: [ ... ]              # entry + steps; task?, visible?, modalities?
  templates: [ { id, content | file } ]
  includes:                       # pull shared definitions from sibling folders
    models:    [ "*.yaml" ]       # ./models/
    pipelines: [ "*.yaml" ]       # ./pipelines/
    templates: [ "*.yaml" ]       # ./templates/
```

Models carry a stable **logical id** (`llm`, `pii`, `router`), so several model files can share an id and a server includes exactly one: the same pipeline then runs unchanged on any backend.

**Publication.** `task` is the slug advertised on `/v1/models` (`text-generation`, `text-classification`, `token-classification`, `feature-extraction`, `reranking`); a model defaults to what its engine reports, a pipeline to the model behind its `role: output` step. `visible: false` hides an entry from listings and HTTP resolution while leaving it callable as a dependency and over gRPC. `modalities` (`text`, `image`, `audio`) is detected from the loaded model, not declared; a pipeline accepts the union of its model-bound steps, and the HTTP layer refuses media the target cannot read with `unsupported_modality`.

Model types, step keys and templates are each documented in the [reference](../README.md#reference); the format end to end is in [Workspaces](../workspaces/README.md).

## Remote and multi-server

`remote_*` model types resolve to `Remote*Engine` proxies wrapping a `SingulariteeClient` (with optional Basic credentials from the endpoint). A `sub_pipeline` step with a `server` delegates the whole nested pipeline to another server through `SingulariteeClient.inferPipeline()`, so servers compose recursively.

Run **one model (or one engine) per process**. llama.cpp, vLLM and ONNX Runtime each load their own native libraries; co-locating them in one JVM invites library conflicts and GPU-memory contention. Compose across processes over gRPC; `examples/modular/` shows the pattern.

## HTTP API

Module `gravitee-singularitee-http`, lifecycle `HttpApiServerComponent`, enabled by `http.enabled`. It shares the registries and engines with gRPC and adds no inference logic: it translates JSON to proto, drives the same local services, and so inherits metrics, tracing and cancel-on-disconnect.

- Endpoints: `POST /v1/chat/completions`, `/v1/completions`, `/v1/responses`, `/v1/embeddings`, `/v1/classify`, `/v1/rerank`, `/v1/similarity`; `GET /v1/models`, `/v1/models/{id}`. Each is also served without the `/v1` prefix.
- Streaming: SSE with a terminal `[DONE]`, back-pressure coupling the token `Flowable` to the HTTP write queue; a disconnect cancels the sequence or pipeline.
- Resolution: `model` names a text-generation model or a pipeline; `pipeline:` forces the pipeline, otherwise a model wins and a bare pipeline id is the fallback. Unknown and hidden ids both give `model_not_found`.
- Validation and errors: lenient JSON-schema validation (required, type, enum enforced; unknown fields accepted), optional Bearer auth, OpenAI error envelope.

Conventions and endpoint details: [HTTP API](../api/http/README.md); schemas: [openapi/](../../openapi/README.md).

## Observability

Opt-in through `services.metrics` and `services.opentelemetry`.

- Tracing: a SERVER span per gRPC call (continuing an inbound W3C `traceparent`) with nested `ai.pipeline`, `ai.step` and `ai.model.<op>` spans; direct RPCs get `ai.infer`, `ai.classify`, `ai.embed`. Client-side execution is not traced.
- Metrics (Micrometer, Prometheus at `/_node/metrics/prometheus`): `ai_infer_requests_total`, `ai_infer_latency_seconds`, `ai_pipeline_requests_total`, `ai_pipeline_latency_seconds`, `ai_classify_requests_total`, `ai_embed_requests_total`, `ai_model_call_seconds{model,op}`, `ai_tokens_total{model,kind}`.

Details: [Observability](../operations/observability/README.md).
