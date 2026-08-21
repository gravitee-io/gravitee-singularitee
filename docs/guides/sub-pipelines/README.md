# Sub-pipelines

> Invoke another published pipeline as a step, locally or on a remote server, and capture its output in the parent context.

## Overview

A `sub_pipeline` step builds an `InferPipelineRequest` for the child from the parent's state,
runs it through a callback (the local `PipelineExecutor`, or a `RemotePipelineCallback` for a
named `server`), and writes the child's accumulated text to `output_field`. While the child
runs, its events are forwarded to the caller unchanged; when it completes, its usage is added
to the parent totals and a non-`STOP` finish reason is re-signalled on the parent.

The child receives a snapshot of the parent's context map and the parent's tools. Its input
is either a single field (`input_field`, default `prompt`) or, with
`forward_messages: true`, the parent's whole conversation, optionally re-anchored by
`system_prompt`.

## Key types

| Type | Module | Purpose |
| --- | --- | --- |
| `SubPipelineStepExecutor` | `engine` | Builds the child request, runs the callback, writes `output_field`, propagates halts. |
| `SubPipelineStepExecutor.PipelineExecutorCallback` | `engine` | `executePipeline(request, responseStream, callerContext)`; one local, one per remote endpoint. |
| `RemotePipelineCallback` | `engine-remote` | Streams `SingulariteeClient.inferPipeline` into the parent's response stream. |
| `SubPipelineStepConfig` | `protocol` | `pipeline_id`, `input_field`, `output_field`, `remote_id` (YAML `server`), `system_prompt`, `forward_messages`. |
| `TokenCaptureStream.forwardAll` | `engine` | Accumulates the child's tokens and keeps its last event for the finish-reason check. |

## Usage

Moderate with a published guard pipeline, then generate. Both pipelines live in the same
workspace; the child ids come from `examples/modular/pipelines/toxicity-guard.yaml`:

```yaml
workspace:
  includes:
    pipelines: [toxicity-guard.yaml]
  pipelines:
    - id: moderated-chat
      entry: moderate
      steps:
        - id: moderate
          type: sub_pipeline
          next_step: polish
          config:
            pipeline_id: toxicity-guard-pipeline
            input_field: prompt               # sent as the child's prompt
            output_field: moderate.output     # the child's final text

        - id: polish
          type: infer
          role: output
          config:
            model_id: llm
            output_field: polish.output
            prompt:
              messages:
                - role: user
                  content: "Rewrite concisely: {{moderate.output}}"
```

A guard reject inside `toxicity-guard-pipeline` ends `moderated-chat` with
`FINISH_REASON_GUARD_BLOCKED` and the guard's message; `polish` never runs.

Forward the whole conversation to a child running on another server (the `remote:` block is
described in [Remote and multi-server](../remote-and-multi-server/README.md)):

```yaml
- id: summarize
  type: sub_pipeline
  next_step: answer
  config:
    pipeline_id: summary-pipeline
    server: safety                   # id from the workspace remote: block; omit for local
    forward_messages: true           # send messages, not a flat prompt
    system_prompt: "You are a summarization assistant. Condense the conversation."
    output_field: summarize.output
```

Call the parent like any pipeline:

```bash
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"moderated-chat","messages":[{"role":"user","content":"Explain DNS in two lines."}]}'
```

A whole pipeline can also be a remote reference: `server:` at the pipeline level with no
local steps proxies every request to the pipeline of the same id on that endpoint.

## Options

Full table: [`sub_pipeline`](../../reference/steps/sub_pipeline.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `pipeline_id` | string | required | Published pipeline to invoke, looked up locally unless `server` is set. |
| `input_field` | string | `prompt` | Parent field sent as the child's prompt. |
| `output_field` | string | `<step_id>.output` | Where the child's final text lands. |
| `server` | string | unset | Remote endpoint id; unset runs locally. |
| `system_prompt` | string | unset | Child system message. With `forward_messages` it replaces the parent's system message; without, the child gets `[system, input_field value]`. |
| `forward_messages` | bool | `false` | Forward the parent's message list instead of the flat prompt. |

## Notes

- The child's context snapshot is a copy: fields the child writes do not come back except
  through `output_field`.
- Halt propagation: a child `COMPLETED` with any reason other than `STOP` or `UNSPECIFIED`
  halts the parent with that reason; a child `FAILED` event halts the parent with
  `GUARD_BLOCKED` and the child's error message.
- Missing targets skip rather than fail: an unknown `server` id, or a `pipeline_id` that is
  neither local nor remote, logs a warning and the walk continues along `next_step` with
  `output_field` unset.
- Streaming passes through, so a child `role: output` step streams to the end client while
  the parent is mid-graph. Put `role: internal` on child steps whose text must stay hidden.
- Remote execution is non-blocking; if the connection drops before a terminal event the
  callback synthesizes a `FAILED` event so the parent never hangs.

## See also

- [Pipelines](../../concepts/pipelines/README.md): how halts and finish reasons travel.
- [Remote and multi-server](../remote-and-multi-server/README.md): declaring `remote:` endpoints.
- [Guards and redaction](../guards-and-redaction/README.md): the guard halts a child can raise.
- [gRPC API](../../api/grpc/README.md): `InferPipeline`, the RPC remote delegation uses.
