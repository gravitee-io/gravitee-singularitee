# Routing

> How to branch a pipeline with a `route` step: on a classifier's top label, on embedding similarity to reference sentences, or on the text an earlier LLM step produced.

## Overview

A `route` step reads a context field (`prompt` by default), resolves it to a label with one of three strategies, and continues at the step named by the matching rule. No rule matching means `default_step`, or the pipeline ends if none is set.

| Strategy | `model_id` | Resolves the label by | Match |
| --- | --- | --- | --- |
| `classifier` (default) | a classifier model | the model's top label | exact string equality |
| `embedding_knn` | an embedding model | the single reference sentence nearest by cosine similarity | exact, on the winning rule's label |
| `llm_structured` | none | the input text itself, normalised | case-insensitive `contains`, first rule wins |

## Key types

- `RouteStepExecutor` (engine), including `rxWarmupEmbeddings`, which embeds every rule's sentences at workspace load.
- `RouteStepConfig`, `RouteStepConfig.RouteRule`, `RoutingStrategy`: the route plugin's config records.
- `ClassifierEngine` and `EmbeddingEngine` behind the first two strategies.

## Usage

### Start a server

```bash
./run-server.sh --workspace examples/pipelines/gliner-router.yaml      # pipeline id: gliner-router
./run-server.sh --workspace examples/pipelines/embedding-router.yaml   # pipeline id: embedding-router
```

### Call it

HTTP (`GRAVITEE_HTTP_ENABLED=true`):

```bash
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "gliner-router",
  "messages": [{"role": "user", "content": "How do I implement a binary search tree?"}]
}' | jq -r '.choices[0].message.content'
```

gRPC:

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"embedding-router","messages":{"messages":[{"role":"ROLE_USER","content":"my invoice is wrong"}]}}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

Java: `client.inferPipeline(InferPipelineRequest.newBuilder().setPipelineId("gliner-router")...build())`. Run with `--debug` to see which branch a request took.

### Route on a classifier label

`examples/pipelines/gliner-router.yaml`. The router is a zero-shot classifier, so the route set is the YAML label list; every `rules[].label` must appear verbatim in the model's `labels[].name`:

```yaml
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
          description: User asks about writing, fixing, or understanding code.
        - name: cooking and food recipe
          description: User asks how to prepare food or drinks.
        - name: general conversation
          description: Small talk or queries without a clear specialised intent.

pipelines:
  - id: gliner-router
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
            - label: general conversation
              next_step: respond_general

      - id: respond_code
        type: infer
        role: output
        config:
          model_id: llm
          prompt:
            messages:
              - role: system
                content: "You are an expert software engineer."
              - role: user
                content: "{{prompt}}"
      # respond_cooking / respond_general follow the same shape
```

### Route on example sentences

`examples/pipelines/embedding-router.yaml`. Each rule carries sentences; they are embedded once at load and the single nearest sentence decides, so one sharp example beats several vague ones:

```yaml
- id: route
  type: route
  config:
    model_id: text-embedding
    strategy: embedding_knn
    default_step: respond_general
    rules:
      - label: support
        next_step: respond_support
        sentences:
          - "I was charged twice for my subscription"
          - "the app crashes when I upload a file"
      - label: sales
        next_step: respond_sales
        sentences:
          - "what does the enterprise plan cost?"
          - "can I get a demo of the product?"
```

### Route on an LLM's answer

Let an internal infer step emit the label, then match its output. The route step makes no model call of its own:

```yaml
- id: judge
  type: infer
  role: internal
  next_step: route
  config:
    model_id: llm
    output_field: judge.output
    prompt:
      messages:
        - role: user
          content: "Classify as one of: code, cooking, other. Answer with the word only.\n\n{{prompt}}"
    sampling: { max_tokens: 4, temperature: 0 }

- id: route
  type: route
  config:
    strategy: llm_structured
    input_field: judge.output
    default_step: respond_general
    rules:
      - label: code
        next_step: respond_code
      - label: cooking
        next_step: respond_cooking
```

## Options

Keys used in this guide. Full list: [route step reference](../../reference/steps/route.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | unset | Classifier (`classifier`) or embedding model (`embedding_knn`); unused by `llm_structured`. |
| `strategy` | string | `classifier` | `classifier`, `embedding_knn`, `llm_structured`. |
| `input_field` | string | `prompt` | Context field to route on. |
| `default_step` | string | unset | Step taken when no rule matches; without it the pipeline ends at the route step. |
| `rules[].label` | string | required | Label to match. |
| `rules[].next_step` | string | required | Step to continue at. |
| `rules[].sentences` | list | `[label]` | `embedding_knn` only: reference texts for this rule. |

## Notes

- Set `default_step` on every route step. A blank input, a missing or wrong-typed model, and an unmatched label all resolve to the empty label, which matches nothing; without a default the pipeline ends with no output.
- `classifier` matching is exact. A router that always takes the default branch is almost always a label that differs from the model's `labels[].name`.
- `llm_structured` strips whitespace and surrounding quotes, lowercases, then takes the first rule whose label the text contains. Order rules so the most specific label comes first.
- Reference embeddings are cached per `pipelineId:stepId` at workspace load; editing `sentences` needs a reload.
- A route step's edges are its `rules[].next_step` and `default_step`; it ignores a step-level `next_step`.

## See also

- [Classification](../classification/README.md): the models behind `classifier`.
- [Embeddings and reranking](../embeddings-and-reranking/README.md): the models behind `embedding_knn`.
- [Loops and chain-of-thought](../loops-and-cot/README.md): internal infer steps feeding `llm_structured`.
- [Pipelines](../../concepts/pipelines/README.md) and [Workspaces](../../workspaces/README.md).
- [HTTP API](../../api/http/README.md), [gRPC API](../../api/grpc/README.md), [Java client](../../api/java-client/README.md).
