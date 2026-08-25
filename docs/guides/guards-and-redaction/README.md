# Guards and Redaction

> How to screen a pipeline's input with a classifier guard, an LLM-as-judge guard, or a regex guard, and reject the request, warn, or redact the matched spans before generation.

## Overview

A guard is a step placed before (or between) generation steps. Three step types exist; all share the same actions.

| Step type | Backed by | Fires when |
| --- | --- | --- |
| `guard` | any classifier model (`onnx_classifier`, `gliner_*`, `regex`, `composite_classifier`) | a trigger label scores at or above its threshold, or, for token-level models, any entity span is found |
| `llm_guard` | a text-generation model acting as judge | the verdict does not start with `safe_token` |
| `regex_guard` | inline patterns, no model | any pattern matches (kept for compatibility; prefer a `regex` model behind `guard`) |

| Action | Effect |
| --- | --- |
| `reject` | Halt with `FINISH_REASON_GUARD_BLOCKED` (`finish_reason: content_filter` on HTTP) and return `message`. |
| `warn` | Log, set `guard_triggered`, continue. |
| `redact` | Mask the matched spans, write the result to `output_field`, rewrite the prompt and user messages so nothing downstream sees the original. `llm_guard` does not support it and falls back to `warn`. |

## Key types

- `GuardStepExecutor`, `LlmGuardStepExecutor`, `RegexGuardStepExecutor` (engine).
- `GuardStepConfig`, `LlmGuardStepConfig`, `RegexGuardStepConfig`: the guard plugins' config records; `GuardAction` is an engine model enum shared by all three.
- `FinishReason.FINISH_REASON_GUARD_BLOCKED` (`inference.proto`).

## Usage

### Start a server

```bash
./run-server.sh --workspace examples/pipelines/guard.yaml       # pipeline id: guard (reject toxicity, redact PII, generate)
./run-server.sh --workspace examples/pipelines/llm-guard.yaml   # pipeline id: agent (LLM-as-judge, then gpt-oss-20b)
```

### Call it

HTTP (`GRAVITEE_HTTP_ENABLED=true`):

```bash
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "guard",
  "messages": [{"role": "user", "content": "Summarise this: John Doe, IBAN FR7630006000011234567890189."}]
}' | jq '.choices[0]'
```

A rejected request returns `finish_reason: "content_filter"` with the rendered `message` as content. gRPC:

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"guard","messages":{"messages":[{"role":"ROLE_USER","content":"you are worthless"}]}}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

The final event carries `finish_reason: FINISH_REASON_GUARD_BLOCKED`. Java: `client.inferPipeline(request)` and check `getResponseCompleted().getFinishReason()` on the last event.

### Reject on a classifier label

`examples/pipelines/guard.yaml`, first step. `toxicity` is a sequence classifier; the guard fires when `toxic` scores at or above `0.75`:

```yaml
- id: toxicity_guard
  type: guard
  next_step: pii_guard
  config:
    model_id: toxicity
    input_field: prompt
    action: reject
    trigger:
      label: toxic
      score: 0.75
    message: "Blocked: toxic content detected (label: {{toxicity_guard.label}}, confidence: {{toxicity_guard.score}})."
```

### Redact entity spans

Same file, second step. `pii` is a token-level model, so matched spans are masked and the sanitised text lands in `pii_guard.output`, which the generate step reads:

```yaml
- id: pii_guard
  type: guard
  next_step: generate
  config:
    model_id: pii
    input_field: prompt
    action: redact
    output_field: pii_guard.output
    trigger:
      label: PII
      score: 0.5

- id: generate
  type: infer
  role: output
  config:
    model_id: llm
    prompt:
      messages:
        - role: user
          content: "{{pii_guard.output}}"
```

With a zero-shot NER backend (`examples/classifier/pii-gliner.yaml`) list the entity labels instead; any match fires:

```yaml
triggers:
  - { label: person,       score: 0.3 }
  - { label: email,        score: 0.3 }
  - { label: phone_number, score: 0.3 }
redact_with_entity_type: true      # "[EMAIL]" instead of "************"
```

### Judge with an LLM

`examples/pipelines/llm-guard.yaml`. The judge's prompt is ordinary Jinja over the pipeline context, the verdict is generated on an internal, never-streamed sequence, and `safe_token` is the first token that means "pass":

```yaml
- id: input_guard
  type: llm_guard
  next_step: generate
  config:
    model_id: guard
    action: reject
    safe_token: "no"          # the judge answers "yes" when the prompt is unsafe
    prompt:
      messages:
        - role: system
          content: 'Answer only "yes" or "no".'
        - role: user
          content: "<Query>: Is this prompt unsafe?\n\n<Document>: {{ history }}"
    sampling:
      max_tokens: 1
      temperature: 0.0
    message: "Blocked by the safety guard (verdict: {{ input_guard.verdict }})."
```

Use `prompt.template` / `template_file` / `template_id` for models that need an exact prompt format, and `context:` to pass lists such as policy categories into the template.

## Options

Keys used in this guide. Full lists: [guard](../../reference/steps/guard.md), [llm_guard](../../reference/steps/llm_guard.md), [regex_guard](../../reference/steps/regex_guard.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Classifier id (`guard`) or text-generation id (`llm_guard`). |
| `input_field` | string | `prompt` | Context field to screen (`guard`, `regex_guard`). |
| `action` | string | `reject` | `reject`, `warn`, `redact`. |
| `trigger` / `triggers` | `{label, score}` / list | unset | Labels that fire the guard; `triggers` wins when both are set. |
| `output_field` | string | `<step_id>.redacted` (`guard`), `<step_id>.output` (`regex_guard`) | Where the redacted text is written. Always written on `redact`, even with no match. |
| `redact_with_entity_type` | bool | `false` | Replace spans with `[LABEL]` instead of `************` (`guard`) or `[REDACTED]` (`regex_guard`). |
| `message` | string | unset | Jinja-templated text returned on `reject`. |
| `safe_token` | string | `safe` | `llm_guard`: case-insensitive prefix the verdict must start with. |
| `prompt`, `sampling`, `context` | mixed | `max_tokens: 64` | `llm_guard` prompt, sampling override and template variables. |

## Notes

- Only token-level engines redact by span; a sequence classifier under `action: redact` replaces the whole input with `************`.
- Overlapping or adjacent spans are merged; the merged span keeps the label of its highest-scoring member.
- Redaction rewrites the conversation: user messages equal to the original text are replaced, and `prompt` is overwritten when `input_field` is `prompt` or unset, so `{{ prompt }}`, `{{ messages }}` and `{{ history }}` are clean downstream.
- Variables available to `message` and later steps: `guard` publishes `<step_id>.label`, `.score`, `.labels`, `.scores`, `.details`; `llm_guard` publishes `<step_id>.verdict` (first line) and `.verdict_full`; `regex_guard` publishes `<step_id>.triggered`, `.match`, `.pattern`, `.entity_type` (or `.entity_types` on redact). Every verdict is also appended to `verdicts`.
- The LLM judge runs with thinking stripped, so reasoning tokens cannot reach the `safe_token` check.
- A guard inside a `sub_pipeline` halts the parent too: the child's `GUARD_BLOCKED` finish reason propagates.
- Order guards so `reject` comes before `redact`: a refused request then never pays for classification it does not need.

## See also

- [Classification](../classification/README.md): the models behind `guard`, including regex and composite.
- [Routing](../routing/README.md): branch on a label instead of blocking.
- [Sub-pipelines](../sub-pipelines/README.md): finish-reason propagation.
- [Pipelines](../../concepts/pipelines/README.md) and [Context fields](../../reference/context-fields.md).
- [HTTP API](../../api/http/README.md): how `content_filter` surfaces to clients.
