# Remote Models & Multi-Server Composition

> Run pipelines on the client with `remote_*` model proxies over gRPC, composing models that live on one or many servers.

## Overview
A workspace does not have to load its models in-process. The `gravitee-singularitee-engine-remote`
module lets a *client* workspace declare models of type `remote_llm`, `remote_classifier`,
`remote_embedding`, or `remote_reranker` — each a thin gRPC proxy to a model already published
on a Singularitee server. `ClientPipelineExecutor` reads such a workspace, opens one
`SingulariteeClient` per endpoint in the `remote:` block, validates each remote model with a
`GetModel` RPC, and wires the same `PipelineExecutor` used server-side — so a pipeline DAG runs
locally while every model call travels over gRPC. This is how one pipeline composes models split
across backends (e.g. ONNX classifiers on a safety server, generation on a CUDA vLLM server).

There are three ways to execute against a server:

| Mode | Where the DAG runs | Where the models run | Entry point |
| --- | --- | --- | --- |
| Server-side pipeline | server | server | `InferPipeline` RPC (`--pipeline-id`) |
| Direct model | — (no pipeline) | server | `Infer` / `Classify` / `Embed` RPC (`--model-id`) |
| Client-side pipeline | client | one or more servers | `ClientPipelineExecutor` (`--workspace`) |

## Key types
- `ClientPipelineExecutor` — static factory (`create(Path)`, `createFromString(String[, Vertx, templatesPath])`, `create(WorkspaceRequests, Vertx)`); returns a `Result(executor, clients, pipelineIds, modelRegistry)` that is `AutoCloseable` (closes all gRPC clients).
- `RemoteTextGenEngine` — `TextGenEngine` proxy over the streaming `Infer` RPC; caches the remote model's chat template / BOS / EOS from `GetModel`; fully non-blocking.
- `RemoteClassifierEngine` — `ClassifierEngine` proxy over the `Classify` RPC (carries the remote `task` metadata).
- `RemoteEmbeddingEngine` — `EmbeddingEngine` proxy over the `Embed` RPC.
- `RemoteRerankerEngine` — `RerankerEngine` proxy over the `TextRerank` RPC; the server decides cross-encoder scoring vs. embed-plus-cosine fallback.
- `RemotePipelineCallback` — `SubPipelineStepExecutor.PipelineExecutorCallback` that delegates a whole sub-pipeline to a remote server via `InferPipeline`.
- `ClientLocalModelRegistrar` — registers pure-Java engines (`regex`, `composite_classifier`) that run in-process on either side, no gRPC and no native library.
- `LocalStreamRegistry` — in-memory `StreamRegistry` mapping model id + sequence id to token-capture streams, so streamed remote tokens reach `InferStepExecutor`.
- `ModelType` — `isRemote()` is true for the four `REMOTE_*` constants; `isClientLocal()` for `REGEX` / `COMPOSITE_CLASSIFIER`.

## Usage
Single-server client — one `default:` endpoint, every remote model resolves to it
(`examples/modular/client-llamacpp.yaml`, `examples/modular/client-cot.yaml`):

```yaml
workspace:
  name: client-llamacpp
  remote:
    default:
      host: 127.0.0.1
      port: 9090
  models:
    - id: llm
      type: remote_llm
  includes:
    pipelines:
      - infer.yaml
```

Multi-server client — named endpoints, each model picks its `server:`
(`examples/modular/client-safety-vllm.yaml`):

```yaml
workspace:
  name: client-safety-vllm
  remote:
    servers:
      - id: safety
        host: 127.0.0.1
        port: 9092
      - id: vllm
        host: 127.0.0.1
        port: 9091
  models:
    - id: pii
      type: remote_classifier
      server: safety
    - id: toxicity
      type: remote_classifier
      server: safety
    - id: llm
      type: remote_llm
      server: vllm
  includes:
    pipelines:
      - pii-redact.yaml
      - toxicity-guard.yaml
```

Authenticated endpoint — Basic auth credentials sent as gRPC metadata
(`examples/modular/client-safety-llamacpp.yaml`):

```yaml
  remote:
    servers:
      - id: pii
        host: 127.0.0.1
        port: 9100
        username: pii
        password: <password>
```

Mutual TLS between servers — the endpoint opts in with `ssl: true`, while the certificates
come from `grpc.client.ssl.*` in `gravitee.yml`, never from the workspace
(`examples/modular/server-llm-mtls.yaml` → `server-safety-mtls.yaml`):

```yaml
  remote:
    servers:
      - id: safety
        host: localhost # must match the server certificate's SAN
        port: 9092
        ssl: true
```

```yaml
# gravitee.yml (or the GRAVITEE_GRPC_CLIENT_SSL_* variables) on the CALLING server
grpc:
  client:
    ssl:
      truststore:              # verify the callee (a private CA)
        type: PKCS12
        path: /certs/ca.p12
        password: changeit
      keystore:                # our identity — this is what makes it MUTUAL
        type: PKCS12
        path: /certs/client.p12
        password: changeit
```

The runnable pair ships with the repo:

```bash
task certs             # once — throwaway CA + server/client certificates (certs/, gitignored)
task run:mtls-safety   # shell 1 — callee, gRPC 9092, clientAuth REQUIRED
task run:mtls-llm      # shell 2 — caller, presents the client certificate
task mtls-check        # shell 3 — classify through the channel, then show an anonymous caller refused
```

`mtls-check` proves both directions — only together do they demonstrate *mutual* TLS:

1. A classification through the caller returns PII entities: a real RPC crossed the
   authenticated channel to the callee's model.
2. An `openssl s_client` probe with **no** client certificate is refused — the expected
   output is `SSL alert number 40` (handshake_failure), the callee rejecting the
   anonymous caller. The `Cipher is …` line after it just shows how far the handshake
   got before dying. The probe forces `-tls1_2` because TLS 1.3 rejects after the
   point where `s_client` prints its summary, which would hide the refusal.

Delegating a sub-pipeline to another server — a `sub_pipeline` step with a `server:` field
routes the whole nested DAG through `RemotePipelineCallback` instead of executing it locally:

```yaml
      - id: moderate
        type: sub_pipeline
        sub_pipeline:
          pipeline_id: toxicity-guard-pipeline
          server: safety          # id from the remote: block; omit for local execution
```

Run it:

```bash
# Start the servers first, then:
java io.gravitee.singularitee.cli.Main \
     --workspace examples/modular/client-safety-vllm.yaml \
     --pipeline pii-redact-pipeline
```

Or embed it programmatically:

```java
try (var result = ClientPipelineExecutor.create(Path.of("examples/modular/client-safety-vllm.yaml"))) {
  result.executor().executePipeline(request, responseStream, callerContext);
}
```

## Options

### `remote:` endpoint (`WorkspaceDefinition.RemoteEndpoint`)
| Field | Default | Purpose |
| --- | --- | --- |
| `id` | — (`default` for the `default:` entry) | Name that models/steps reference via `server:`. |
| `host` / `port` | — | gRPC coordinates of the remote Singularitee. |
| `http2_keep_alive_timeout` | `-1` (keep alive forever) | Seconds an idle HTTP/2 connection is held open; any positive value is passed to Vert.x `setHttp2KeepAliveTimeout`. |
| `username` / `password` | none | Optional HTTP Basic credentials sent as gRPC metadata; auth is on when `username` is non-blank. |
| `ssl` | `false` | Reach the endpoint over TLS (ALPN negotiates HTTP/2). Trust and client-certificate material comes from `grpc.client.ssl.*` in `gravitee.yml` — with no configuration the JVM default trust store applies. Configure `grpc.client.ssl.keystore` to present a client certificate (mutual TLS) to a server running `clientAuth: REQUIRED`. |

### `grpc.client.ssl.*` (`gravitee.yml` — the calling server)
| Key | Default | Purpose |
| --- | --- | --- |
| `truststore.type` / `.path` / `.password` | JVM default trust store | Verify the callee's certificate: `PEM`, `JKS` or `PKCS12`. Needed for any private CA. |
| `keystore.type` / `.path` / `.password` | none | The client certificate presented to the callee — configuring it is what makes the connection mutual. `PEM` additionally needs `keystore.keyPath`. |
| `trustAll` | `false` | Skip server verification entirely. Development only; logs a WARN. |
| `verifyHostname` | `true` | Require the certificate to match the host dialled. |

Resolved once per deployment, and only when at least one endpoint sets `ssl: true`.

### Remote model definition
| Field | Default | Purpose |
| --- | --- | --- |
| `id` | — | Must match the model id published on the remote server (validated with `GetModel` at startup). |
| `type` | — | One of `remote_llm`, `remote_classifier`, `remote_embedding`, `remote_reranker`. |
| `server` | `default` | Endpoint id from the `remote:` block; startup fails if neither `server:` nor a `default` endpoint exists. |
| `name` | the `id` | Display name in the model registry. |

## Notes
- **Endpoints are plaintext unless `ssl: true`**: the default is unencrypted gRPC, so an endpoint
  carrying `username`/`password` sends those credentials in the clear. That is fine for the
  loopback topology these examples use, but set `ssl: true` for anything crossing a host —
  the server logs a warning when it sees credentials on a plaintext endpoint.
- **Mutual TLS**: `grpc.client.ssl.{truststore,keystore}` in `gravitee.yml` supply the outbound
  trust and client-certificate material — the deployment-level counterpart of the server's
  `grpc.ssl.*`. See `examples/modular/server-*-mtls.yaml` (`task certs`, `task run:mtls-safety`,
  `task run:mtls-llm`, `task mtls-check`). Build dev truststores with `keytool`, not
  `openssl pkcs12 -nokeys` — Java reads zero entries from openssl's cert-only bundles, which
  silently yields an empty truststore.
- **Startup is fail-fast**: every remote model is checked with a blocking `GetModel` call — a missing model or unreachable server throws `IllegalStateException` before the executor is built.
- **Remote model types carry no engine config**: `ModelType.toModelLoadRequest` returns `null` for `REMOTE_*` — sampling and prompting are configured on the *serving* workspace and in pipeline steps, not on the proxy.
- **Chat templating happens client-side for `remote_llm`**: `RemoteTextGenEngine` fetches the remote model's chat template, BOS, and EOS from `GetModel`, so pipeline steps can render prompts locally exactly as the server would.
- **Token dispatch is explicit**: `ClientPipelineExecutor` wires a token dispatcher from each `remote_llm` into `LocalStreamRegistry`; without it the `TokenCaptureStream` registered by `InferStepExecutor` never receives tokens and the step hangs.
- **Embedded Vert.x callers must pass their Vert.x**: inside a Gravitee gateway plugin, use `createFromString(yaml, vertx)` — creating a standalone `Vertx.vertx()` under a parent-first classloader causes event-loop isolation where gRPC response handlers never fire.
- **Client-local models run everywhere**: `regex` and `composite_classifier` are pure Java (no GPU, no native lib) and are registered by `ClientLocalModelRegistrar` in two passes so composites can reference earlier-declared simple engines.
- **One backend per process in production**: don't put llama.cpp, ONNX, and vLLM models on the same server — give each backend its own server (see `examples/README.md`) and compose them from a client workspace. The `examples/` pipelines reference stable logical ids (`llm`, `pii`, `toxicity`, `router`) so the same DAG runs unchanged over any backend split.
- **Close the `Result`**: it holds one gRPC client per endpoint; `close()` shuts them all down.
- **Client-side execution is not traced**: OpenTelemetry spans are only emitted by the server-side path (see Observability).

## See also
- [gRPC API & Client](../grpc-api-and-client/README.md) — the `SingulariteeClient` and RPCs these proxies call.
- [Sub-Pipelines](../sub-pipelines/README.md) — the `sub_pipeline` step that `server:` delegates remotely.
- [Workspaces](../workspaces/README.md) — the YAML model/pipeline/includes structure shared by servers and clients.
- [Observability](../observability/README.md) — server-side tracing and metrics for the remote calls.
