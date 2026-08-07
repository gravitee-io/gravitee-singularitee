# Routing

> Branch a pipeline to different steps based on a classifier label, embedding similarity, or an LLM's structured output.

## Overview
A `route` step reads a context field (the prompt by default), resolves it to a label
using one of three strategies, and jumps to the step declared by the matching rule.
`classifier` asks a `ClassifierEngine` for its top label; `embedding_knn` embeds the
input and picks the rule whose reference-sentence embedding is nearest by cosine
similarity; `llm_structured` treats the input text itself (typically the output of a
prior LLM judge step) as the label and fuzzy-matches it against the rule labels. When no
rule matches, execution falls through to `default_step` — or the pipeline simply ends if
none is set. The classic use is zero-shot semantic routing: a GLiNER classifier whose
labels are declared in YAML picks a persona branch, no fine-tuning required.

## Key types
- `RouteStepExecutor` — executes `type: route`; dispatches on `RoutingStrategy` and maps the resolved label to a `RouteRule.next_step_id`.
- `RouteStepConfig` / `RouteRule` / `RoutingStrategy` — proto definitions in `pipeline.proto`. YAML strategy names map to `ROUTING_STRATEGY_CLASSIFIER` (default), `ROUTING_STRATEGY_EMBEDDING_KNN`, `ROUTING_STRATEGY_LLM_STRUCTURED`.
- `ClassifierEngine` — backs the `classifier` strategy (`rxClassify(...).topLabel()`).
- `EmbeddingEngine` — backs the `embedding_knn` strategy (`rxEmbed` for both references and query).
- `RouteStepExecutor.rxWarmupEmbeddings(Pipeline)` — pre-computes and caches the reference embeddings of every KNN route step at workspace load time, so runtime routing costs a single embedding call.

## Usage
Classifier routing — `examples/modular/pipelines/routing.yaml` branches to one of four persona
infer steps:

```yaml
workspace:
  pipelines:
    - id: routing-pipeline
      name: GLiNER Zero-Shot Semantic Routing Pipeline
      entry: route

      steps:
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
              - label: financial advice
                next_step: respond_finance
              - label: general conversation
                next_step: respond_general

        - id: respond_code
          type: infer
          role: output
          config:
            model_id: llm
            output_field: respond_code.output
            prompt:
              messages:
                - role: system
                  content: "You are an expert software engineer. Provide clear, concise technical answers with code examples when appropriate."
                - role: user
                  content: "{{prompt}}"
        # ... respond_cooking / respond_finance / respond_general follow the same shape
```

The `router` model is a zero-shot GLiNER classifier whose labels ARE the routing schema —
edit the YAML to change routes, no retraining (`examples/classifier/intent-gliner.yaml`):

```yaml
workspace:
  models:
    - id: router
      name: gravitee-io/gliner4j-gliner2-base-v1
      type: gliner_classifier
      memory_check: disabled
      gliner_classifier:
        variant: onnx_fp16
        threshold: 0.3
        labels:
          - name: software development question
            description: >
              User asks about writing, fixing, or understanding code; debugging; algorithms;
              data structures; system design; APIs; programming languages, tools, or frameworks.
          - name: cooking and food recipe
            description: >
              User asks how to prepare food or drinks; requests recipes, cooking steps,
              ingredient lists, substitutions, measurements, or kitchen techniques.
          # ...
```

The zero-shot equivalent ships in `examples/classifier/intent-gliner.yaml` — a
`gliner_classifier` (`gravitee-io/gliner4j-gliner2-base-v1`, `onnx_fp16`,
`threshold: 0.5`) whose labels are supplied per deployment.

Embedding-KNN routing with reference sentences:

```yaml
- id: route
  type: route
  config:
    model_id: embedding          # must be an EmbeddingEngine
    strategy: embedding_knn
    default_step: respond_general
    rules:
      - label: code
        next_step: respond_code
        sentences:
          - "How do I fix this bug in my Java service?"
          - "Write a function that parses a CSV file."
      - label: cooking
        next_step: respond_cooking
        sentences:
          - "What's a good recipe for pasta carbonara?"
```

## Options

### `route` (`RouteStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `model_id` | — | Workspace model id: a `ClassifierEngine` for `classifier`, an `EmbeddingEngine` for `embedding_knn`; unused by `llm_structured`. |
| `strategy` | `classifier` | `classifier`, `embedding_knn`, or `llm_structured`. |
| `input_field` | `prompt` | Context field whose value is routed on. |
| `rules` | `[]` | List of `{label, next_step, sentences?}` entries. |
| `default_step` | — | Step to take when no rule matches; when unset the pipeline ends at the route step. |

### `rules[]` (`RouteRule`)
| Field | Default | Purpose |
| --- | --- | --- |
| `label` | — (required) | Label to match against the resolved route label (exact match for `classifier`/`embedding_knn`, case-insensitive `contains` for `llm_structured`). |
| `next_step` | — (required) | Step id to branch to when this rule wins. |
| `sentences` | `[label]` | `embedding_knn` only: reference sentences embedded per rule; the rule owning the single nearest reference wins. When empty, the label text itself is the sole reference. |

## Notes
- **How each strategy resolves the label**: `classifier` returns the engine's top label verbatim; `embedding_knn` embeds the input and returns the label of the single reference embedding with the highest cosine similarity (best-matching sentence wins, not a per-rule average); `llm_structured` normalizes the input text (strip, lowercase, unwrap surrounding quotes) and returns the first rule whose label it *contains* — rule order matters for overlapping labels.
- **`llm_structured` routes on prior output, not a model call**: the route step itself invokes no model — point `input_field` at a preceding internal infer step's `output_field` (an LLM judge that emits the label) and match its text against the rule labels.
- **Rule labels must equal classifier labels** for the `classifier` strategy — matching is exact string equality against `topLabel()`. With a zero-shot GLiNER model, keep the route `rules[].label` values identical to the model's `labels[].name` entries.
- **KNN references are cached**: reference embeddings are keyed by `pipelineId:stepId` and pre-computed at workspace load by `rxWarmupEmbeddings`; on a cache miss they are computed lazily on first request and cached from then on. Changing `sentences` requires a workspace reload.
- **Failure modes route to `default_step`**: a blank input, a missing model, a wrong engine type, or an unmatched label all resolve to the empty label, which matches no rule — execution falls through to `default_step`, or the pipeline ends silently when none is configured. Set `default_step` on every route step.
- **Route steps declare their own edges**: `next_step` targets live inside `rules[]`/`default_step`, not in the pipeline's `next_step`/`edges` wiring.

## See also
- [Classification](../classification/README.md) — the classifier engines (GLiNER zero-shot, ONNX, regex, composite) behind the `classifier` strategy.
- [Embeddings & Reranking](../embeddings-and-reranking/README.md) — the embedding engines behind `embedding_knn`.
- [Pipelines](../pipelines/README.md) — the DAG model and how routing edges fit into it.
- [Loops & Chain-of-Thought](../loops-and-cot/README.md) — internal infer steps whose output feeds `llm_structured` routing.
- [Workspaces](../workspaces/README.md) — declaring the router model and pipeline in one workspace YAML.
