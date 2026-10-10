# remote_decision

> A proxy for a decision model published by another Singularitee.

## Overview

`remote_decision` registers a `DecisionEngine` (`decision`) that forwards every call to
`GraviteeDecisionService.Decide` on the server named by `server:`. A local
[`decide`](../steps/decide.md) step, `/v1/decisions`, `/v1/systemone` and gRPC `Decide` against
this id get the remote model's answers, typed. `RemoteDecisionEngine` implements it.

## Usage

```yaml
workspace:
  name: client-decision
  remote:
    default:
      host: 127.0.0.1
      port: 9090
  models:
    - id: judge                   # id published by the decision server
      type: remote_decision
  pipelines:
    - id: triage
      entry: decide
      steps:
        - id: decide
          type: decide
          role: output
          config:
            model_id: judge
            questions: request
```

Serve `examples/decision/qwen3-0.6b-judge.yaml` on port 9090 for the `judge` id.

The `remote:` block is shared by every remote type; see
[remote_reranker](./remote_reranker.md#the-remote-block).

## Options

Model-level keys only; there is no `remote_decision:` block.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | unset | Id of the decision model on the remote server. |
| `server` | string | `default` | Endpoint id from `remote:`. |
| `name` | string | `id` | Display name. |
| `task` | string | `decision` | Override; rarely needed. |
| `visible` | boolean | `true` | Hide the proxy from the local catalogue. |

## Notes

- **The remote validates.** Questions, option limits and input modes are checked by the
  serving model; its errors come back as gRPC statuses.
- **Capabilities are probed lazily.** On a standalone server the proxy calls `GetModel` the first
  time its capabilities are read; until the probe answers it reports a placeholder (`custom`,
  255 options, templated and raw). A client-side workspace (`ClientPipelineExecutor`) reads them
  from its startup `GetModel`.
- **Hidden remote models are reachable.** The proxy calls over gRPC, where `visible: false` on
  the serving side does not apply, so a server can keep its decision model out of its own HTTP
  catalogue and still serve it to clients.
- **Lazy on the server, fail-fast on the client**, as for every remote type.

## See also

- [llama_cpp_decision](./llama_cpp_decision.md), [vllm_decision](./vllm_decision.md), [gliner_decision](./gliner_decision.md).
- [Remote and multi-server](../../guides/remote-and-multi-server/README.md).
- [remote_classifier](./remote_classifier.md), [remote_reranker](./remote_reranker.md).
