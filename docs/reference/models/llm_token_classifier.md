# llm_token_classifier

> A token classifier over a text-generation model of the workspace: the model lists the entities it finds, the engine places them back into the input as character spans.

## Overview

Some entity detectors are generative: a fine-tuned LLM reads the text and answers with the
entities it holds, copying each one from the input, without offsets.
`llm_token_classifier` wraps such a model so it behaves like any span classifier. It reports
`token-classification`, so `POST /v1/classify`, the `Classify` RPC, the `guard` step
(`reject`, `warn`, `redact`) and `composite_classifier` use it unchanged.

For every input the engine sends `system_prompt` as the system turn and the text as the user
turn, decodes greedily, and reads the answer: a list of items, each a label and a value copied
from the input. By default:

```json
{"spans":[{"label":"PERSON","text":"Kari Nordmann","ref":"p1"},{"label":"PHONE","text":"912 34 567","ref":"p1"}]}
```

The `answer:` block names where a model keeps its items, so any span model fits, whatever field
names it was trained with: `{"entities":[{"type":"PERSON","value":"Jean Dupont"}]}` is
`answer: {spans: entities, label: type, text: value}`, and a model that answers with the bare
array sets `spans: ""`. With `structured_output` (the default) the
engine turns that block into a JSON schema and the backend decodes against it, so the answer
can only hold items whose label and value sit where the engine reads them: no prose, no
reasoning, no unknown field, no cut-off object other than at `max_tokens`.

Each item is then located in the input: from the end of the previous match first (items come
in order of appearance), then from the start; case-sensitively, then ignoring case; preferring
a whole-word occurrence; never overlapping a span already placed. A value at least
`repeat_min_length` characters long is also marked at its other whole-word occurrences, so a
name listed once is redacted everywhere.

Each span is also graded. The engine asks the model for its token
log-probabilities, finds the tokens that wrote each item (from its opening brace, which is
where the model chose to list another entity, through its label to the end of its text) and
scores the span with one of the confidence signals the `infer` step publishes, read as a
confidence in `[0, 1]`. A guard threshold then separates a clear name from a doubtful health
mention.

## Key types

- `LlmTokenClassifierEngine` (`engine`): the `ClassifierEngine`, built by
  `ClientLocalModelRegistrar` over the `TextGenEngine` registered under `model_id`.
- `LlmSpans` (`engine`): answer parsing, span location, repeated occurrences.
- `LlmSpans.Format` (`engine`): where an answer keeps its items, labels and values, and the
  JSON schema of such an answer.
- `WorkspaceDefinition.LlmTokenClassifierDef` and `LlmAnswerDef` (`workspace`): the YAML block.

## Usage

[`examples/classifier/pii-nordic-llm.yaml`](../../../examples/classifier/pii-nordic-llm.yaml)
publishes `unified-analytics/nordic-pii-spans-qwen3.5-4b`, a Norwegian and Swedish PII span
model, as the classifier `pii`
([`examples/vllm/pii-nordic-llm.yaml`](../../../examples/vllm/pii-nordic-llm.yaml) is the same
classifier over the model's BF16 safetensors build on `vllm`):

```yaml
workspace:
  name: pii-nordic-llm
  models:
    - id: nordic-pii-spans
      name: unified-analytics/nordic-pii-spans-qwen3.5-4b
      type: llama_cpp
      visible: false              # callers use the classifier, not the raw model
      llama_cpp:
        path: nordic-pii-spans-qwen3.5-4b.Q8_0.gguf
        n_ctx: 4096
        n_seq_max: 2
    - id: pii
      type: llm_token_classifier
      llm_token_classifier:
        model_id: nordic-pii-spans
        system_prompt: >-
          Find all personal data in the user's text. Answer with JSON only: ...
        labels: [PERSON, ADDRESS, PHONE, EMAIL, NATIONAL_ID, ...]
        answer:                   # the defaults, plus the ref field the model writes
          spans: spans
          label: label
          text: text
          metadata: [ref]
        structured_output: true   # the default: decode against the answer's JSON schema
        max_tokens: 1500
        context:
          enable_thinking: false
        score: max_entropy        # the default; none scores every span 1.0
```

The same classifier over a model trained on other field names, answering
`{"entities":[{"type":"PERSON","value":"Jean Dupont"}]}`:

```yaml
    - id: ner
      type: llm_token_classifier
      llm_token_classifier:
        model_id: my-ner-llm
        system_prompt: >-
          List the named entities of the text as JSON ...
        answer:
          spans: entities
          label: type
          text: value
```

```bash
curl localhost:8080/v1/classify -H 'content-type: application/json' \
  -d '{"model":"pii","input":"Ring Kari Nordmann på 912 34 567"}'
```

```json
{
  "results": [{
    "top_label": "PERSON", "top_score": 0.9994,
    "scores": {"PERSON": 0.9994, "PHONE": 0.9993},
    "spans": [
      {"label": "PERSON", "score": 0.9994, "token": "Kari Nordmann", "start": 5, "end": 18,
       "metadata": {"ref": "p1"}},
      {"label": "PHONE", "score": 0.9993, "token": "912 34 567", "start": 22, "end": 32,
       "metadata": {"ref": "p1"}}
    ]
  }],
  "usage": {"prompt_tokens": 184, "completion_tokens": 43, "total_tokens": 227}
}
```

Behind a `guard` step it redacts like any NER model:

```yaml
- id: pii_guard
  type: guard
  config:
    model_id: pii
    action: redact
    redact_with_entity_type: true
```

## Options

`llm_token_classifier:` block (`WorkspaceDefinition.LlmTokenClassifierDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Id of a text-generation model in the same workspace: `llama_cpp`, `vllm` or `remote_llm`. Missing, unregistered or not a generator skips the classifier with a WARN. |
| `system_prompt` | string | unset | System turn sent before every input. Use the prompt the model was trained with, verbatim. Unset sends the input alone. |
| `labels` | list of string | unset (every label) | Labels kept from the answer; anything else the model emits is dropped. |
| `max_tokens` | int | `1024` | Answer budget per request. An answer cut off before its JSON closes fails the call. |
| `context` | map | unset | Chat-template variables, such as `enable_thinking: false` for a model that must answer without reasoning. |
| `chunk_tokens` | int | `1024` | Estimated tokens per request; a longer input is split on paragraph, line and sentence boundaries and each piece asked separately, at most 4 at a time. `0` never splits. |
| `repeat_min_length` | int | `4` | A found value at least this long is also marked at its other whole-word occurrences. `0` disables. |
| `score` | string | `max_entropy` | Confidence signal scoring each span from its own tokens (see Notes). `none` scores every span `1.0` and requests no log-probabilities. An unknown name, or a signal with no confidence reading, skips the classifier with a WARN. |
| `answer` | map | see below | Where the answer keeps its items, labels and values. |
| `structured_output` | boolean | `true` | Decode against the JSON schema of `answer` (see Notes). `false` decodes freely and parses what comes back. |

`answer:` block (`WorkspaceDefinition.LlmAnswerDef`). Each unset key keeps its default.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `spans` | string | `spans` | Key of the items array in the answer object. `""` when the answer is the array itself. |
| `label` | string | `label` | Field of an item holding its label. |
| `text` | string | `text` | Field of an item holding the value copied from the input. |
| `metadata` | list of string | unset | Other string fields an item may carry, such as `ref`. The schema declares them, optional, after the label and the value; a constrained answer cannot write a field it does not declare. |

A blank `label` or `text`, the same field for both, or a `metadata` entry repeating one of them
skips the classifier with a WARN.

`name` is the display name (falls back to `id`); `memory_check` and `download` are ignored;
`task`, `visible` and `modalities` apply as on any entry.

## Notes

- **The answer contract.** The model must answer with one JSON object whose `answer.spans`
  array holds objects with a string `answer.label` and `answer.text` (by default `spans`,
  `label`, `text`), or with that array alone when `answer.spans` is `""`. Text up to a closing `</think>` and prose around the object are dropped;
  reasoning-channel tokens are never read.
- **Structured output.** The schema has the items array as its only key, and each item its
  label (a string), its value (a non-empty string) and the `metadata` fields (optional
  strings), in that order and nothing else, which is the order the item is written in. It goes
  to the backend as a `json_schema` constraint: a GBNF grammar on `llama_cpp`, guided decoding
  on `vllm`, forwarded as is by `remote_llm`. Decoding is constrained from the first answer
  token, so keep thinking off (`context: {enable_thinking: false}`) on a model that reasons
  first. A `llama_cpp` model loaded with speculative decoding refuses structured output; set
  `structured_output: false` there. `labels` is not part of the schema: it filters the answer,
  so narrowing it never forces a label onto an item the model would have labelled otherwise.
- **Invalid answers fail closed.** An answer without that object fails the classification
  call, and so does an array entry that is not an object with a non-blank string label and
  value: a detector that cannot read its model must not report a clean text, so a `guard` over
  it fails the pipeline instead of passing the input on. The error names the entry by position
  and field, never its content. For a cut-off answer it suggests raising `max_tokens` or
  lowering `chunk_tokens`; for an input piece that does not fit the context, lowering
  `chunk_tokens`. An empty array is a valid answer: nothing found.
- **Metadata.** Every other scalar field of an item (`ref` for this model: the person the
  data belongs to, the same for every mention of them) is kept as the span's `metadata`, as
  a string, in the order the model wrote them; a repeated occurrence takes its span's
  metadata. With structured output only the fields `answer.metadata` declares can be written.
- **Usage.** The response counts the prompt tokens read and the answer tokens generated,
  summed over the pieces of a split input.
- **Items not found are dropped.** An item whose text does not occur in the input is logged
  as a WARN (count only, never the text) and left out, so behind a `guard` that value passes
  unredacted. The model must copy each value verbatim; one it normalises (spaces dropped from
  a phone number) or one that crosses a `chunk_tokens` boundary is not found.
- **The input can steer the model.** It is the user turn, so a text that tells the model to
  list nothing can be obeyed; `structured_output` fixes the answer's shape, not its content.
  Where a miss is costly, pair it with a `regex` model in a `composite_classifier`.
- **Placement.** Each item is placed case-sensitively before ignoring case, as a whole word
  before as part of a longer word, after the previous item before anywhere: items listed out
  of input order, as when grouped by person, still land on their real mention.
- **Scores.** The engine requests the top 5 candidates per position and computes the
  `score` signal over the tokens of each item, its `ref` excluded. A token counts for one
  item only: the one holding its last character, else the one holding its first, so a token
  that closes one item and opens the next (`"},{"`) grades the item it opens. With `score: none` every
  span scores `1.0` and a trigger threshold is binary, as for `regex`.

  | `score` | Confidence |
  | --- | --- |
  | `max_entropy` (default) | `exp(-entropy)` at the most spread position: the inverse of the effective number of candidates there |
  | `max_token_perplexity`, `answer_max_token_perplexity` | probability of the weakest token |
  | `perplexity`, `answer_perplexity`, `p95_perplexity`, `p90_perplexity`, `tail_perplexity` | inverse perplexity |
  | `mean_logprob`, `answer_mean_logprob` | geometric-mean token probability |
  | `min_margin` | top-1 against top-2 at the most contested token, `sigmoid(margin)` |
  | `uncertain_token_fraction`, `low_margin_fraction` | share of confident tokens |
  | `mean_entropy` | `exp(-mean entropy)` |

  `logprob_slope` and `logprob_stdev` have no such reading and are refused. Most tokens of an
  item are copied from the input at near certainty, so an average dilutes the one token the
  model hesitated on; the peak signals (`max_entropy`, `max_token_perplexity`, `min_margin`)
  do not. `max_entropy` also lowers a correct but long or composite value, such as a full
  address, more than `max_token_perplexity` does. Clear entities score close to `1.0`, so
  pick a threshold on labelled samples. A repeated occurrence takes its span's score;
  Over HTTP, `scores` holds each label's score, or one score per span for a label found in
  several spans; the gRPC `all_scores` map keeps each label's best span. `top_label` is the
  best span (the first on a tie).
  A backend that returns no log-probabilities, such as `remote_llm` today, scores `1.0`.
- **Request `labels` narrow the answer.** `/v1/classify` `labels` keep only those labels; the
  model is asked the same question either way.
- **Offsets** are Java string indices (UTF-16 code units), the unit every classifier and the
  guard's redaction use.
- **Sizing.** A request needs `system_prompt + piece + max_tokens` within the model's
  `n_ctx`, and concurrent classifications share its `n_seq_max` slots with every other caller
  of the model.
- **Order of registration.** It is built after local and remote models and before
  composites, so a `composite_classifier` can list it next to `regex` or `gliner_ner` models,
  and on a client workspace it runs over a `remote_llm`.

## See also

- [Classification](../../guides/classification/README.md).
- [Guards and redaction](../../guides/guards-and-redaction/README.md).
- [llama_cpp](./llama_cpp.md), [vllm](./vllm.md), [remote_llm](./remote_llm.md), [composite_classifier](./composite_classifier.md), [gliner_ner](./gliner_ner.md).
