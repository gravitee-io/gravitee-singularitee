# remote_reranker

> A proxy for a cross-encoder reranker published by another Singularitee.

## Overview

`remote_reranker` registers a `RerankerEngine` (`reranking`) that forwards `TextRerank` to
the server named by `server:`. Local `/v1/rerank` and `TextRerank` calls against this id
get true cross-encoder scores from the remote. `RemoteRerankerEngine` implements it.

## Usage

```yaml
workspace:
  name: client-rerank
  remote:
    default:
      host: 127.0.0.1
      port: 9094
  models:
    - id: reranker                # id published by the reranker server
      type: remote_reranker
```

Serve `examples/reranker/bge-reranker-base.yaml` on port 9094 for the `reranker` id.

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

Model-level keys only; there is no `remote_reranker:` block.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | unset | Id of the reranker on the remote server. |
| `server` | string | `default` | Endpoint id from `remote:`. |
| `name` | string | `id` | Display name. |
| `task` | string | `reranking` | Override; rarely needed. |
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
- **Results keep the original indices** and `top_k: 0` returns every document, as on the
  serving side.

## See also

- [Remote and multi-server](../../guides/remote-and-multi-server/README.md).
- [Embeddings and reranking](../../guides/embeddings-and-reranking/README.md).
- [onnx_reranker](./onnx_reranker.md), [llama_cpp_reranker](./llama_cpp_reranker.md), [remote_embedding](./remote_embedding.md).
