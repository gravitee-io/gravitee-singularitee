# Getting Started

> Build the standalone distribution, run the server, and configure it via `gravitee.yml` — ports, workspace, model cache, and HuggingFace downloads.

## Quick start: `./install.sh`

The fastest path from a fresh clone to a running server. It checks prerequisites (Java 25,
Maven), downloads the llama.cpp native libraries into `~/.llama.cpp`, builds the
distribution, and starts the server with an OpenAI-compatible API on port 8080:

```bash
./install.sh
```

Useful flags: `--port PORT`, `--skip-build`, `--no-run` (set everything up but don't
start), `--llama-version bNNNN`.

The native libraries are **not** bundled in the jar for licensing reasons, which is why
they are downloaded here. They are ABI-specific: the release must match the llamaj.cpp
dependency the build was generated against, so let `install.sh` pick it unless you know
otherwise. Supported hosts are the two llamaj.cpp ships bindings for — macOS on Apple
Silicon and Linux on x86_64.

Model weights are **not** downloaded by the script: the server fetches them from
HuggingFace on first start into `~/.cache/gravitee-singularitee/models`, deliberately
outside the build tree so `mvn clean` does not wipe multi-GB files. Export `HF_TOKEN`
(free account, [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens))
for gated repos — authenticated downloads also dodge anonymous rate limits.

When it finishes, `install.sh` prints ready-to-paste client configuration for **pi**
(`brew install pi-coding-agent`), Claude Code, OpenCode, Codex CLI, Aider, and anything
else that speaks the OpenAI API. Every example workspace exposes one pipeline called
`agent`, so that config is unchanged when you switch models.

### Switching workspaces

`install.sh` hands off to `run-server.sh`, which is what you use from then on:

```bash
./run-server.sh --list                                          # everything runnable
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml      # small and fast
./run-server.sh --workspace examples/pipelines/guard.yaml       # PII + toxicity guards
./run-server.sh --workspace examples/pipelines/tool-router.yaml # tool shortlisting
./run-server.sh --debug                                         # TRACE-log rendered prompts
```

`--debug` is the one to reach for when a model misbehaves: it logs exactly what the model
received after template rendering — system prompt, history and tools — rather than what
you think the client sent. See [examples/](../../examples/README.md) for the full catalogue.

The rest of this page is the manual path and the full configuration reference.

## Overview
Singularitee is a gRPC (and optionally OpenAI-compatible HTTP) inference server built on
gravitee-node with Vert.x 5 and RxJava 3, targeting **Java 25**. The Maven build assembles a
self-contained distribution (`bin/`, `config/`, `lib/`, `plugins/`, ...) launched by
`bin/gravitee.sh` or, from an IDE, by the `SingulariteeContainer` main class. At boot the node starts
its servers first (so `/health` answers immediately), then loads the workspace declared in
`ai.workspace.path` — models are downloaded from HuggingFace into the model cache on first use.
Until the workspace finishes loading, service calls return `UNAVAILABLE` ("Model server is still
loading"); readiness flips only once every model and pipeline is published.

## Key types
- `Bootstrap` (`io.gravitee.singularitee.standalone.bootstrap`) — production entry point; requires `gravitee.home` (`-Dgravitee.home` or `GRAVITEE_HOME`), builds the `lib/ext/` → `lib/` classloader chain, then starts the container.
- `SingulariteeContainer` (`io.gravitee.singularitee.standalone`) — Spring-based container with its own `main()`; the class to run from an IDE (`-Dgravitee.home=<distribution>`).
- `SingulariteeNode` — the gravitee-node `Node` (`gio-singularitee`); registers components in boot order: monitoring services → `GrpcServerComponent` → `HttpApiServerComponent` → `WorkspaceLoaderComponent`.
- `GrpcServerComponent` — binds the gRPC port from the `grpc.*` config block (TLS, ALPN, HTTP Basic auth).
- `HttpApiServerComponent` — opt-in OpenAI-compatible HTTP API from the `http.*` block (Bearer auth).
- `WorkspaceLoaderComponent` — parses the workspace YAML, publishes models and pipelines, then marks the node ready (`ReadinessState`).
- `SingulariteeConfiguration` — Spring wiring; resolves `ai.models.path`, `ai.huggingface.token`, and the workspace path.
- `HuggingFaceModelDownloader` / `GgufModelResolver` / `OnnxModelResolver` / `GlinerModelResolver` — resolve model files as local file → cache hit → HuggingFace download (`Authorization: Bearer <token>` when a token is set).

## Usage

**Build** (Java 25 + Maven):

```bash
mvn clean install -DskipTests
```

The distribution lands under
`gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution/`:

```
distribution/
├── bin/          # gravitee.sh
├── config/       # gravitee.yml, logback.xml
├── lib/          # io.gravitee.* jars (incl. the bootstrap jar)
│   └── ext/      # third-party jars
├── plugins/
├── models/       # model cache (created on first download)
└── logs/
```

**Run** with the launcher script:

```bash
GRAVITEE_HOME=/path/to/distribution ./bin/gravitee.sh

# override port and workspace inline:
GRAVITEE_HOME=/path/to/distribution \
JAVA_OPTS="-Dgrpc.port=9090 -Dai.workspace.path=/path/to/workspace.yaml" \
./bin/gravitee.sh

# gated HuggingFace models:
HF_TOKEN=hf_xxx GRAVITEE_HOME=/path/to/distribution ./bin/gravitee.sh
```

`gravitee.sh` execs `java --enable-preview --enable-native-access=ALL-UNNAMED` with
`-Dgravitee.home=$GRAVITEE_HOME`, appends `JAVA_OPTS`, and runs the
`gravitee-singularitee-standalone-bootstrap-*.jar` it finds under `lib/`.

**Run from an IDE** — main class `io.gravitee.singularitee.standalone.SingulariteeContainer` with VM
options `-Dgravitee.home=/path/to/distribution` (add `--enable-preview --enable-native-access=ALL-UNNAMED`).
Committed IntelliJ run configurations live under `.run/`.

**Configure** — `config/gravitee.yml` (shipped defaults):

```yaml
grpc:
  port: 9090
  host: 0.0.0.0
  alpn: true

http:                 # OpenAI-compatible HTTP API — opt-in
  enabled: false
  port: 8080
  expose-pipelines: true

ai:
  workspace:
    path:              # no default — point at an examples/ file or your own
  models:
    path: ${gravitee.home}/models
  huggingface:
    token:            # falls back to the HF_TOKEN env var
  streaming:
    buffer-capacity: 256
```

Every key accepts a `-D` system-property override or a `GRAVITEE_`-prefixed environment variable
(dots become underscores): `grpc.port` → `-Dgrpc.port=9091` or `GRAVITEE_GRPC_PORT=9091`,
`ai.workspace.path` → `GRAVITEE_AI_WORKSPACE_PATH=...`.

## Options

### `grpc.*`
| Key | Default | Purpose |
| --- | --- | --- |
| `grpc.port` | `9090` | gRPC listen port. |
| `grpc.host` | `0.0.0.0` | Bind address. |
| `grpc.alpn` | `true` | ALPN negotiation (forced on when `secured: true`). |
| `grpc.secured` | `false` | Enable TLS; configure certs under `grpc.ssl.*`. |
| `grpc.ssl.keystore.type` | — | `JKS`, `PEM`, `PKCS12`, or `SELF-SIGNED` (hot-reloadable with `watch`). |
| `grpc.ssl.clientAuth` | `NONE` | mTLS: `NONE`, `REQUEST`, or `REQUIRED` (truststore under `grpc.ssl.truststore.*`). |
| `grpc.auth.enabled` | `false` | HTTP Basic auth on gRPC calls (metadata `authorization: Basic ...`). |
| `grpc.auth.type` | `basic` | Only `basic` is supported. |
| `grpc.auth.users.<name>` | — | User → password map (also via `-Dgrpc.auth.users.<name>` / `GRAVITEE_GRPC_AUTH_USERS_<NAME>`). |
| `grpc.idleTimeout` | `0` | Connection idle timeout (0 = none). |
| `grpc.compressionSupported` | `false` | gRPC message compression. |
| `grpc.client.ssl.truststore.*` | JVM default trust store | Outbound TLS for workspace `remote:` endpoints with `ssl: true` — trust material for the peer's CA (`PEM`/`JKS`/`PKCS12`). |
| `grpc.client.ssl.keystore.*` | — | Client certificate presented to peers demanding `clientAuth` (mutual TLS). See [Remote & Multi-Server](../remote-and-multi-server/README.md). |

### `http.*`
| Key | Default | Purpose |
| --- | --- | --- |
| `http.enabled` | `false` | Enable the OpenAI-compatible HTTP API. |
| `http.port` | `8080` | HTTP listen port (separate Vert.x server). |
| `http.host` | `0.0.0.0` | Bind address. |
| `http.expose-pipelines` | `true` | List pipelines on `/v1/models` and accept pipeline ids as `model`. |
| `http.auth.enabled` | `false` | Bearer-token auth. |
| `http.auth.type` | `bearer` | Only `bearer` is supported. |
| `http.auth.tokens` | — | List of accepted API keys. |
| `http.secured` / `http.ssl.*` | `false` | TLS, same structure as `grpc.ssl.*`. |

### `ai.*`
| Key | Default | Purpose |
| --- | --- | --- |
| `ai.workspace.path` | — | Workspace YAML loaded at startup; unset = no workspace (skipped). |
| `ai.models.path` | `${gravitee.home}/models`, else `~/.cache/gravitee-singularitee/models` | Model cache directory; downloads are skipped when the file is already cached. |
| `ai.huggingface.token` | `HF_TOKEN` env var | Token for gated HuggingFace repos. |
| `ai.huggingface.download.chunkSize` | `10485760` (10 MiB) | Chunk size in bytes for accelerated (hf_transfer-style) parallel Range downloads. |
| `ai.huggingface.download.parallelism` | `8` | Concurrent Range requests per file; peak buffered memory is `parallelism × chunkSize`. |
| `ai.huggingface.download.chunkedThreshold` | `2 × chunkSize` | Minimum file size (bytes) for the chunked path; smaller files stream over one connection. |
| `ai.streaming.buffer-capacity` | `256` | Token-stream buffer capacity. |
| `ai.todos.session-ttl` | `1800` | Todo-session idle timeout (seconds) for cross-turn plan recovery; `0` disables. |
| `ai.todos.session-max-entries` | `10000` | Max concurrently tracked todo sessions. |
| `ai.conversations.ttl` | `3600` | Stored-conversation idle timeout (seconds) for Responses `previous_response_id` continuation; `0` disables. |
| `ai.conversations.max-entries` | `10000` | Max concurrently stored conversations. |
| `ai.vllm.tensor-parallel-size` | — | Deployment-wide vLLM GPU topology fallback; a model's own `tensor_parallel_size` wins. |
| `ai.vllm.pipeline-parallel-size` | — | Same, for `pipeline_parallel_size`. |
| `ai.vllm.distributed-executor-backend` | — | Same, for `distributed_executor_backend`. See [Workspaces](../workspaces/README.md). |

## Notes
- **Boot order**: the gRPC and HTTP servers bind their ports *before* the workspace loads, so `/health` answers `200 OK` immediately — but every service call returns `UNAVAILABLE` (gRPC status 14 / HTTP 503, "Model server is still loading") until `WorkspaceLoaderComponent` publishes all models and pipelines and marks the node ready. Wait for readiness before sending traffic.
- **Model downloads happen at boot**: models named as HuggingFace repos (`name: Qwen/Qwen3-0.6B-GGUF`) are fetched from `huggingface.co` into `ai.models.path` the first time the workspace loads; subsequent boots hit the cache. Gated repos need `ai.huggingface.token` or `HF_TOKEN`.
- **Java 25 with preview features** is mandatory — the FFM-based engines need `--enable-preview --enable-native-access=ALL-UNNAMED`, which `gravitee.sh` sets for you but IDE run configs must add.
- **`gravitee.home` is required**: `Bootstrap` throws `IllegalStateException` if neither `-Dgravitee.home` nor `GRAVITEE_HOME` is set.
- **Management API** on `localhost:18092` (basic auth `admin`/`adminadmin` by default) exposes health and Prometheus metrics at `/_node/metrics/prometheus` — see the `services.*` block in the shipped `gravitee.yml`.
- **Non-loopback without auth logs a warning** on the HTTP API — enable `http.auth` before exposing it beyond localhost.

## See also
- [Workspaces](../workspaces/README.md) — the YAML format behind `ai.workspace.path`: models, pipelines, templates, includes.
- [gRPC API & Client](../grpc-api-and-client/README.md) — the services the bound port exposes and the Java client.
- [OpenAI HTTP API](../openai-http-api/README.md) — the `http.*` block's endpoints (`/v1/chat/completions`, `/v1/embeddings`, ...).
- [Deployment](../deployment/README.md) — Docker images and production layout.
