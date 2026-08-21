# `route`

> Resolves a label for the input with a classifier, embedding similarity or a prior LLM verdict, and jumps to the rule whose `label` matches.

## Overview

`RouteStepExecutor` resolves a label according to `strategy`, then scans `rules` in order and returns the first rule whose `label` equals the resolved label. No match returns `default_step`; an empty `default_step` ends the pipeline. The step ignores the envelope `next_step`.

| `strategy` | Model | Label resolution |
| --- | --- | --- |
| `classifier` (default) | `ClassifierEngine` | The classifier's top label, compared exactly with each rule's `label`. |
| `embedding_knn` | `EmbeddingEngine` | Every rule's `sentences` (or its `label` when none) is embedded; the rule holding the reference with the highest cosine similarity to the embedded input wins. References are computed at workspace load (`rxWarmupEmbeddings`) and cached per `pipeline:step`. |
| `llm_structured` | none | The input text itself is the verdict: trimmed, surrounding quotes removed, lower-cased, then matched with `contains` against each rule's lower-cased `label`, in rule order. Point `input_field` at the output of a `role: internal` infer step. |

## Usage

Embedding KNN (`examples/pipelines/embedding-router.yaml`):

```yaml
- id: route
  type: route
  config:
    model_id: text-embedding
    strategy: embedding_knn
    input_field: prompt
    default_step: respond_general
    rules:
      - label: support
        next_step: respond_support
        sentences:
          - "I was charged twice for my subscription"
          - "I cannot log in to my account"
      - label: sales
        next_step: respond_sales
        sentences:
          - "what does the enterprise plan cost?"
          - "can I get a demo of the product?"
```

Zero-shot classifier (`examples/pipelines/gliner-router.yaml`):

```yaml
- id: route
  type: route
  config:
    model_id: router
    strategy: classifier
    input_field: prompt
    default_step: respond_general
    rules:
      - label: software development question
        next_step: respond_code
      - label: cooking and food recipe
        next_step: respond_cooking
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required for `classifier` and `embedding_knn` | Logical id of the classifier or embedding model. Unused by `llm_structured`. |
| `strategy` | string | `classifier` | `classifier`, `embedding_knn` or `llm_structured`. Unknown values fall back to `classifier`. |
| `input_field` | string | `prompt` | Context key holding the text to route on. Blank text resolves to the empty label and takes `default_step`. |
| `default_step` | string | unset | Step when no rule matches. Unset: the pipeline ends. |
| `rules` | list | required | Ordered list of `{label, next_step, sentences}`. |
| `rules[].label` | string | required | Label to match. For `embedding_knn` without `sentences`, it is also the reference text. |
| `rules[].next_step` | string | required | Target step id. |
| `rules[].sentences` | list of string | unset | `embedding_knn` only: reference sentences embedded for this rule. |

## Context fields

Reads: `input_field` (default `prompt`).

Writes:

| Field | Value |
| --- | --- |
| `<id>.label` | The resolved label (empty when the input was blank or nothing matched for `llm_structured`). |
| `<id>.matched` | `true` when a rule matched, `false` when `default_step` was taken. |

## Notes

- `classifier` compares labels exactly; the rule label must be spelled as the model reports it (GLiNER labels are the workspace-declared names).
- `embedding_knn` picks the single nearest reference, so one strong sentence outweighs many weak ones; there is no score threshold.
- A missing or wrongly typed model logs a warning and resolves the empty label, so the pipeline silently takes `default_step`. Check the log when every request lands on the default branch.
- Reference embeddings live in the node cache `ai-route-embeddings` when a cache manager is wired, so several nodes share the warm-up.

## See also

- [Routing guide](../../guides/routing/README.md)
- [`classify`](./classify.md), [`embed`](./embed.md)
- [Context fields](../context-fields.md)
