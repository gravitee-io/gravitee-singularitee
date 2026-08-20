# Gravitee Singularitee — working notes

Gravitee's inference server: hosts LLMs, classifiers, embedders and rerankers in a
**separate process** from the gateway, and runs multi-step **pipelines** over them.
Speaks gRPC (primary) and an optional OpenAI-compatible HTTP API over the same registries.

Everything below was verified by running it on macOS/Apple Silicon. Where something is
untested here (CUDA, vLLM on Linux) it says so.

---

## 1. Setup

Prereqs: **Java 25** (`.java-version` pins 25.0.4), Maven, and — for the demo tasks —
[go-task](https://taskfile.dev) (`brew install go-task`) and [uv](https://docs.astral.sh/uv/).

```bash
./install.sh            # prereq check → llama.cpp natives → build → run
```

`install.sh` downloads the llama.cpp native libraries into `~/.llama.cpp` (they are **not**
bundled in the jar, for size reasons) and then hands off to `run-server.sh`. Supported hosts are
the two llamaj.cpp ships bindings for: macOS/Apple Silicon and Linux/x86_64.

Manual build:

```bash
mvn clean install -DskipTests     # distribution lands in
# gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution/
```

> **The build enforces formatting and license headers.** A formatting deviation fails with
> `Incorrectly formatted file` *before* compiling; a missing/incorrect Apache-2.0 header fails
> the license check. Fix both with `mvn prettier:write license:format` (add `-pl <module>` to
> scope it). Expect this on your first new or edited file — including YAML and shell scripts,
> which carry the header too.

### Running

```bash
./run-server.sh --list                                      # every runnable workspace
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml  # the reference model
./run-server.sh --debug                                     # TRACE-log rendered prompts
./run-server.sh --port 8081 --workspace <file>
```

`--debug` is the tool for "why did the model do that" — it logs the prompt *after* template
rendering, i.e. what the model actually received.

Model weights download from HuggingFace on first boot into
`~/.cache/gravitee-singularitee/models` — deliberately outside the build tree so `mvn clean`
doesn't wipe multi-GB files. Set `HF_TOKEN` for gated repos and to dodge anonymous rate limits.

`task` wraps the common paths (`task run:qwen`, `task run:guard`, `task chat`, `task classify`,
`task vision`, `task audio`). Server tasks run in the foreground; demo tasks need a server
already running in another shell.

### Engines: llama.cpp is the default

**llama.cpp is the default backend and the one to develop against** — cross-platform
(CPU/Metal/CUDA), no Python, and `install.sh` sets it up for you. ONNX and GLiNER need nothing
beyond the build either. Everything in `examples/` outside `examples/vllm/` runs on those three.

**vLLM is opt-in and Linux/CUDA-first.** It runs from a Python virtualenv that the JVM loads
CPython out of, so it is skipped by default (`vllm.setupVenv.skip=true`):

```bash
./scripts/setup-venv.sh -b metal        # or -b cuda | -b cpu; -d <dir>, -v 3.12
./run-server.sh --workspace examples/vllm/gpt-oss-20b-mac.yaml   # venv auto-detected
```

`run-server.sh` finds `~/.venv-gravitee-ai/.venv` and passes `-Dvllm4j.venv` — the only thing
vLLM4j reads, so a venv merely on `PATH` is not enough — plus the `libpython`/`libjsig`
preloads. `--venv` or `$VLLM_VENV` point elsewhere. Maven can bootstrap it instead:
`mvn verify -Pvllm-integration,metal`, or `-Dvllm.venv.path=` to reuse one.

On Apple Silicon use `examples/vllm/*-mac.yaml`: the others are sized for an 80 GB card, and
Metal applies `gpu_memory_utilization` to *total* unified memory rather than what is free.

---

## 2. Layout

| Module | Role |
| --- | --- |
| `protocol` | `.proto` contract + generated Vert.x gRPC stubs. **Source of truth for the wire.** |
| `engine` | Pipeline execution: `ModelRegistry`, `PipelineRegistry`, `PipelineExecutor`, step executors. |
| `engine-remote` | `remote_*` proxy engines + `ClientPipelineExecutor` (client-side DAG, remote models). |
| `inference` | Vendored engines (`-api`, `-llama-cpp`, `-vllm`, `-onnx`, `-math`) behind `AbstractBatchEngine`. |
| `workspace` | `YamlWorkspaceLoader` — YAML → model/pipeline definitions. `ModelType` lives here. |
| `grpc` | gRPC service impls, engine-adapter factories, HuggingFace resolvers. |
| `http` | OpenAI-compatible HTTP/JSON API. Pure translation — no inference logic. |
| `client` | `SingulariteeClient` — thin gRPC client, the only dep a gateway connector needs. |
| `standalone` | `bootstrap` (classloader), `container` (node, Spring, components), `distribution` (assembly). |

Non-module directories: `examples/` (runnable workspaces), `docs/` (see below),
`prod/` (production workspaces, gitignored), `k6/` (load tests), `docker/`, `release/`.

---

## 2b. Documentation — where things are written down

Three tiers, and they answer different questions. Look here **before** reading code:

| | Answers |
| --- | --- |
| `README.md` | What the project is, the gRPC/HTTP surface, quick start, workspace format at a glance. |
| `ARCHITECTURE.md` | Execution model, module breakdown, **how pipelines work** — step types, guard/routing/loop semantics, workspace schema, remote composition. |
| `docs/<topic>/README.md` | One page per capability, in depth. `docs/README.md` is the index. |

```
docs/
├── getting-started/          build, run, gravitee.yml, every config key, boot order
├── workspaces/               the YAML format: models, pipelines, templates, includes
├── pipelines/                the DAG, step types, execution context
├── text-generation/          Infer RPC / infer step, streaming, sampling
├── classification/           ONNX BERT, GLiNER zero-shot, regex, composite
├── embeddings-and-reranking/ vectors, cross-encoders, similarity
├── guards-and-redaction/     classifier / LLM-judge / regex guards, redaction
├── routing/                  label, embedding-KNN and LLM-structured routing
├── loops-and-cot/            loop back-edges, break, self-refinement
├── sub-pipelines/            nested pipelines, local and remote
├── multimodal/               image_url / input_audio content parts
├── openai-http-api/          every HTTP endpoint and its semantics
├── grpc-api-and-client/      the four services + SingulariteeClient
├── remote-and-multi-server/  remote_* proxies, client-side execution
├── models/                   models validated end-to-end, with measurements
├── observability/            OTel spans, Micrometer/Prometheus metrics
├── deployment/               CUDA images per engine, build args, prod topology
└── openapi/                  singularitee.openapi.yaml — machine-readable HTTP schema
```

**House style** — match it when adding a page: a `> one-line summary` under the title, then
`## Overview`, `## Key types`, `## Usage`, `## Options` (a table of keys/defaults/purpose),
`## Notes` (gotchas), `## See also` (sibling pages).

**When you change something, grep for it:**

```bash
grep -rn "<thing>" docs/ README.md ARCHITECTURE.md examples/
```

Add a `docs/<topic>/` page → link it from `docs/README.md`. Add a step type or config key →
update `ARCHITECTURE.md`'s table and the relevant `## Options` table. Rename an example or a
model repo → the example headers, the docs snippets and `examples/README.md` all reference it.
Example files carry their own runnable curl in the header comment; keep it consistent with the
ids the workspace actually publishes.

---

## 3. Configuration

Defaults ship in `.../standalone-distribution/src/main/resources/config/gravitee.yml`.
**Every key** accepts a `-D` system property or a `GRAVITEE_`-prefixed env var
(dots → underscores): `grpc.port` → `GRAVITEE_GRPC_PORT`, `ai.workspace.path` →
`GRAVITEE_AI_WORKSPACE_PATH`.

| Key | Default | Notes |
| --- | --- | --- |
| `grpc.port` / `grpc.host` | `9090` / `0.0.0.0` | Primary API. |
| `grpc.secured`, `grpc.ssl.*` | off | TLS, hot-reloadable PEM/JKS, mTLS via `ssl.clientAuth`. |
| `grpc.auth.enabled` / `.users.<name>` | off | HTTP Basic over gRPC metadata. |
| `http.enabled` | **`false`** | OpenAI HTTP API is opt-in. |
| `http.port` | `8080` | Separate Vert.x server. |
| `http.expose-pipelines` | `true` | List pipelines on `/v1/models` and accept pipeline ids as `model`. |
| `http.auth.enabled` / `.tokens` | off | Bearer tokens. |
| `ai.workspace.path` | — | Workspace loaded at boot. Unset = start empty. |
| `ai.models.path` | `${gravitee.home}/models` | `run-server.sh` overrides to `~/.cache/gravitee-singularitee/models`. |
| `ai.huggingface.token` | `$HF_TOKEN` | Gated repos. |
| `ai.streaming.buffer-capacity` | `256` | Tokens a slow client may lag before its stream is cancelled. |
| `ai.vllm.tensor-parallel-size` etc. | — | Deployment-wide GPU topology; a model's own value wins. |
| `services.core.http.port` | `18092` | Management API + `/_node/metrics/prometheus` (basic auth). |
| `services.opentelemetry.enabled` | `false` | OTLP tracing. |

`.run/` holds committed IntelliJ configs (main class
`io.gravitee.singularitee.standalone.SingulariteeContainer`, VM options need
`-Dgravitee.home=<distribution>` plus `--enable-preview --enable-native-access=ALL-UNNAMED`).

---

## 4. Workspaces

A workspace declares what to publish. Models get a **stable logical id** (`llm`, `pii`,
`toxicity`, `router`) so the same pipeline runs against any backend bound to that id.

```yaml
workspace:
  name: my-workspace
  models:
    - id: llm                       # logical id — what pipelines reference
      name: Qwen/Qwen3-0.6B-GGUF    # HuggingFace repo, or a local path
      type: llama_cpp               # selects the <type>: config block below
      memory_check: warn            # disabled | warn | fail
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

**Model types** (`ModelType.java`): `llama_cpp`, `vllm`, `onnx_classifier`, `onnx_embedding`,
`onnx_reranker`, `gliner_classifier`, `gliner_ner`, `llama_cpp_embedding`, `llama_cpp_reranker`,
the `remote_*` proxies, and `regex` / `composite_classifier` (pure Java, run anywhere).

**Step types** (`StepExecutorFactory.createHandlers`): `infer`, `classify`, `embed`, `route`,
`guard`, `llm_guard`, `loop`, `break`, `sub_pipeline`, `regex_guard`, `tool_select`, `todo`.

**Publication** — `task:`, `visible:` and `modalities:` apply to both a model and a pipeline entry.
`task` is the slug `/v1/models` advertises (`text-generation`, `text-classification`,
`token-classification`, `feature-extraction`, `reranking`); unset, a model reports its
engine's and a pipeline inherits the model behind its `role: output` step. Pipelines are
never labelled `pipeline`. `visible: false` drops an entry from the listings and from HTTP
resolution while leaving it callable as a pipeline dependency and over gRPC — publish the
pipeline, hide its parts. `modalities` is what the entry accepts (`text`/`image`/`audio`),
detected not declared — llama.cpp asks the mtmd projector, vLLM reads the checkpoint's
`config.json`, pipelines take the union over their model-bound steps; HTTP refuses media the target
cannot read (`unsupported_modality`) rather than dropping it silently. A VLM/ALM is still
`task: text-generation`.

**Composition** — `includes:` pulls `models:` / `pipelines:` / `templates:` from the sibling
`models/`, `pipelines/`, `templates/` folders (globs allowed). Because ids are logical, several
model files can share an id and a server includes exactly one. See `examples/modular/`.

**Multi-server** — a client workspace declares `remote:` endpoints and `remote_*` models; the
DAG runs locally while model calls travel over gRPC.

---

## 5. Testing

```bash
mvn test                                        # full suite
task test:examples                              # loads every examples/**.yaml through the real loader
mvn -o -pl <module> test -Dtest=<Class>         # one class
```

`ExamplesWorkspaceTest` (79 tests) parses and resolves **every** example. It is the fastest
guard against breaking a workspace — run it after touching `examples/` or the loader. It
validates *structure only*: it never resolves weights, so a wrong HuggingFace repo name still
passes here and only fails at boot.

Don't use `mvn -q` when you need to read a result — it hides the surefire summary.

Smoke-testing a running server:

```bash
curl -s localhost:8080/v1/models | jq
uv run --with openai examples/scripts/openai_test.py       # or: task chat
uv run --with requests examples/scripts/classify_test.py   # or: task classify
```

gRPC (no reflection assumed — point grpcurl at the protos):

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/vector.proto \
  -d '{"model_id":"text-embedding","text":"hello"}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeVectorService/Embed
```

---

## 6. Extending

**Add a model type** — `ModelType` (workspace) is an enum where each constant implements
`toModelLoadRequest(...)`, mapping its YAML block to a proto `ModelLoadRequest`. Add the
constant with its wire name, a config record in `WorkspaceDefinition`, the proto message in
`model.proto`, and an engine-adapter factory in `grpc`. Factories are registered only when a
**probe class** resolves on the classpath (`SingulariteeConfiguration`), which is how one
distribution flavour can omit an engine and fail with "no factory for type" rather than
`NoClassDefFoundError`.

**Add a pipeline step** — add `STEP_TYPE_*` to `pipeline.proto` (never reuse a retired tag;
see the `reserved` entries), a config message beside it, the config record + parsing in
`WorkspaceDefinition`/`YamlWorkspaceLoader`, a `StepExecutor` in `engine`, and register it in
`StepExecutorFactory.createHandlers`. Document it in the ARCHITECTURE.md step table.

**Add an HTTP endpoint** — `http` is a translator only. Route + request/response records +
JSON↔proto mapping; it must drive the same local service the gRPC path uses, so metrics,
tracing and cancel-on-disconnect come for free. Don't put inference logic here.

**Add an engine backend** — implement `EngineAdapter` (in `inference-api`) and let
`AbstractBatchEngine` own sequence lifecycle, slots, queuing, stop-strings and streaming.
The adapter handles only backend specifics.

**Wire compatibility** — `protocol` is a published contract. Adding an enum value or field is
fine; renumbering or reusing a tag is not. Retired values are tombstoned with `reserved`
(both tag and name) — follow that pattern.

**Keep OpenAI compatibility.** `finish_reason` is a closed set (`stop`, `length`, `tool_calls`,
`content_filter`). Internal reasons are mapped onto legal values (`GUARD_BLOCKED` →
`content_filter`); tags 4/5 in `FinishReason` were retired precisely because they were dead
compatibility values. Don't invent new ones — conforming clients will reject them.

---

## 7. Gotchas

**A failed model load does not fail startup.** `WorkspaceLoaderComponent` logs a WARN and
carries on, so a bad repo name yields a *healthy* server with `/v1/models` → `[]`. If a
workspace looks inert, grep the log for `failed to load`.

**Ports bind before models load.** `/health` answers 200 immediately while calls return
`UNAVAILABLE` / 503 ("Model server is still loading"). A TCP readiness probe is not enough —
poll `/v1/models`.

**`n_ctx` is per sequence.** Total KV = `n_ctx × n_seq_max`. `examples/llama/qwen3-30b.yaml`
(131072 × 2) OOMs on a 36 GB machine while the *larger* `qwen3.6-35b` (131072 × 1) runs fine.
When a model won't fit, check `n_seq_max` before blaming the weights.

**A raw model id is not the pipeline.** `/v1/models` advertises both. Calling the bare model
returns the unprocessed token stream — for dialect models (gpt-oss/Harmony) that includes
channel markers in `content`. The channel/tool handling lives in the pipeline step's `tags:`,
so use the pipeline id for client-facing traffic.

**One backend per process.** llama.cpp, vLLM and ONNX Runtime each load their own native
(CUDA) libraries; co-locating them invites library conflicts and GPU-memory contention.
Compose across processes over gRPC — that is what `examples/modular/` demonstrates.

**Modular client workspaces need a companion server.** `client-*.yaml` declares `remote:`
endpoints (e.g. `127.0.0.1:9090`) and fails fast at startup if the server isn't up. Run the
`server-*.yaml` first, then the client on different ports
(`GRAVITEE_GRPC_PORT=9190 ./run-server.sh --port 8180 --workspace <client>`).

**`target/distribution` can go stale.** `run-server.sh` launches whatever is there. If you
change Java and don't rebuild, you are running the old code — and a `-Pcuda` build leaves a
GPU-only ONNX Runtime behind that fails on macOS. Rebuild before testing.

**Distribution flavours.** Default carries every engine; `-Pdist-onnx` / `-Pdist-llama` /
`-Pdist-vllm` ship one each, and `-Pcuda` swaps ONNX Runtime for the GPU artifact. CI builds
the per-engine images; local development wants the default.

**Keep the venv's vLLM version in step with the image.** `scripts/setup-venv.sh` pins vLLM
`0.26.0`, matching `Dockerfile.vllm-cuda`'s `VLLM_IMAGE` — vLLM4j is compiled against a specific
vLLM Python API, so a drifting venv fails at model load rather than at build. See
[Engines](#engines-llamacpp-is-the-default) for the setup itself.

**Keep `LLAMACPP_VERSION` in lockstep with llamaj.cpp.** The FFI bindings are ABI-specific;
a mismatch shows up as a runtime `NoSuchMethodError`. Currently `b10276` ↔ llamaj.cpp `2.7.0`
(`Dockerfile.llama-cuda`, `install.sh`).

**Java reads zero entries from openssl cert-only PKCS12 bundles.** `openssl pkcs12
-export -nokeys` produces a truststore Java silently treats as empty — TLS then fails in
ways that look like configuration errors (mTLS servers reject valid clients with
`internal_error`). Build truststores with `keytool -importcert`; `scripts/gen-dev-certs.sh`
does it right. Also: `openssl s_client </dev/null` cannot observe TLS 1.3 client-auth
rejection (it happens after the handshake) — force `-tls1_2` to see it, or make a real RPC.
