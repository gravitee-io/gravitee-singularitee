# remote_embedding

> A proxy for an embedding model published by another Singularitee.

## Overview

`remote_embedding` registers an `EmbeddingEngine` (`feature-extraction`) that forwards
`Embed` / `EmbedBatch` to the server named by `server:`. The local `embed` step,
embedding-KNN routing and the vector RPCs use it exactly like a local embedder.
`RemoteEmbeddingEngine` implements it.

## Usage

```yaml
workspace:
  name: client-embedding
  remote:
    servers:
      - id: vectors
        host: 127.0.0.1
        port: 9093
  models:
    - id: text-embedding          # id published by the vector server
      type: remote_embedding
      server: vectors
  pipelines:
    - id: embed
      entry: embed_prompt
      steps:
        - id: embed_prompt
          type: embed
          role: output
          config:
            model_id: text-embedding
            input_field: prompt
            output_field: prompt.embedding
```

Serve `examples/embedding/bge-small-en.yaml` on port 9093 (`GRAVITEE_GRPC_PORT=9093`) for the
`text-embedding` id.

## The `remote:` block

Remote types carry no engine config; they need an endpoint declared once per workspace
under `workspace.remote` (`WorkspaceDefinition.RemoteConfig`) and bound with `server:`.

```yaml
workspace:
  remote:
    default:                      # used by models with no server:
      host: 127.0.0.1
      port: 9090
    servers:
      - id: safety
        host: safety.internal     # must match the certificate SAN when ssl: true
        port: 9092
        ssl: true
        username: client
        password: ${SAFETY_PASSWORD}
        http2_keep_alive_timeout: -1
```

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `default` | endpoint | unset | The endpoint a model without `server:` binds to (its id is `default`). |
| `servers[].id` | string | unset | Name referenced by `server:`. Entries without an id are ignored. |
| `servers[].host` / `.port` | string / int | unset | gRPC coordinates of the other Singularitee. |
| `servers[].ssl` | boolean | `false` | TLS with ALPN HTTP/2. Trust and client-certificate material come from `grpc.client.ssl.*` in `gravitee.yml`; the JVM default trust store applies when nothing is configured. |
| `servers[].username` / `.password` | string | unset | HTTP Basic credentials sent as gRPC metadata; on when `username` is non-blank. The server logs a warning when credentials travel over a plaintext endpoint. |
| `servers[].http2_keep_alive_timeout` | int | `-1` (always alive) | Seconds an idle HTTP/2 connection is held; positive values go to the Vert.x client. |

The same keys apply to `default:`. Credentials match the `grpc.auth.users.<name>` entries of
the serving process; `grpc.client.ssl.keystore` presents a client certificate for a server
running `clientAuth: REQUIRED`.

## Options

Model-level keys only; there is no `remote_embedding:` block.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | unset | Id of the embedding model on the remote server. |
| `server` | string | `default` | Endpoint id from `remote:`. |
| `name` | string | `id` | Display name. |
| `task` | string | `feature-extraction` | Override; rarely needed. |
| `visible` | boolean | `true` | Hide the proxy from the local catalogue. |
| `modalities` | list | `text` | Declared only. |

## Notes

- **The id is the remote id.** The proxy calls the remote model under this entry's `id`; there
  is no separate remote name. `name` is only a display name and falls back to `id`.
- **Lazy on the server, fail-fast on the client.** A standalone server registers the proxy
  without probing the remote, so the local process starts while the remote is still booting
  and transient failures surface per request. A client-side workspace (`ClientPipelineExecutor`)
  calls `GetModel` for every remote model at startup and throws `IllegalStateException` when
  one is missing or the endpoint is unreachable.
- **`server:` must resolve.** A `server:` naming an endpoint absent from `remote:` (or no
  `server:` with no `default:`) fails the registration: WARN and skip on the server,
  exception on the client.
- **Publication is local.** `task:`, `visible:` and `modalities:` apply to the proxy as to
  any entry; the remote server's own visibility setting does not propagate.
- **Run the serving workspace first.** `examples/modular/server-*.yaml` on the default ports,
  then the client on others (`GRAVITEE_GRPC_PORT=9190 ./run-server.sh --port 8180 --workspace <client>`).
- **Splitting happens on the remote.** Long inputs are chunked by the serving engine; the
  proxy forwards the text as-is and returns the remote's vector and token count.

## See also

- [Remote and multi-server](../../guides/remote-and-multi-server/README.md).
- [Embeddings and reranking](../../guides/embeddings-and-reranking/README.md).
- [Routing](../../guides/routing/README.md), embedding-KNN routing.
- [onnx_embedding](./onnx_embedding.md), [remote_reranker](./remote_reranker.md).
