# Architecture

How Gravitee Singularitee is put together, how requests execute, and how pipelines work. For setup and configuration, see the [README](README.md).

## Overview

Singularitee is a [gravitee-node](https://github.com/gravitee-io/gravitee-node) container (Spring 6 + Vert.x 5, fully reactive on RxJava 3). It exposes four gRPC services over Vert.x's native HTTP/2 server — and, optionally, a native OpenAI-compatible HTTP/JSON API on a second Vert.x server backed by the same registries and engines (see [OpenAI-compatible HTTP API](#openai-compatible-http-api)) — and hosts models in-process through the engine factories. A pipeline is a directed graph of steps that the engine walks reactively; the same pipeline can run entirely on the server, or on a client that proxies each model call back over gRPC.

| Layer | Technology |
|-------|------------|
| Node / lifecycle / config | gravitee-node 9.0.0 (`gravitee.yml`, `GRAVITEE_`/`-D` overrides) |
| Runtime | Vert.x 5 event loop; model loading on worker threads |
| Transport | gRPC over HTTP/2; TLS (PEM/JKS, SNI, mTLS), HTTP Basic auth. Optional OpenAI-compatible HTTP/JSON API (SSE streaming, Bearer auth) on a separate port |
| Reactive | RxJava 3 — `Maybe<String>` for DAG traversal, `Single<T>` for unary RPCs, `Flowable<T>` for token streams |

## Modules

| Module (`gravitee-singularitee-*`) | Role |
|--------|------|
| `protocol` | `.proto` contract + generated Vert.x gRPC stubs (messages, engine configs, step/pipeline defs). |
| `engine` | Core pipeline logic: `ModelRegistry`, `PipelineRegistry`, `PipelineExecutor` (reactive DAG walker), step executors. |
| `engine-remote` | Remote proxy engines (`RemoteTextGenEngine`, `RemoteClassifierEngine`, `RemoteEmbeddingEngine`) + `ClientPipelineExecutor`. |
| `inference` | Vendored engine implementations (llama.cpp, vLLM, ONNX, GLiNER) behind a unified `BatchEngine`. |
| `workspace` | `YamlWorkspaceLoader` — parses workspace YAML into model + pipeline definitions. |
| `client` | `SingulariteeClient` — thin gRPC client; the only dependency a gateway connector needs. |
| `grpc` | gRPC service impls, engine-adapter factories, HuggingFace model resolvers. |
| `http` | OpenAI-compatible HTTP/JSON API — vert.x-web router, JSON↔proto translation, SSE writer, JSON-schema validation, Bearer auth. Gateway-decoupled translation classes. |
| `standalone` | `bootstrap` (two-level classloader entry point), `container` (`SingulariteeNode`, Spring config, `GrpcServerComponent`, `HttpApiServerComponent`), `distribution` (Maven assembly). |

`GrpcServerComponent` (and `HttpApiServerComponent`) bind their ports **first**, before `WorkspaceLoaderComponent` loads the workspace — so `/health` answers immediately while models download/load. Until loading completes, a shared `ReadinessState` gates inference: gRPC calls fail `UNAVAILABLE` ("Model server is still loading") and the HTTP API returns 503 `model_not_ready`.

## Execution model

The same workspace and pipeline definition run in three ways:

| Mode | How to invoke | Where the DAG runs | Where models live |
|------|---------------|--------------------|-------------------|
| **Server-side pipeline** | `InferPipeline` RPC (`--pipeline-id`) | On the server | On the server |
| **Direct model** | `Infer` / `Classify` / `Embed` RPC (`--model-id` / `--classify`) | n/a (single call) | On the server |
| **Client-side pipeline** | `ClientPipelineExecutor` (`--workspace`) | In the client process | On one or more remote servers |

In **client-side** mode the client declares `remote:` endpoints and `remote_*` models; `ClientPipelineExecutor` walks the DAG locally and routes each model call over gRPC to the server that hosts it (`RemoteClassifierEngine` → `Classify()`, `RemoteTextGenEngine` → `Infer()`, etc.). A **multi-server** client simply points different models at different named endpoints — e.g. `examples/modular/client-safety-vllm.yaml` runs `pii`/`toxicity` on the safety server and `llm` on the vLLM server in one DAG.

## How pipelines work

A pipeline is `entry` + a list of `steps`. The executor walks step→step: linear steps follow their `next_step` edge, while routing/looping steps name their own targets. Each step reads and writes a shared **`PipelineContext`** scratchpad — `prompt`, per-step outputs (`{step_id}.output`), the running `messages` list, and usage. A step that yields no next step is terminal.

**Termination** (`finish_reason`): normal exhaustion → `STOP`; a `break`/`loop` condition → `BREAK_CONDITION`; a guard rejection → `GUARD_BLOCKED`, surfaced to the caller as HTTP 400 `content_filter` (the original input is never echoed back). A missing/unreachable step ends the pipeline with a logged warning. Two engine-failure reasons stay distinct on the gRPC surface but map onto legal OpenAI values over HTTP: `CANCELLED` (client disconnect / slow-consumer cancellation) and `STALLED` (backend decode failure — the output is silently truncated), both rendered as `stop`.

**Failure signals as context fields**: each infer step publishes `<step>.finish_reason`, `<step>.tool_parse_ok`, `<step>.tool_parse_failed`, `<step>.tool_call_count`, `<step>.parse_error`, `<step>.thinking_unclosed` and per-step token counts into the pipeline context; route steps publish `<step>.label` / `<step>.matched`, loop steps `<step>.iterations` / `<step>.max_iterations_reached`. Loop/break conditions and `loopback_message` templates can therefore drive self-repair off a malformed tool call or a truncated generation — see [Loops & CoT](docs/loops-and-cot/README.md) and `examples/pipelines/tool-repair.yaml`.

### Step types

| Type | Purpose | Key config |
|------|---------|-----------|
| `infer` | Stream LLM generation | `model_id`, `sampling`, chat `messages` or `prompt.template_id`/`template`, `role`, `tags` |
| `classify` | ONNX/GLiNER classifier → label + score | `model_id`, `input_field`, `output_field` |
| `embed` | Embedding model → vector | `model_id`, `input_field`, `output_field` |
| `route` | Classify/embed the input, dispatch to a named step | `model_id`, `strategy`, `rules[]`, `default_step` |
| `guard` | Input-side safety on a classifier | `model_id`, `input_field`, `action`, `triggers[]` |
| `llm_guard` | LLM-as-judge safety verdict (Llama-Guard style) | `model_id`, `prompt`, `safe_token`, `action` |
| `loop` | Bounded back-edge (chain-of-thought) | `loopback_step`, `condition`, `max_iterations`, `fallback_step`, `loopback_message` |
| `break` | Conditional early halt returning a field | `condition`, `input_field`, `match_value`, `output_field` |
| `sub_pipeline` | Invoke another pipeline (local or remote) | `pipeline_id`, `input_field`, `output_field`, `server`, `forward_messages`, `system_prompt` |
| `regex_guard` | Pattern guard with entity redaction | `patterns[]`, `action`, `redact_with_entity_type` |
| `tool_select` | Shortlist the caller's tools with a classifier before injecting them | `model_id`, `input_field`, `batch_size`, `threshold` |
| `todo` | Server-executed plan tooling: consumes `set_todos`/`complete_todo` calls, mutates the engine-managed plan, streams PROGRESS events | `handled_step` |

**Infer roles** shape conversation history: `output` appends the response as an assistant turn; `thinking` keeps reasoning out of history (and can be stripped from the stream); `internal` holds grader/router verdicts (e.g. `YES`/`NO`) that must not leak into the conversation.

### Guards

A trigger is a `label` + minimum `score`; with multiple `triggers[]` any match fires (OR). Actions:

- **reject** → halt with `GUARD_BLOCKED` (propagates up through sub-pipelines; HTTP 400 `content_filter`).
- **warn** → log and flag, but pass through.
- **redact** → replace matched spans with `[ENTITY_TYPE]` when `redact_with_entity_type: true` (overlapping NER spans merged), else `[REDACTED]`; the sanitized text replaces `prompt`/`messages` so downstream steps never see the original.

### Routing

`route` classifies the input and jumps to the step named by the first matching rule (`rules: [{label, next_step, sentences?}]`), falling back to `default_step`. Strategies: **classifier** (top ONNX/GLiNER label), **embedding_knn** (nearest reference embedding by cosine similarity), **llm_structured** (the model's text output matched to a rule label).

### Loops & chain-of-thought

After a chat-mode `infer` step (no template, non-empty output) its output is auto-appended to `messages`, so later steps see the growing conversation. A `loop` step evaluates an exit `condition` (`equals`, `contains`, `label_equals`, `score_above`, `score_below`, `not_empty`, `empty`) against a field: if met it exits via `next_step`; otherwise it injects the `loopback_message` (Jinja2-rendered) and jumps back to `loopback_step`, until `max_iterations`, after which it routes to `fallback_step`. This is how the CoT example reasons → self-evaluates → refines → answers.

### Message forwarding (sub-pipelines)

`sub_pipeline` with `forward_messages: true` sends the parent's full chat history into the nested pipeline; `system_prompt` overrides/prepends the system message. Without forwarding it sends just the `input_field` text. A sub-pipeline that finishes with a non-`STOP` reason (e.g. a guard block) re-signals halt on the parent.

### Workspace schema

```yaml
workspace:
  name: <string>
  remote:                         # optional — client-side / multi-server
    default: { host, port, username?, password? }
    servers: [ { id, host, port, username?, password? } ]
  models:    [ ... ]              # see model types below
  pipelines: [ ... ]              # entry + steps
  templates: [ { id, content | file } ]
  includes:                       # pull shared defs from sibling folders
    models:    [ "*.yaml" ]       # → ./models/
    pipelines: [ "*.yaml" ]       # → ./pipelines/
    templates: [ "*.yaml" ]       # → ./templates/
```

`includes` resolve each list against the hardcoded `models/`, `pipelines/`, `templates/` subfolder next to the file (globs allowed). Because models are referenced by **logical id**, several model files can share an id (e.g. `llm` = llama.cpp here, vLLM there) and a server includes exactly one — the same pipeline then runs unchanged on any backend.

**Model types:** `llama_cpp`, `vllm`, `onnx_classifier`, `onnx_embedding`, `onnx_reranker`, `gliner_classifier`, `gliner_ner`, `llama_cpp_embedding`, `llama_cpp_reranker` (local engines); `remote_llm`, `remote_classifier`, `remote_embedding`, `remote_reranker` (gRPC proxies); `regex`, `composite_classifier` (in-process, pure-Java).

## Remote & multi-server

Remote model types resolve to `Remote*Engine` proxies wrapping an `SingulariteeClient` (gRPC, with optional HTTP Basic credentials from the endpoint's `username`/`password`). A `sub_pipeline` step with a `server` set delegates the **entire** nested pipeline to another server via `RemotePipelineCallback` → `SingulariteeClient.inferPipeline()`, letting servers compose each other recursively.

**Production:** host one model (or one engine) per server process and compose them this way, rather than co-locating many models in a single JVM. On CUDA especially, the engines load distinct native bindings and each model needs its own slice of GPU memory — packing heterogeneous engines into one process risks native library/version conflicts and memory contention. Isolating per process (and per GPU) keeps lifecycles, failures, and memory independent.

## OpenAI-compatible HTTP API

An optional second front-end (module `gravitee-singularitee-http`, lifecycle `HttpApiServerComponent`) serves an OpenAI-compatible HTTP/JSON API on its own Vert.x server and port (`http.*`, default 8080), opt-in via `http.enabled`. It shares the same `ModelRegistry`/`PipelineRegistry` and engines as gRPC and adds **no** inference logic — it is a thin JSON↔proto translator that drives the same local services (`GraviteeInferenceServiceImpl.infer`/`inferPipeline`, vector/model/pipeline services), inheriting their metrics, tracing and cancel-on-disconnect. `HttpApiServerComponent` is registered after `GrpcServerComponent` (and reuses its tracer); when `http.enabled` is false it is a no-op.

- **Endpoints**: `POST /v1/chat/completions`, `/v1/completions`, `/v1/responses`, `/v1/embeddings`; `GET /v1/models` (+`/{id}`); Gravitee extensions `POST /v1/classify`, `/v1/rerank`, `/v1/similarity`. Each is served bare and under `/v1`.
- **Streaming**: SSE (`text/event-stream`, terminal `[DONE]`) with end-to-end backpressure coupling the RxJava token `Flowable` to the HTTP write queue (`drainHandler`); a client disconnect fires the engine's termination hook, cancelling the sequence/pipeline.
- **Token bridge**: a `WriteStream<InferResponse>` adapter feeds the engine's reactive token stream (`replay().autoConnect`) into the response formatter; `step_role=THINKING` deltas render as OpenAI `reasoning_content`; `tool_calls` are parsed from `<tool_call>…</tool_call>` output.
- **Resolution**: the `model` field maps to a text-gen model (`Infer`) or a pipeline (`InferPipeline`) — `pipeline:` prefix forces the pipeline, otherwise a matching model wins and a bare pipeline id is the fallback; unknown ids → `model_not_found` (400).
- **Validation / auth / errors**: lenient per-endpoint JSON-schema validation (networknt — required/type/enum, `additionalProperties` allowed); optional Bearer API-key auth (constant-time); OpenAI error envelope `{"error":{message,type,param,code}}`.

Translation/formatting classes are fully decoupled from the gateway (local `ServerEvent`, no `gravitee-gateway-api` dependency). Scope is OpenAI-compatible only — Anthropic and Gemini are out of scope. Setup, config and `curl` examples are in the [README](README.md#http-api-openai-compatible) and the [module README](gravitee-singularitee-http/README.md).

## Observability

Opt-in (`services.metrics` / `services.opentelemetry` in `gravitee.yml`).

- **Tracing** — a SERVER span per gRPC call (continuing any inbound W3C `traceparent`) with nested `ai.pipeline → ai.step → ai.model.<op>` spans; direct RPCs get `ai.infer` / `ai.classify` / `ai.embed`. The client-side execution path is not traced. Exported via OTLP.
- **Metrics** (Micrometer → Prometheus at `/_node/metrics/prometheus`): `ai_infer_requests_total`, `ai_infer_latency_seconds`, `ai_pipeline_requests_total`, `ai_pipeline_latency_seconds`, `ai_classify_requests_total`, `ai_embed_requests_total`, `ai_model_call_seconds{model,op}`, `ai_tokens_total{model,kind}`.
