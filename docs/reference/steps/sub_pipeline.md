# `sub_pipeline`

> Runs another published pipeline as a nested step, locally or on a named remote server, and captures its output in the parent context.

## Overview

`SubPipelineStepExecutor` builds an `InferPipelineRequest` for `pipeline_id`: the parent's context snapshot becomes the request `context`, the parent's tools are forwarded, and the input is either the flat `input_field` text or, with `forward_messages: true`, the whole conversation. Tokens streamed by the sub-pipeline are forwarded to the client as they arrive and accumulated into `output_field`. Usage is added to the parent's totals. A sub-pipeline that ends with a finish reason other than `STOP` halts the parent with that reason; a `FAILED` event (a guard rejection) halts the parent with `GUARD_BLOCKED` and the sub-pipeline's error message. With `server` set, the whole nested pipeline runs on that remote endpoint through `SingulariteeClient.inferPipeline`.

## Usage

```yaml
workspace:
  remote:
    default:
      host: 127.0.0.1
      port: 9090
    servers:
      - id: safety
        host: 127.0.0.1
        port: 9191
  pipelines:
    - id: agent
      entry: screen
      steps:
        - id: screen
          type: sub_pipeline
          next_step: generate
          config:
            pipeline_id: guard          # published on the `safety` server
            server: safety
            input_field: prompt
            output_field: screen.output
            forward_messages: true
            system_prompt: "Screen the last user message only."

        - id: generate
          type: infer
          role: output
          config:
            model_id: llm
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `pipeline_id` | string | required | Id of the pipeline to run. Must exist in the local `PipelineRegistry`, or on `server`. |
| `input_field` | string | `prompt` | Context key sent as the sub-pipeline's prompt when messages are not forwarded. |
| `output_field` | string | `<id>.output` | Context key receiving the sub-pipeline's streamed text. |
| `server` | string | unset | Id of a `workspace.remote.servers[]` endpoint; runs the pipeline there. Proto `remote_id`. An unknown id skips the step with a warning. |
| `system_prompt` | string | unset | System message for the sub-pipeline. With `forward_messages` it replaces any existing system turn; without, it is sent ahead of the `input_field` text as a two-message conversation. Plain text, not a Jinja template. |
| `forward_messages` | bool | `false` | Send the parent's full conversation (`messages`) instead of the flat prompt. |

## Context fields

Reads: `input_field` (default `prompt`), `messages` (with `forward_messages`), the request tools, and the full context snapshot (forwarded as the sub-request's `context`).

Writes:

| Field | Value |
| --- | --- |
| `<output_field>` | Everything the sub-pipeline streamed (set even when it halted). |

Also accumulates the sub-pipeline's usage and performance, and on a non-`STOP` result sets the parent's halt state with `<output_field>` as the reported output field.

## Notes

- The sub-pipeline's tokens reach the client with the roles the sub-pipeline's own steps assign; an `internal` step inside it stays silent.
- The forwarded context snapshot includes every `<step>.*` field of the parent, so a sub-pipeline template can read them through `context`-seeded variables, and the sub-pipeline's own fields do not flow back: only `output_field` does.
- A remote pipeline declared with `server:` directly on a `pipelines:` entry compiles to a single hidden `_remote` step of this type; `remote.system_prompt` and `remote.forward_messages` map to the keys above.
- Hidden pipelines (`visible: false`) remain callable as sub-pipelines.

## See also

- [Sub-pipelines guide](../../guides/sub-pipelines/README.md)
- [Remote and multi-server guide](../../guides/remote-and-multi-server/README.md)
- [Context fields](../context-fields.md)
