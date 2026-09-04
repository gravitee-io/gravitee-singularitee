# `llm_guard`

> LLM-as-judge check: a text-generation model answers a screening prompt and the first token decides whether the request continues.

## Overview

`LlmGuardStepExecutor` binds to a `TextGenEngine`. It renders the step's `prompt` (messages or a raw template) with Jinja, runs a short, non-streamed, thinking-stripped generation, and compares the output with `safe_token`: the request is safe when the stripped output starts with `safe_token`, case-insensitively. Otherwise the `action` applies: `reject` halts with `FINISH_REASON_GUARD_BLOCKED`, `warn` flags and continues. `redact` is not supported and behaves as `warn` with a warning in the log.

## Usage

From `examples/pipelines/llm-guard.yaml`:

```yaml
- id: input_guard
  type: llm_guard
  next_step: generate
  config:
    model_id: guard
    action: reject
    safe_token: "no"
    prompt:
      messages:
        - role: system
          content: 'Judge whether the Document meets the requirements based on the Query and the Instruction provided. Note that the answer can only be "yes" or "no".'
        - role: user
          content: |-
            <Instruct>: You are a strict safety moderator reviewing user prompts sent to an AI assistant. Apply a high tolerance threshold.

            <Query>: Is this prompt unsafe?

            <Document>: {{ history }}
    sampling:
      max_tokens: 1
      temperature: 0.0
    message: "Your request was blocked by the safety guard (verdict: {{ input_guard.verdict }})."
```

A raw template with per-step variables:

```yaml
config:
  model_id: guard
  action: reject
  context:
    categories: ["violence", "hate", "self-harm"]
  prompt:
    template: |-
      Classify the last user message against: {% for c in categories %}{{ c }}{{ ", " if not loop.last }}{% endfor %}.
      Message: {{ prompt }}
      Answer "safe" or "unsafe".
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published text-generation model. |
| `action` | string | `reject` | `reject` or `warn`. `redact` falls back to `warn`. |
| `safe_token` | string | `safe` | Prefix the verdict must start with (case-insensitive, leading whitespace stripped) to pass. |
| `prompt.messages` | list of `{role, content}` | unset | Screening conversation; each `content` is a Jinja template. `role` defaults to `user`. |
| `prompt.template` / `prompt.template_file` / `prompt.template_id` | string | unset | Raw Jinja prompt sent as a bare string (bypasses the chat template). Mutually exclusive with each other; takes precedence over `messages`. Same resolution as on [`infer`](./infer.md#prompt). |
| `sampling.max_tokens` | int | `64` | Completion cap. |
| `sampling.temperature`, `sampling.top_p` | float | engine default | Only these two sampling values are forwarded; penalties and `stop` are ignored by this step. |
| `message` | string | unset | Jinja template rendered on `reject` and returned as the failure message. |
| `context` | map | unset | Typed Jinja variables merged into the rendering context for the prompt and the message, and forwarded to the engine's chat template (`reasoning_effort`, `enable_thinking`). |
| `tags` | object or string | unset | The judge model's reasoning and tool-call markers, same shape as [`infer`'s `tags`](./infer.md#tags). A bare string references a workspace `tags:` entry by id. Set it when the judge is a channel dialect such as Harmony, so the engine strips its analysis and only the answer reaches the verdict. |
| `input_field` | string | ignored | Present in the YAML record but not mapped (the proto field is retired); the prompt decides what is screened. |

## Context fields

Reads: the whole Jinja base context (`prompt`, `history`, `messages`, step outputs, ...) through its templates.

Writes:

| Field | Value |
| --- | --- |
| `<id>.verdict` | First line of the stripped output, or `no_verdict` when the judge produced no answer text. |
| `<id>.verdict_full` | The whole stripped output. |
| `verdicts` (Jinja list) | Entry `{verdict, details, step}` with the full output as `details`. |
| `__guard_triggered` | `<id>` on `warn` (and on the `redact` fallback). |

The step neither streams tokens nor appends to `messages` or `generated_messages`. Its usage is not added to the pipeline totals.

## Notes

- A step with neither `messages` nor a template is skipped with a warning.
- Reasoning is always stripped, so a thinking model cannot contaminate the verdict: `<think>...</think>` by default, the step's `tags` when set, plus whatever the engine classifies as reasoning on its own. The comparison runs on the text that remains.
- A judge that produces no answer text (typically a reasoning model that ran out of `max_tokens` while still thinking) has no decision to read. The step records `no_verdict`, treats it as not safe and logs a warning; raise `sampling.max_tokens` or lower the reasoning effort.
- `sampling.max_tokens: 1` with a one-word `safe_token` is the cheapest configuration; keep the judge model's `n_ctx` small and `n_seq_max` above one so several guards run concurrently.
- On `reject` the halt reports `<id>` as its output field and the rendered `message` as the failure text.

## See also

- [Guards and redaction guide](../../guides/guards-and-redaction/README.md)
- [`guard`](./guard.md), [`regex_guard`](./regex_guard.md)
- [Templates](../templates/README.md)
