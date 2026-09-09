# Configuration

> Every `gravitee.yml` key, its default, its environment variable and what it does, plus the flags and variables read by `run-server.sh`, `install.sh` and `scripts/setup-venv.sh`.

## Overview

The server reads `${gravitee.home}/config/gravitee.yml` at boot. The shipped defaults live in
`gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/src/main/resources/config/gravitee.yml`
and are copied into `target/distribution/config/`.

Every key can be overridden without editing the file:

| Mechanism | Form | Example |
| --- | --- | --- |
| JVM system property | `-D<key>=<value>` in `JAVA_OPTS` | `-Dgrpc.port=9091` |
| Environment variable | `GRAVITEE_` + key upper-cased, `.` -> `_`, `-` dropped (an `_` in its place is also accepted) | `GRAVITEE_GRPC_PORT=9091`, `GRAVITEE_HTTP_EXPOSEPIPELINES=false`, `GRAVITEE_AI_VLLM_TENSORPARALLELSIZE=4` |
| List entries | index suffix | `GRAVITEE_HTTP_AUTH_TOKENS_0=sk-...` for `http.auth.tokens[0]` |
| Map entries | key suffix | `GRAVITEE_GRPC_AUTH_USERS_ADMIN=secret` for `grpc.auth.users.admin` |

Precedence: system property, then environment variable, then `gravitee.yml`.

Keys whose default column says `unset` have no value in the shipped file; the behaviour listed is what happens when they stay unset.

## Key types

- `SingulariteeConfiguration` (standalone container): reads the `ai.*` keys and wires the model cache, HuggingFace downloader, streaming policy, todo and conversation stores, and the vLLM topology defaults.
- `GrpcServerComponent`: reads `grpc.*` through gravitee-node's `VertxHttpServerOptions` (port, host, TLS, HAProxy, compression) and `grpc.auth.*` itself.
- `HttpApiServerComponent`: reads `http.*` the same way, plus `http.auth.*` and `http.expose-pipelines`.
- `GrpcClientSslConfig`: reads `grpc.client.ssl.*` for outbound TLS to workspace `remote:` endpoints.
- `WorkspaceLoaderComponent`: reads `ai.workspace.path`.
- `Bootstrap`: reads `gravitee.home` (`-Dgravitee.home` or `GRAVITEE_HOME`).
- gravitee-node: owns `services.*`, `gracefulShutdown.*` and `kubernetes.*`.

## Usage

```bash
# one-off overrides on the launcher
GRAVITEE_HOME=/path/to/distribution \
JAVA_OPTS="-Dgrpc.port=9091 -Dai.workspace.path=/path/to/workspace.yaml" \
./bin/gravitee.sh

# the same with environment variables (what run-server.sh and the Docker images do)
GRAVITEE_GRPC_PORT=9091 \
GRAVITEE_HTTP_ENABLED=true \
GRAVITEE_AI_WORKSPACE_PATH=/path/to/workspace.yaml \
./bin/gravitee.sh
```

## Options

### `grpc.*` (primary API)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `grpc.port` | `9090` | `GRAVITEE_GRPC_PORT` | gRPC listen port. |
| `grpc.host` | `0.0.0.0` | `GRAVITEE_GRPC_HOST` | Bind address. A non-loopback bind without `grpc.auth` logs a warning at start. |
| `grpc.alpn` | `true` | `GRAVITEE_GRPC_ALPN` | HTTP/2 ALPN negotiation. Forced on when `grpc.secured` is `true`. |
| `grpc.compressionSupported` | `false` | `GRAVITEE_GRPC_COMPRESSIONSUPPORTED` | gRPC message compression. |
| `grpc.idleTimeout` | `0` | `GRAVITEE_GRPC_IDLETIMEOUT` | Connection idle timeout in seconds; `0` disables. |
| `grpc.tcpKeepAlive` | `true` | `GRAVITEE_GRPC_TCPKEEPALIVE` | TCP keep-alive. |
| `grpc.secured` | `false` | `GRAVITEE_GRPC_SECURED` | Enable TLS on the gRPC port; certificates come from `grpc.ssl.*`. |
| `grpc.haproxy.proxyProtocol` | `false` | `GRAVITEE_GRPC_HAPROXY_PROXYPROTOCOL` | Accept the HAProxy PROXY protocol (v1/v2). |
| `grpc.haproxy.proxyProtocolTimeout` | `10000` | `GRAVITEE_GRPC_HAPROXY_PROXYPROTOCOLTIMEOUT` | PROXY header timeout in ms. |

### `grpc.ssl.*` (server TLS)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `grpc.ssl.sni` | `false` | `GRAVITEE_GRPC_SSL_SNI` | Server Name Indication. |
| `grpc.ssl.openssl` | `false` | `GRAVITEE_GRPC_SSL_OPENSSL` | Use the OpenSSL engine (needs netty-tcnative). |
| `grpc.ssl.tlsProtocols` | `TLSv1.2,TLSv1.3` | `GRAVITEE_GRPC_SSL_TLSPROTOCOLS` | Allowed TLS versions. |
| `grpc.ssl.clientAuth` | `NONE` | `GRAVITEE_GRPC_SSL_CLIENTAUTH` | `NONE`, `REQUEST` or `REQUIRED` (mutual TLS). |
| `grpc.ssl.keystore.type` | unset | `GRAVITEE_GRPC_SSL_KEYSTORE_TYPE` | `JKS`, `PEM`, `PKCS12` or `SELF-SIGNED`. |
| `grpc.ssl.keystore.path` | unset | `GRAVITEE_GRPC_SSL_KEYSTORE_PATH` | Keystore file. |
| `grpc.ssl.keystore.password` | unset | `GRAVITEE_GRPC_SSL_KEYSTORE_PASSWORD` | Keystore password. |
| `grpc.ssl.keystore.watch` | `true` | `GRAVITEE_GRPC_SSL_KEYSTORE_WATCH` | Hot-reload the keystore on file change. |
| `grpc.ssl.truststore.type` | unset | `GRAVITEE_GRPC_SSL_TRUSTSTORE_TYPE` | `JKS`, `PEM`, `PKCS12` or `PEM-FOLDER`. |
| `grpc.ssl.truststore.path` | unset | `GRAVITEE_GRPC_SSL_TRUSTSTORE_PATH` | Truststore file (client certificates for mTLS). |
| `grpc.ssl.truststore.password` | unset | `GRAVITEE_GRPC_SSL_TRUSTSTORE_PASSWORD` | Truststore password. |

### `grpc.auth.*` (HTTP Basic over gRPC metadata)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `grpc.auth.enabled` | `false` | `GRAVITEE_GRPC_AUTH_ENABLED` | Require `authorization: Basic base64(user:password)` in call metadata; rejects with `UNAUTHENTICATED`. Startup fails when enabled with no users. |
| `grpc.auth.type` | `basic` | `GRAVITEE_GRPC_AUTH_TYPE` | Only `basic` is supported. |
| `grpc.auth.users.<name>` | unset | `GRAVITEE_GRPC_AUTH_USERS_<NAME>` | One entry per user; the env var suffix is lower-cased to form the user name. |

### `grpc.client.ssl.*` (outbound TLS to `remote:` endpoints)

The workspace decides which endpoints are reached over TLS (`ssl: true` on the endpoint); this block decides what the client presents and trusts. Unset means the JVM default trust store and no client certificate.

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `grpc.client.ssl.trustAll` | `false` | `GRAVITEE_GRPC_CLIENT_SSL_TRUSTALL` | Skip server certificate verification. Development only; logs a warning. |
| `grpc.client.ssl.verifyHostname` | `true` | `GRAVITEE_GRPC_CLIENT_SSL_VERIFYHOSTNAME` | Check the server certificate against the endpoint host. |
| `grpc.client.ssl.truststore.type` | unset | `GRAVITEE_GRPC_CLIENT_SSL_TRUSTSTORE_TYPE` | `PEM`, `JKS` or `PKCS12`. |
| `grpc.client.ssl.truststore.path` | unset | `GRAVITEE_GRPC_CLIENT_SSL_TRUSTSTORE_PATH` | CA material for the peer. |
| `grpc.client.ssl.truststore.password` | unset | `GRAVITEE_GRPC_CLIENT_SSL_TRUSTSTORE_PASSWORD` | Truststore password (JKS, PKCS12). |
| `grpc.client.ssl.keystore.type` | unset | `GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_TYPE` | `PEM`, `JKS` or `PKCS12`. Setting a keystore makes the connection mutual TLS. |
| `grpc.client.ssl.keystore.path` | unset | `GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_PATH` | Client certificate (PEM) or keystore file. |
| `grpc.client.ssl.keystore.keyPath` | unset | `GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_KEYPATH` | Private key file; required when `type` is `PEM`. |
| `grpc.client.ssl.keystore.password` | unset | `GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_PASSWORD` | Keystore password (JKS, PKCS12). |

### `http.*` (OpenAI-compatible HTTP API)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `http.enabled` | `false` | `GRAVITEE_HTTP_ENABLED` | Start the HTTP API. Off by default. |
| `http.port` | `8080` | `GRAVITEE_HTTP_PORT` | HTTP listen port (a separate Vert.x server). |
| `http.host` | `0.0.0.0` | `GRAVITEE_HTTP_HOST` | Bind address. A non-loopback bind without `http.auth` logs a warning. |
| `http.idleTimeout` | `0` | `GRAVITEE_HTTP_IDLETIMEOUT` | Idle timeout in seconds; `0` disables. |
| `http.tcpKeepAlive` | `true` | `GRAVITEE_HTTP_TCPKEEPALIVE` | TCP keep-alive. |
| `http.expose-pipelines` | `true` | `GRAVITEE_HTTP_EXPOSEPIPELINES` | List pipelines on `/v1/models` and accept pipeline ids as `model`. |
| `http.secured` | `false` | `GRAVITEE_HTTP_SECURED` | TLS on the HTTP port. |
| `http.ssl.*` | unset | `GRAVITEE_HTTP_SSL_*` | Same structure as `grpc.ssl.*`. |
| `http.haproxy.*` | unset | `GRAVITEE_HTTP_HAPROXY_*` | Same structure as `grpc.haproxy.*`. |

### `http.auth.*` (Bearer tokens)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `http.auth.enabled` | `false` | `GRAVITEE_HTTP_AUTH_ENABLED` | Require `Authorization: Bearer <token>`. Startup fails when enabled with no tokens. |
| `http.auth.type` | `bearer` | `GRAVITEE_HTTP_AUTH_TYPE` | Only `bearer` is supported. |
| `http.auth.tokens` | unset | `GRAVITEE_HTTP_AUTH_TOKENS_<n>` | List of accepted API keys. |

### `ai.*` (workspace, model cache, downloads)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `ai.workspace.path` | unset | `GRAVITEE_AI_WORKSPACE_PATH` | Workspace YAML loaded at boot. Unset starts an empty server. |
| `ai.models.path` | `${gravitee.home}/models` | `GRAVITEE_AI_MODELS_PATH` | Model cache. Blank falls back to `~/.cache/gravitee-singularitee/models`; `run-server.sh` sets that path explicitly so `mvn clean` never removes weights. |
| `ai.huggingface.token` | unset | `GRAVITEE_AI_HUGGINGFACE_TOKEN` | Token for gated repos. Blank falls back to the `HF_TOKEN` environment variable. |
| `ai.huggingface.download.chunkSize` | `10485760` | `GRAVITEE_AI_HUGGINGFACE_DOWNLOAD_CHUNKSIZE` | Bytes per HTTP Range request in the parallel download path. |
| `ai.huggingface.download.parallelism` | `8` | `GRAVITEE_AI_HUGGINGFACE_DOWNLOAD_PARALLELISM` | Concurrent Range requests per file. Peak buffered memory is `parallelism x chunkSize`. |
| `ai.huggingface.download.chunkedThreshold` | `2 x chunkSize` | `GRAVITEE_AI_HUGGINGFACE_DOWNLOAD_CHUNKEDTHRESHOLD` | Files at least this large use the parallel path; smaller files stream over one connection. |
| `ai.huggingface.download.connectTimeout` | `15000` | `GRAVITEE_AI_HUGGINGFACE_DOWNLOAD_CONNECTTIMEOUT` | Milliseconds to wait establishing a connection to the hub or its CDN before the attempt is retried. |
| `ai.huggingface.download.idleTimeout` | `300` | `GRAVITEE_AI_HUGGINGFACE_DOWNLOAD_IDLETIMEOUT` | Download time check, in seconds: a transfer that receives no data for this long is aborted and retried against a fresh URL. Raise it for very large weights on a slow link; lower it to fail fast. |
| `ai.huggingface.download.progressInterval` | `5000` | `GRAVITEE_AI_HUGGINGFACE_DOWNLOAD_PROGRESSINTERVAL` | How often, in milliseconds, the per-file download progress bar (percentage and MiB) is logged. Raise it to quiet the log on long downloads; lower it for a snappier bar. |

### `ai.streaming.*`

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `ai.streaming.buffer-capacity` | `256` | `GRAVITEE_AI_STREAMING_BUFFERCAPACITY` | Tokens a slow client may lag behind before its stream is cancelled instead of buffering without bound. |

### `ai.todos.*` (cross-request plan persistence)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `ai.todos.session-ttl` | `1800` | `GRAVITEE_AI_TODOS_SESSIONTTL` | Idle timeout in seconds for a todo session keyed by the request's cache key (`prompt_cache_key` or `user`). `0` disables persistence. |
| `ai.todos.session-max-entries` | `10000` | `GRAVITEE_AI_TODOS_SESSIONMAXENTRIES` | Maximum sessions held at once. |

### `ai.conversations.*` (Responses API continuation)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `ai.conversations.ttl` | `3600` | `GRAVITEE_AI_CONVERSATIONS_TTL` | Idle timeout in seconds for conversations stored for `previous_response_id`. `0` disables. |
| `ai.conversations.max-entries` | `10000` | `GRAVITEE_AI_CONVERSATIONS_MAXENTRIES` | Maximum stored conversations. |

### `ai.tools.*` (server-tool display names)

Presentation only: the model and the wire see these names, while internal identity, stored state and the proto contract never change with them. Two tools may not share a name.

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `ai.tools.set-todos.name` | `set_todos` | `GRAVITEE_AI_TOOLS_SETTODOS_NAME` | Display name of the plan-install tool. |
| `ai.tools.complete-todo.name` | `complete_todo` | `GRAVITEE_AI_TOOLS_COMPLETETODO_NAME` | Display name of the item-completion tool. |
| `ai.tools.ask-user.name` | `ask_user` | `GRAVITEE_AI_TOOLS_ASKUSER_NAME` | Display name of the pause-for-user tool. A client delegating it must declare the configured name. |

### `http.events.*` (wire event type strings)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `http.events.progress-type` | `gravitee.progress` | `GRAVITEE_HTTP_EVENTS_PROGRESSTYPE` | The `type` string of progress events on the Responses API, including the elicitation payload they carry. |

### `plugins.*` and `license.*` (step plugins)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `plugins.path` | `${gravitee.home}/plugins` | `GRAVITEE_PLUGINS_PATH` | Directory whose plugin zips are loaded as gravitee step plugins by the node's plugin registry (parent-first classloading). The twelve core steps must be present or startup fails. |
| `plugins.workDir` | temp dir | `GRAVITEE_PLUGINS_WORKDIR` | Where plugin zips are extracted. The distribution sets `${gravitee.home}/.plugins-work`. |
| `step.<id>.enabled` | `true` | `GRAVITEE_STEP_<ID>_ENABLED` | Switch off a step plugin by its manifest id (for example `step.route.enabled: false`). |
| `license.key` | `${gravitee.home}/license/license.key` | `GRAVITEE_LICENSE_KEY` | Platform license file. Absent means the OSS license: every core step registers. A step whose `plugin.properties` declares a `feature=` must have that feature listed in the license's `features` to register; a workspace declaring such a step without the feature fails to load with the feature named. |

### `ai.vllm.*` (deployment-wide GPU topology)

A model's own `vllm:` value wins; these apply when the workspace leaves the field unset; when both are unset vLLM decides.

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `ai.vllm.tensor-parallel-size` | unset | `GRAVITEE_AI_VLLM_TENSORPARALLELSIZE` | GPUs to shard each layer across. |
| `ai.vllm.pipeline-parallel-size` | unset | `GRAVITEE_AI_VLLM_PIPELINEPARALLELSIZE` | Pipeline stages. |
| `ai.vllm.distributed-executor-backend` | unset | `GRAVITEE_AI_VLLM_DISTRIBUTEDEXECUTORBACKEND` | `mp` or `ray`. |

### `services.core.http.*` (management API)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `services.core.http.enabled` | `true` | `GRAVITEE_SERVICES_CORE_HTTP_ENABLED` | Internal HTTP API: health, metrics, configuration introspection. |
| `services.core.http.port` | `18092` | `GRAVITEE_SERVICES_CORE_HTTP_PORT` | Management port. Prometheus scrapes `/_node/metrics/prometheus` here. |
| `services.core.http.host` | `localhost` | `GRAVITEE_SERVICES_CORE_HTTP_HOST` | Bind address. |
| `services.core.http.authentication.type` | `basic` | `GRAVITEE_SERVICES_CORE_HTTP_AUTHENTICATION_TYPE` | Auth scheme on the management API. |
| `services.core.http.authentication.users.<name>` | `admin: adminadmin` | `GRAVITEE_SERVICES_CORE_HTTP_AUTHENTICATION_USERS_<NAME>` | Management users. Change the default before exposing the port. |

### `services.metrics.*` and `services.monitoring.*`

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `services.metrics.enabled` | `true` | `GRAVITEE_SERVICES_METRICS_ENABLED` | Bind a Micrometer registry. |
| `services.metrics.prometheus.enabled` | `true` | `GRAVITEE_SERVICES_METRICS_PROMETHEUS_ENABLED` | Expose it in Prometheus format on the management port. Both flags must be on for the `<name-prefix>_*` meters. |
| `services.metrics.name-prefix` | `ai` | `GRAVITEE_SERVICES_METRICS_NAMEPREFIX` | Prefix for the inference meter names (`<prefix>.tokens`, `<prefix>.pipeline.requests`, ...); Prometheus renders it as `<prefix>_*`. The OpenTelemetry span prefix is separate (`services.opentelemetry.name-prefix`). |
| `services.monitoring.gpu.enabled` | `true` | `GRAVITEE_SERVICES_MONITORING_GPU_ENABLED` | Poll `nvidia-smi` and publish `gpu_*` gauges. No-op without the NVIDIA driver. |
| `services.monitoring.gpu.delay` | `5000` | `GRAVITEE_SERVICES_MONITORING_GPU_DELAY` | Poll interval. |
| `services.monitoring.gpu.unit` | `MILLISECONDS` | `GRAVITEE_SERVICES_MONITORING_GPU_UNIT` | Unit of `delay`. |

### `services.opentelemetry.*` (tracing)

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `services.opentelemetry.enabled` | `false` | `GRAVITEE_SERVICES_OPENTELEMETRY_ENABLED` | Emit a `SERVER` span per RPC with nested `<name-prefix>.*` spans. |
| `services.opentelemetry.verbose` | `false` | `GRAVITEE_SERVICES_OPENTELEMETRY_VERBOSE` | Add content-heavy attributes to spans (input/output values, message text, embedded text). Off keeps spans free of prompt/response content. |
| `services.opentelemetry.openinference` | `true` | `GRAVITEE_SERVICES_OPENTELEMETRY_OPENINFERENCE` | Also emit OpenInference semantic attributes (`openinference.span.kind`, `session.id`, `llm.*`, `embedding.*`), so OpenInference-aware tools render the traces natively. These keys are fixed and never carry the name-prefix. |
| `services.opentelemetry.name-prefix` | `singularitee` | `GRAVITEE_SERVICES_OPENTELEMETRY_NAMEPREFIX` | Prefix for the span names and the engine's own span attributes (`<prefix>.pipeline`, `<prefix>.step`, `<prefix>.session`, ...). Does not apply to OpenInference keys; Micrometer metric names are unaffected. |
| `services.opentelemetry.exporter.endpoint` | `http://localhost:4317` | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_ENDPOINT` | OTLP collector. |
| `services.opentelemetry.exporter.protocol` | `grpc` | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_PROTOCOL` | `grpc` (4317) or `http/protobuf` (4318). |
| `services.opentelemetry.exporter.compression` | unset | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_COMPRESSION` | e.g. `gzip`. |
| `services.opentelemetry.exporter.timeout` | unset | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_TIMEOUT` | Export timeout in ms. |
| `services.opentelemetry.exporter.headers` | unset | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_HEADERS_<n>` | Extra OTLP headers as `name=value` entries. |
| `services.opentelemetry.exporter.ssl.trustAll` | `false` | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_SSL_TRUSTALL` | Skip collector certificate verification. |
| `services.opentelemetry.exporter.ssl.verifyHost` | `true` | `GRAVITEE_SERVICES_OPENTELEMETRY_EXPORTER_SSL_VERIFYHOST` | Verify the collector host name. |

`services.tracing.otel.*` is accepted as an alias for `services.opentelemetry.*`.

### Node

| Key | Default | Env var | Purpose |
| --- | --- | --- | --- |
| `gravitee.home` | unset (required) | `GRAVITEE_HOME` | Distribution directory. `Bootstrap` refuses to start without it; `bin/gravitee.sh` derives it from its own location. |
| `gracefulShutdown.delay` | `0` | `GRAVITEE_GRACEFULSHUTDOWN_DELAY` | Delay before shutdown completes. |
| `gracefulShutdown.unit` | `MILLISECONDS` | `GRAVITEE_GRACEFULSHUTDOWN_UNIT` | Unit of `delay`. |
| `kubernetes.enabled` | `false` | `GRAVITEE_KUBERNETES_ENABLED` | gravitee-node Kubernetes integration; not needed standalone. |

### JVM system properties outside `gravitee.yml`

| Property | Default | Purpose |
| --- | --- | --- |
| `vllm4j.venv` | unset | Python virtualenv the vLLM engine loads CPython from. The only way vLLM4j finds it; `run-server.sh` and `docker/cuda/cuda-env.sh` set it. |
| `vllm4j.attentionBackend` | auto | vLLM attention backend. On pre-Ampere GPUs the server pins `TRITON_ATTN` unless this or `VLLM4J_ATTENTION_BACKEND` is set. |
| `gliner4j.ggml.rawQkv` | `false` | Skips the f32→f16 cast before the `libggml-deberta` attention op and feeds q/k/v straight from the projection GEMMs, trading the cast launches for extra f32 traffic through the kernel's key-tile re-reads. Effect is architecture-dependent; leave at the default unless you have measured a net win on your hardware. |
| `gliner4j.ggml.eventOrdering` | `true` | Cross-backend stream synchronization for the `libggml-deberta` plugin's `ggml_backend_sched` splits (see the `0001-ggml-sched-cross-backend-events.patch` in GLiNER4j). Setting it to `false` falls back to host-side syncs between splits; leave at the default. |

### Engine environment variables (not `gravitee.yml` keys)

These are read straight from the process environment by the engine adapters.

| Variable | Default | Purpose |
| --- | --- | --- |
| `HF_TOKEN` | unset | Fallback for `ai.huggingface.token`. |
| `LLAMA_CPP_LIB_PATH` | unset | Directory llamaj.cpp loads the llama.cpp natives from before falling back to `~/.llama.cpp`; GLiNER ggml bundles use the same natives and look for the `libggml-deberta` plugin in its `plugins/` sub-directory. The CUDA image sets it. |
| `GLINER4J_DEBERTA_KERNEL` | `auto` | Attention kernel of the `libggml-deberta` plugin: `auto` times every variant the GPU holds at first use (logged as `DEBERTA plugin: autotune …`) and keeps the fastest correct one; `turing`, `ampere` (shared-memory kernels) or `reg64x32`, `reg64x64`, `reg128x32`, `reg128x64` (register-resident) force one. `GLINER4J_DEBERTA_AUTOTUNE=0` keeps the static rule instead. |
| `VLLM4J_ATTENTION_BACKEND` | unset | Same as `-Dvllm4j.attentionBackend`. |
| `GRAVITEE_ONNX_INTRA_OPS_NUM_THREADS` | CPU count | Intra-op threads for ONNX classifier, embedding and reranker sessions. |
| `GRAVITEE_ONNX_RERANK_MAX_BATCH_TOKENS` | `32768` | Padded-token budget per ONNX reranker batch (rows are capped at 256 regardless). |
| `GRAVITEE_ONNX_BATCH_MAX` | `16` | Max items fused into one ONNX classifier or embedding batch. |
| `GRAVITEE_ONNX_BATCH_MAX_TOKENS` | `2048` | Max summed estimated tokens per batch. |
| `GRAVITEE_ONNX_BATCH_BUCKET_TOKENS` | `128` | Short/long bucket boundary; items at or below it never share a batch with longer ones. |
| `GRAVITEE_ONNX_BATCH_LINGER_MS` | `5` | How long a partial batch waits for more items. |
| `GRAVITEE_GLINER_BATCH_MAX`, `_MAX_TOKENS`, `_BUCKET_TOKENS`, `_LINGER_MS` | same as ONNX | The same micro-batcher knobs for the GLiNER runtimes. |
| `GRAVITEE_GLINER_ENCODER_INTRA_OP_THREADS` | all cores | Intra-op threads for the GLiNER encoder and span sessions. |
| `GRAVITEE_GLINER_ENCODER_INTER_OP_THREADS` | cores/2, min 2 | Inter-op threads for the same sessions. |
| `GRAVITEE_GLINER_SCORING_INTRA_OP_THREADS` | cores/4, min 2 | Intra-op threads for the scoring heads. |
| `GRAVITEE_GLINER_SCORING_INTER_OP_THREADS` | `1` | Inter-op threads for the scoring heads. |
| `GRAVITEE_GLINER_EXECUTION_PROVIDER` | auto | Force `cuda` or `cpu` (ONNX execution provider, or GPU offload vs CPU for llama.cpp/ggml GLiNER bundles). |
| `GRAVITEE_GLINER_ALLOW_SPINNING` | ORT default | `0` or `false` stops the ORT thread pools busy-waiting; useful when compute is GPU-bound. |
| `GRAVITEE_GLINER_ORT_PROFILING_DIR` | unset | Write per-node ONNX profiling traces here on session close. Diagnostic only. |
| `GRAVITEE_GLINER_ORT_PROFILING_SECONDS` | unset | Flush the traces this many seconds after load instead of at close. |

### `run-server.sh`

| Flag or variable | Default | Purpose |
| --- | --- | --- |
| `--workspace FILE` | `examples/llama/qwen3-0.6b.yaml` | Workspace to load; relative to the repo or absolute. Include fragments under `examples/modular/{models,pipelines,templates}/` are refused. |
| `--port PORT` | `8080` | HTTP API port. The script always enables the HTTP API. |
| `--venv DIR` | `$VLLM_VENV`, then `~/.venv-gravitee-ai/.venv` | vLLM virtualenv, passed as `-Dvllm4j.venv`. Only consulted when the workspace is a vLLM one. |
| `--debug` | off | Switch the `io.gravitee.singularitee.engine.pipeline` and `.inference` loggers to TRACE in the distribution's `logback.xml`: rendered prompts and streamed tokens are logged. |
| `--list` | | Print every runnable example workspace and exit. |
| `VLLM_VENV` | unset | Same as `--venv`. |
| `NATIVE_DIR` | `~/.llama.cpp` | Directory prepended to `LD_LIBRARY_PATH` so the ggml backends resolve on Linux. |
| `JAVA_OPTS` | unset | Extra JVM options, appended to the script's own. |
| `HF_TOKEN` | unset | Passed through to the server. |

The script exports `GRAVITEE_HTTP_ENABLED=true`, `GRAVITEE_HTTP_PORT`, `GRAVITEE_AI_WORKSPACE_PATH` and `GRAVITEE_AI_MODELS_PATH=~/.cache/gravitee-singularitee/models`, then execs `target/distribution/bin/gravitee.sh`. For vLLM workspaces it also preloads `libjsig` and `libpython` through `LD_PRELOAD`.

### `install.sh`

| Flag or variable | Default | Purpose |
| --- | --- | --- |
| `--port PORT` | `8080` | Forwarded to `run-server.sh`. |
| `--skip-build` | off | Do not run `mvn clean install -DskipTests`. |
| `--no-run` | off | Set everything up and exit without starting the server. |
| `--llama-version bNNNN` | `b10276` | llama.cpp release to download. Must match the llamaj.cpp binding the build was generated against. |
| `LLAMA_CPP_VERSION` | `b10276` | Same as `--llama-version`. |
| `LLAMA_CPP_SHA256` | pinned per host for the default version | Expected digest of the release archive. Required to verify a non-default version; without it the download is installed with a warning. |
| `NATIVE_DIR` | `~/.llama.cpp` | Where the natives are extracted. A `.llama-cpp-version` stamp records the installed release; the directory is replaced wholesale on a version change. |
| `HF_TOKEN` | unset | Hinted at the end of setup; read by the server. |

`install.sh` runs `examples/llama/gpt-oss-20b.yaml` when it starts the server. Supported hosts are macOS arm64 and Linux x86_64.

### `scripts/setup-venv.sh`

| Flag | Default | Purpose |
| --- | --- | --- |
| `-b metal\|cuda\|cpu` | required | Backend. `metal` and `cpu` build the vLLM core from source and `metal` adds the vllm-metal wheel; `cuda` installs the wheel matching the driver (the `+cu129` wheel below driver 580). |
| `-d DIR` | `~/.venv-gravitee-ai` | Parent directory; the venv is created at `DIR/.venv`. |
| `-v VERSION` | `3.12` | Python version, always a uv-managed standalone build. |

The script pins vLLM `0.26.0`, `xgrammar 0.2.2` and `apache-tvm-ffi 0.1.12`. The Maven profiles `-Pvllm-integration,metal|cuda|cpu` invoke it during `initialize`; `-Dvllm.venv.path=` points the build at an existing venv and `vllm.setupVenv.skip=true` (the default) skips it.

### Taskfile variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port for every `run:*` and demo task. |
| `VLLM=1` | unset | Run the `examples/vllm/` twin of a `run:*` model task instead of the llama.cpp file. |
| `BASE_URL` | `http://localhost:$PORT/v1` | Exported to the demo scripts. |

## Notes

- `grpc.secured: true` forces `grpc.alpn` on: gRPC over TLS needs ALPN to negotiate HTTP/2.
- `grpc.auth.enabled` and `http.auth.enabled` fail startup when no users or tokens are configured. A non-loopback bind with auth off only warns.
- Numeric `ai.*` keys that fail to parse fall back to their default with a warning; `ai.vllm.*` values below 1 are treated as unset.
- `ai.models.path` blank is not the same as unset in `gravitee.yml`: the shipped file sets `${gravitee.home}/models`, which lives under `target/` and is removed by `mvn clean`. Point it somewhere stable for development; `run-server.sh` already does.
- Java reads zero entries from an openssl `pkcs12 -export -nokeys` bundle. Build truststores with `keytool -importcert`; `scripts/gen-dev-certs.sh` does.
- `-Dlogback.configurationFile` does not stick: gravitee-node reloads `${gravitee.home}/config/logback.xml` itself. Edit that file (or use `run-server.sh --debug`).

## See also

- [Getting started](../getting-started/README.md): the handful of keys needed for a first run.
- [Observability](../operations/observability/README.md): what the `services.metrics` and `services.opentelemetry` blocks produce.
- [Deployment](../operations/deployment/README.md): the container images and the environment they set.
- [gRPC API](../api/grpc/README.md) and [HTTP API](../api/http/README.md): auth and TLS as seen from a caller.
- [Remote and multi-server](../guides/remote-and-multi-server/README.md): `grpc.client.ssl.*` in use.
