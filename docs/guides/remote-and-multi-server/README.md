# Remote models and multi-server composition

> Run a pipeline on one process while its models live on others: `remote_*` model types are gRPC proxies to models published elsewhere, so one DAG can span a safety server and a generation server.

## Overview

A workspace does not have to load its models in-process. A model of type `remote_llm`,
`remote_classifier`, `remote_embedding` or `remote_reranker` is a thin proxy that calls the
`Infer`, `Classify`, `Embed` or `TextRerank` RPC of the endpoint named by `server:` (or the
`default:` endpoint). The pipeline executor is the same one the server uses, so every step
type, halt and finish reason behaves identically; only the model calls travel over gRPC.

Two ways to run such a workspace:

| Mode | How | Notes |
| --- | --- | --- |
| As a server | `./run-server.sh --workspace examples/modular/client-safety-llamacpp.yaml` | `WorkspaceLoaderComponent` registers the remote proxies like any model; the pipeline is published over gRPC and HTTP. Full tracing and metrics. |
| Embedded | `ClientPipelineExecutor.create(path)` from `gravitee-singularitee-engine-remote` | For a gateway or another JVM; no gRPC server of its own, no tracing. |

The standard topology keeps one backend per process (llama.cpp, vLLM and ONNX Runtime each
load their own native libraries) and composes across processes from a client workspace.

## Key types

| Type | Module | Purpose |
| --- | --- | --- |
| `RemoteTextGenEngine` | `engine-remote` | `TextGenEngine` over the streaming `Infer` RPC; reads chat template, BOS/EOS and `input_modalities` from a lazy `GetModel` probe. |
| `RemoteClassifierEngine`, `RemoteEmbeddingEngine`, `RemoteRerankerEngine` | `engine-remote` | Proxies over `Classify`, `Embed`, `TextRerank`. |
| `RemotePipelineCallback` | `engine-remote` | Runs a `sub_pipeline` step on a remote endpoint via `InferPipeline`. |
| `ClientPipelineExecutor` | `engine-remote` | Builds an executor from a workspace; `Result(executor, clients, pipelineIds, modelRegistry)` is `AutoCloseable`. |
| `ClientLocalModelRegistrar` | `engine-remote` | Registers `regex` and `composite_classifier`, which run in-process anywhere. |
| `WorkspaceDefinition.RemoteEndpoint`, `ModelType.isRemote()` | `workspace` | The `remote:` block and the four remote constants. |
| `GrpcClientSslConfig` | `standalone` | `grpc.client.ssl.*`: outbound trust and client certificate. |

## Usage

Single server, one `default:` endpoint (`examples/modular/client-llamacpp.yaml`,
`client-cot.yaml`):

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

Two servers, named endpoints (`examples/modular/client-safety-llamacpp.yaml`):

```yaml
workspace:
  name: client-safety-llamacpp
  remote:
    servers:
      - id: safety
        host: 127.0.0.1
        port: 9092
      - id: llamacpp
        host: 127.0.0.1
        port: 9090
  models:
    - id: pii
      type: remote_classifier
      server: safety
    - id: toxicity
      type: remote_classifier
      server: safety
    - id: llm
      type: remote_llm
      server: llamacpp
  includes:
    pipelines:
      - pii-redact.yaml
      - toxicity-guard.yaml
```

Run the servers first, then the client on its own ports:

```bash
./run-server.sh --workspace examples/modular/server-llamacpp.yaml                       # shell 1, gRPC 9090
GRAVITEE_GRPC_PORT=9092 ./run-server.sh --port 8082 --workspace examples/modular/server-safety.yaml   # shell 2
GRAVITEE_GRPC_PORT=9190 ./run-server.sh --port 8180 --workspace examples/modular/client-safety-llamacpp.yaml  # shell 3

curl -s localhost:8180/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"pii-redact-pipeline","messages":[{"role":"user","content":"Mail me at jane@example.com"}]}'
```

Embedded:

```java
try (var result = ClientPipelineExecutor.create(Path.of("examples/modular/client-safety-llamacpp.yaml"))) {
  result.executor().executePipeline(request, responseStream, callerContext);
}
```

Inside a Vert.x application pass its instance: `ClientPipelineExecutor.createFromString(yaml, vertx)`.

Credentials and TLS on an endpoint:

```yaml
remote:
  servers:
    - id: safety
      host: localhost         # must match the server certificate's SAN
      port: 9092
      ssl: true
      username: pii           # HTTP Basic over gRPC metadata, optional
      password: <password>
```

The certificates come from `gravitee.yml` on the calling process, never from the workspace:

```yaml
grpc:
  client:
    ssl:
      truststore: { type: PKCS12, path: /certs/ca.p12, password: changeit }      # verify the callee
      keystore:   { type: PKCS12, path: /certs/client.p12, password: changeit }  # present a client certificate (mutual TLS)
```

The repo ships a runnable mutual-TLS pair (`examples/modular/server-llm-mtls.yaml` calling
`server-safety-mtls.yaml`):

```bash
task certs             # throwaway CA and certificates under certs/
task run:mtls-safety   # callee, gRPC 9092, clientAuth REQUIRED
task run:mtls-llm      # caller, presents the client certificate
task mtls-check        # a classify through the channel, then an anonymous caller refused
```

Delegating a whole sub-graph to another server:

```yaml
- id: moderate
  type: sub_pipeline
  next_step: generate
  config:
    pipeline_id: toxicity-guard-pipeline
    server: safety          # id from the remote: block; omit for local execution
```

## Options

Full tables: [Workspaces](../../workspaces/README.md),
[remote model types](../../reference/models/README.md),
[Configuration](../../reference/configuration.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `remote.default` / `remote.servers[]` | endpoint | unset | One anonymous endpoint, or named ones. |
| `endpoint.id` | string | `default` | Referenced by `server:` on models, steps and pipelines. |
| `endpoint.host`, `endpoint.port` | string, int | required | gRPC coordinates. |
| `endpoint.ssl` | bool | `false` | TLS with ALPN; trust and client certificate from `grpc.client.ssl.*`. |
| `endpoint.username`, `endpoint.password` | string | unset | Basic auth metadata; on when `username` is non-blank. |
| `endpoint.http2_keep_alive_timeout` | seconds | `-1` (forever) | Idle HTTP/2 connection timeout. |
| `model.type` | enum | required | `remote_llm`, `remote_classifier`, `remote_embedding`, `remote_reranker`. |
| `model.server` | string | `default` | Endpoint id; startup fails if neither it nor a `default` endpoint exists. |
| `model.modalities` | list | probed | Override for proxies whose remote cannot be probed. |
| `grpc.client.ssl.truststore.*`, `.keystore.*` | `type`, `path`, `password` (`keyPath` for PEM) | JVM default / unset | Outbound trust and identity. |
| `grpc.client.ssl.trustAll`, `.verifyHostname` | bool | `false`, `true` | Development shortcuts. |

## Notes

- Endpoints are plaintext unless `ssl: true`; credentials on a plaintext endpoint are sent in
  the clear and logged as a warning.
- Startup is fail-fast: every remote model is checked with `GetModel`; a missing model or an
  unreachable server throws `IllegalStateException` before anything is published.
- Remote model entries carry no engine config. Sampling and templates are configured on the
  serving workspace and in pipeline steps.
- `remote_llm` renders prompts locally with the remote model's own chat template, fetched
  from `GetModel`, so a step's `prompt:` and `tags:` behave exactly as on the server.
- `regex` and `composite_classifier` need no server: they are registered in-process by
  `ClientLocalModelRegistrar` wherever the workspace runs.
- Build dev truststores with `keytool -importcert`, not `openssl pkcs12 -nokeys`; Java reads
  zero entries from openssl cert-only bundles. `scripts/gen-dev-certs.sh` does it right.
- The embedded executor emits no OpenTelemetry spans; run the client workspace as a server
  when tracing matters.

## See also

- [Sub-pipelines](../sub-pipelines/README.md): the step `server:` delegates.
- [gRPC API](../../api/grpc/README.md): the RPCs the proxies call, TLS and auth on the server side.
- [Java client](../../api/java-client/README.md): `SingulariteeClient`.
- [Deployment](../../operations/deployment/README.md): one backend per process.
- [Observability](../../operations/observability/README.md): server-side tracing.
