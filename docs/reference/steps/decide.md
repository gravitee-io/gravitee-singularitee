# `decide`

> Asks a decision model typed questions about a context field and writes the answers to the context.

## Overview

`DecideStepExecutor` binds to a `DecisionEngine` (`llama_cpp_decision`, `vllm_decision`, `gliner_decision`, `remote_decision`). It
asks its questions about the state read from `input_field`, in one engine call, and writes each
answer to the context. The step never branches: [`route`](./route.md) (`strategy: decision`),
[`break`](./break.md) and [`loop`](./loop.md) act on what it writes.

The questions come from exactly one source:

| Source | Config | Use |
| --- | --- | --- |
| The workspace | `questions:` as a map | A fixed classification inside a pipeline. |
| The caller | `questions: request` | A pipeline published as a decision endpoint: the caller's questions (or rendered prompts) from `/v1/decisions`, `/v1/systemone` or gRPC `Decide`. |
| Step-rendered prompts | `prompts:` | A model with `prompt.template: none`: the step renders each prompt with the pipeline's Jinja context. |

The typed answers are also kept on the pipeline context, so a decision call that targets the
pipeline returns them. The last `decide` step that ran wins.

## Usage

Fixed questions, routed on (`examples/decision/qwen3-0.6b-triage.yaml`, pipeline `support`):

```yaml
- id: classify
  type: decide
  next_step: route
  config:
    model_id: judge
    questions:
      team:
        instructions: Which team should handle this customer message?
        choice:
          billing: payments, invoices, refunds
          shipping: parcels, delivery, tracking

- id: route
  type: route
  config:
    strategy: decision
    input_field: classify.team
    min_confidence: 0.3
    default_step: generalist
    rules:
      - { label: billing, next_step: billing }
      - { label: shipping, next_step: shipping }
```

A decision endpoint guarded before the model sees the state (pipeline `triage`):

```yaml
- id: decide
  type: decide
  role: output
  config:
    model_id: judge
    questions: request
```

Every question type:

```yaml
questions:
  team:    { instructions: Which team?, choice: { billing: null, shipping: parcels } }
  teams:   { instructions: Which team?, choice: [billing, shipping] }     # bare keys
  angry:   { instructions: Is the customer angry?, noul: { true: shouting or insults } }
  urgency: { instructions: How urgent?, score: [can wait, this week, today, right now] }
```

Step-rendered prompts for a raw-only model:

```yaml
config:
  model_id: raw-scorer
  prompts:
    team:
      choice: [billing, shipping]       # option keys, in the order the prompt lists them
      template: judge-team              # a templates: id, or inline Jinja
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a decision model. Missing fails the load. |
| `input_field` | string | `prompt` | Context key holding the state. `messages` passes the chat messages as a list of `{role, content}`. An empty value skips the step with a warning; in a decision call, an empty `prompt` or `messages` fails it with `"state" must be provided`, as a model does. |
| `output_field` | string | the step id | Prefix of the context keys written. |
| `questions` | map, or `request` | unset | Id to question, in declaration order; or `request` to take the caller's. Exactly one of `questions` and `prompts`. |
| `questions.<id>.instructions` | any | required | The question; a string, map or list reaches the template as is. |
| `questions.<id>.choice` | map or list | | Option key to description (`null` allowed), or a list of bare keys. At least one option. |
| `questions.<id>.score` | list | | 2 to 10 level descriptions, lowest first. |
| `questions.<id>.noul` | map | | Yes or no; optional `true` and `false` descriptions. |
| `prompts` | map | unset | Id to a prompt the step renders. Each sets exactly one of `choice` (list of distinct option keys), `score` (number of levels, 2 to 10) or `noul` (`{}`), and `template`. |
| `prompts.<id>.template` | string | required | A `templates:` id, or inline Jinja rendered against the pipeline context (`{{ prompt }}`, `{{ messages }}`, step outputs). A bare name that is no `templates:` id fails the load as a misspelt id. |

Each question sets exactly one of `choice`, `score`, `noul`; anything else fails the load.

## Context fields

Reads: `input_field` (default `prompt`), or `messages`. With `questions: request`, the decision
request a caller sent.

Writes, for each answer `q` under the prefix `p` (`output_field`, or the step id):

| Field | Value |
| --- | --- |
| `p.q` | `choice`: the chosen key. `score`: the expected level (a float). `noul`: the probability of yes. |
| `p.q.confidence` | `choice`, `score`: 0 to 1. |
| `p.q.mode` | `score`: the most probable level. |
| `p.q.probabilities` | `choice`, `score`: a JSON object of key to probability, in option order. |

Templates see the nested view on the first dot only: `{{ classify.team }}` works, the details
read as `{{ classify['team.confidence'] }}`.

## Notes

- **Validation happens at load and at run time.** The codec checks the shape of each question
  and prompt, including the option keys and level count of `prompts:`; option limits and
  duplicate ids are checked by the model when the step runs. A failure
  ends the pipeline (logged), and a decision call that targets the pipeline returns the error.
- **Fixed questions refuse the caller's.** A step with `questions:` or `prompts:` fails a
  decision call that also sends questions (`this pipeline declares its own questions; send the
  state only`); a call with the state alone is answered with the step's questions.
- **`questions: request` outside a decision call** fails the step: there are no questions to
  answer. A call with an empty question list fails with `"questions" must not be empty`.
- **A decision pipeline takes a state, not rendered prompts.** Guards check the pipeline's
  prompt, which only a state becomes; a call with `prompts` (gRPC `RawInput`) is refused with
  `unsupported_input` before the pipeline runs.
- **The state is what the pipeline made of the input.** A guard that redacts `prompt` before the
  step changes what the model sees. A JSON state sent to a decision pipeline reaches the step as
  its JSON text.
- **A decision endpoint** is a pipeline whose `role: output` step is a `decide` step: it inherits
  task `decision` and accepts decision calls. A guard that halts it answers with `blocked`, not an
  error.
- A missing or wrongly typed model logs a warning and follows `next_step`, as for every
  model-bound step.

## See also

- [llama_cpp_decision](../models/llama_cpp_decision.md), [vllm_decision](../models/vllm_decision.md), [gliner_decision](../models/gliner_decision.md), [remote_decision](../models/remote_decision.md)
- [`route`](./route.md) (`strategy: decision`), [`classify`](./classify.md)
- [Context fields](../context-fields.md)
