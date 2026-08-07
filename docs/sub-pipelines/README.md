# Sub-pipelines

> Invoke another published pipeline as a nested step — locally or on a remote server — and capture its output in the parent context.

## Overview
A `sub_pipeline` step delegates to another published pipeline: the parent's context is
snapshotted into the child request, the child runs to completion, and its final output is
written back into a parent context field. The child's streamed events are forwarded to
the caller as they arrive, its token usage is accumulated into the parent's totals, and a
non-STOP finish reason (a guard block, a break condition) halts the parent too. Input can
be a single flat prompt field or — with `forward_messages: true` — the parent's full chat
history, optionally re-anchored with a new `system_prompt`. When the step names a
`server`, the child executes on a remote Singularitee over gRPC instead of locally, which is
how multi-server clients stitch one DAG across an LLM server and a safety server.

## Key types
- `SubPipelineStepExecutor` — executes `type: sub_pipeline`; builds the child `InferPipelineRequest` (context snapshot, tools, prompt or messages), runs it through a callback, then writes the accumulated output to `output_field` and propagates halts.
- `SubPipelineStepExecutor.PipelineExecutorCallback` — `executePipeline(request, responseStream, callerContext)`, returning a `Completable`; one local callback plus one per configured remote.
- `RemotePipelineCallback` (module `gravitee-singularitee-engine-remote`) — the remote implementation: streams `SingulariteeClient.inferPipeline(request)` events into the parent's response stream, ending on the COMPLETED/FAILED event; fully non-blocking.
- `SubPipelineStepConfig` — proto config in `pipeline.proto` (`pipeline_id`, `input_field`, `output_field`, `remote_id`, `system_prompt`, `forward_messages`); the YAML key for `remote_id` is `server`.
- `TokenCaptureStream.forwardAll(...)` — accumulates the child's tokens for `output_field` while forwarding every event to the caller, and retains the last response so the parent can inspect the child's finish reason.
- `FinishReason` — a child finish reason other than `FINISH_REASON_STOP`/`UNSPECIFIED` is re-signalled on the parent via `PipelineContext.signalHalt`.

## Usage
Delegate a field to another pipeline in the same workspace:

```yaml
steps:
  - id: moderate
    type: sub_pipeline
    next_step: generate
    config:
      pipeline_id: toxicity-guard-pipeline   # a published pipeline id
      input_field: prompt                    # context field sent as the child's prompt
      output_field: moderate.output          # child's final output lands here

  - id: generate
    type: infer
    role: output
    config:
      model_id: llm
      output_field: generate.output
      prompt:
        messages:
          - role: user
            content: "{{moderate.output}}"
```

Forward the whole conversation to the child, with a child-specific system prompt, and run
it on a remote server declared in the workspace's `remote:` section (the pattern used by
the multi-server clients in `examples/modular/client-safety-llamacpp.yaml`, which pair a safety
server on `:9092` with an LLM server):

```yaml
steps:
  - id: summarize
    type: sub_pipeline
    next_step: answer
    config:
      pipeline_id: summary-pipeline
      server: safety                # remote id from the workspace remote: config; omit to run locally
      forward_messages: true        # send the parent's chat history, not a flat prompt
      system_prompt: "You are a summarization assistant. Condense the conversation."
      output_field: summarize.output
```

A whole pipeline can also be declared as a remote reference (`server:` at the pipeline
level with no local steps), proxying every request to the named remote — see the
`PipelineDefinition.isRemote()` path and the client workspaces under `examples/`.

## Options

### `sub_pipeline` (`SubPipelineStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `pipeline_id` | — (required) | Id of the published pipeline to invoke (looked up locally unless `server` is set). |
| `input_field` | `prompt` | Parent context field passed as the child's entry prompt (ignored for content when `forward_messages` is true). |
| `output_field` | `<step_id>.output` | Parent context field the child's accumulated final output is written to. |
| `server` | — (local) | Remote id from the workspace's remote config (proto: `remote_id`); when set, the child runs on that server via `RemotePipelineCallback`. |
| `system_prompt` | — | Prepended as a system message to the child's message list, replacing any existing system message; without `forward_messages` it wraps `input_field` into a `[system, user]` pair. |
| `forward_messages` | `false` | Forward the parent's full chat messages (system + history) instead of the flat prompt string. |

## Notes
- **Context and tools are inherited**: the child request carries a snapshot of the parent's context map (`putAllContext(pctx.snapshot())`) and the parent's tools, so child templates can reference parent step outputs and the child can tool-call.
- **Halt propagation**: if the child completes with a finish reason other than `STOP`/`UNSPECIFIED` (e.g. `FINISH_REASON_GUARD_BLOCKED`, `FINISH_REASON_BREAK_CONDITION`), the parent signals halt with the same reason; a FAILED child event halts the parent with `FINISH_REASON_GUARD_BLOCKED` and the child's error message as the halt message. A guard buried two pipelines deep still blocks the top-level response.
- **Usage accounting**: the child's COMPLETED event carries usage and performance stats, which the parent accumulates via `PipelineContext.accumulateUsage` — the caller sees one combined total.
- **Streaming is pass-through**: the child's events are forwarded to the caller as they arrive (`TokenCaptureStream.forwardAll`), so a child `role: output` infer step streams tokens to the end client while the parent is still mid-DAG.
- **Missing targets skip, not fail**: an unknown `server` id, or a `pipeline_id` that is neither local nor remote, logs a warning and proceeds to the next step — `output_field` is then never written, so downstream `{{...output}}` references render empty. Watch the logs when wiring composition.
- **Message forwarding precedence**: with `forward_messages: true`, `system_prompt` replaces any existing system message (via `prependOrReplaceSystem`); with it false but `system_prompt` set, the child gets exactly `[system_prompt, input_field value]`; with neither, the child gets the flat prompt string.
- **Remote execution is fully reactive**: `RemotePipelineCallback` completes when the remote stream ends and synthesizes a FAILED terminal event if the connection drops before one arrives, so the parent never hangs on a dead remote.

## See also
- [Pipelines](../pipelines/README.md) — publishing pipelines and the DAG model that sub-pipelines nest into.
- [Remote & Multi-Server](../remote-and-multi-server/README.md) — declaring `remote:` servers and stitching one DAG across machines.
- [Guards & Redaction](../guards-and-redaction/README.md) — guard halts and how `GUARD_BLOCKED` propagates through sub-pipelines.
- [Loops & Chain-of-Thought](../loops-and-cot/README.md) — the step roles and message accumulation a forwarded conversation carries.
- [gRPC API & Client](../grpc-api-and-client/README.md) — `SingulariteeClient.inferPipeline`, the surface remote delegation rides on.
