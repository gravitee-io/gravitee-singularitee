# Embeddings and Reranking

> How to turn text into vectors, compare texts, and rerank documents against a query with ONNX or llama.cpp encoders, over `/v1/embeddings`, `/v1/similarity`, `/v1/rerank`, the `GraviteeVectorService` RPCs, or an `embed` pipeline step.

## Overview

| Need | Model type | Call |
| --- | --- | --- |
| A vector per text | `onnx_embedding` or `llama_cpp_embedding` | `POST /v1/embeddings`, `Embed` / `EmbedBatch` |
| Cosine scores between texts | any embedding model | `POST /v1/similarity`, `TextSimilarity` |
| Documents ordered by relevance to a query | `onnx_reranker` or `llama_cpp_reranker` (cross-encoder) | `POST /v1/rerank`, `TextRerank` |
| Vector math on vectors you already have | none | `CosineSimilarity`, `Rank` (gRPC only) |

A cross-encoder reads `(query, document)` together and scores the pair; it ranks better than cosine similarity but cannot be precomputed. The usual pattern is two-stage: embed to retrieve candidates, then rerank them.

## Key types

- `EmbeddingEngine`: `rxEmbed(EmbedRequest)` returns a vector and a token count; `rxEmbedBatch`.
- `RerankerEngine` / `AbstractRerankerEngine`: scores every pair, sorts descending, applies `top_k`; `RerankResult.index` refers to the original document order.
- `OnnxEmbeddingEngine`, `LlamaCppEmbeddingEngine`, `OnnxRerankerEngine`, `LlamaCppRerankerEngine`.
- `EmbedStepExecutor`: the `embed` pipeline step.
- `EmbeddingsHandler`, `SimilarityHandler`, `RerankHandler` (http).
- `GraviteeVectorService` (`vector.proto`): `Embed`, `EmbedBatch`, `TextSimilarity`, `TextRerank`, `CosineSimilarity`, `Rank`.

## Usage

### Start a server

```bash
./run-server.sh --workspace examples/embedding/bge-m3.yaml              # model id: text-embedding (multilingual, 1024-dim)
./run-server.sh --workspace examples/embedding/bge-small-en.yaml        # model id: text-embedding (English, 384-dim)
./run-server.sh --workspace examples/reranker/bge-reranker-base.yaml    # model id: reranker
```

### Embed

HTTP (`GRAVITEE_HTTP_ENABLED=true`). `input` is a string or an array; `encoding_format` is `float` (default) or `base64`:

```bash
curl -s localhost:8080/v1/embeddings -H 'content-type: application/json' \
  -d '{"model":"text-embedding","input":["bonjour le monde","hello world"]}' | jq '.data[].embedding | length'
```

gRPC:

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/vector.proto \
  -d '{"model_id":"text-embedding","text":"hello world"}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeVectorService/Embed
```

Java:

```java
var client = new SingulariteeClient("localhost", 9090);
EmbedResponse resp = client
  .embed(EmbedRequest.newBuilder().setModelId("text-embedding").setText("hello world").build())
  .blockingGet();
System.out.println(resp.getEmbedding().getValuesCount() + " dims, " + resp.getTokenCount() + " tokens");
```

### Compare texts

`mode: cross` scores every input against every candidate (one row per input); `zipped` pairs them by position and requires equal lengths:

```bash
curl -s localhost:8080/v1/similarity -H 'content-type: application/json' -d '{
  "model": "text-embedding",
  "input": ["cat", "dog"],
  "candidates": ["kitten", "puppy"],
  "mode": "cross"
}' | jq .results
```

On gRPC, `TextSimilarity` returns the `cross` matrix flat, row-major, with `input_count` and `candidate_count`.

### Rerank

```bash
curl -s localhost:8080/v1/rerank -H 'content-type: application/json' -d '{
  "model": "reranker",
  "query": "how do I cook pasta?",
  "documents": ["Boil salted water, add pasta.", "Git rebase rewrites history.", "Al dente means firm to the bite."],
  "top_k": 2
}' | jq .results
```

Each result is `{index, score, document}`; `return_documents: false` omits the text. gRPC: `TextRerank` with `model_id`, `query`, `documents`, `top_k`; Java: `client.textRerank(...)`.

### Declare the models

ONNX embedding (`examples/embedding/bge-m3.yaml`):

```yaml
models:
  - id: text-embedding
    name: BAAI/bge-m3
    type: onnx_embedding
    memory_check: disabled
    onnx_embedding:
      model_path: onnx/model.onnx
      tokenizer_path: tokenizer.json
      config_json_path: config.json
      max_sequence_length: 512
      pooling_mode: CLS
      normalize: true
```

ONNX cross-encoder (`examples/reranker/bge-reranker-base.yaml`):

```yaml
models:
  - id: reranker
    name: BAAI/bge-reranker-base
    type: onnx_reranker
    memory_check: disabled
    onnx_reranker:
      model_path: onnx/model.onnx
      tokenizer_path: tokenizer.json
      config_json_path: config.json
      max_sequence_length: 512
```

GGUF encoders load through llama.cpp; the pooling type is part of the model load, and a template can wrap each input:

```yaml
models:
  - id: gguf-embedding
    name: <org>/<embedding-gguf>
    type: llama_cpp_embedding
    llama_cpp_embedding:
      llama_cpp:
        path: model.gguf
        n_ctx: 2048
        pooling_type: MEAN
      embedding_template: "Instruct: Given a query, retrieve relevant passages.\nQuery: {text}"

  - id: gguf-reranker
    name: <org>/<reranker-gguf>
    type: llama_cpp_reranker
    llama_cpp_reranker:
      llama_cpp:
        path: model.gguf
        n_ctx: 2048
        pooling_type: RANK
      rerank_template: "<query>{query}</query><document>{document}</document>"
```

### Embed inside a pipeline

The `embed` step writes the vector to `output_field` as a JSON array string, for templating or logging. There is no in-pipeline vector math step; for similarity-based branching use the [route step with `embedding_knn`](../routing/README.md).

```yaml
steps:
  - id: embed_prompt
    type: embed
    next_step: generate
    config:
      model_id: text-embedding
      input_field: prompt
      output_field: prompt.embedding
```

## Options

Keys used in this guide. Full lists: [onnx_embedding](../../reference/models/onnx_embedding.md), [llama_cpp_embedding](../../reference/models/llama_cpp_embedding.md), [onnx_reranker](../../reference/models/onnx_reranker.md), [llama_cpp_reranker](../../reference/models/llama_cpp_reranker.md), [embed step](../../reference/steps/embed.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `onnx_embedding.pooling_mode` | string | `MEAN` | `MEAN` or `CLS`; follow the model card. |
| `onnx_embedding.normalize` | bool | `false` | L2-normalise the vector. |
| `onnx_*.max_sequence_length` | int | `0` (library default 510) | Token window; also the split budget. |
| `llama_cpp_embedding.llama_cpp.pooling_type` | string | unset | `MEAN`, `CLS` or `LAST`; `RANK` for a reranker. |
| `llama_cpp_embedding.embedding_template` | string | unset | Wrapper with `{text}` applied to each input. |
| `*_reranker.scoring` | string | unset (auto-detect) | `SIGMOID`, `SOFTMAX` or `LOGIT`. |
| `llama_cpp_reranker.rerank_template` | string | unset | Prompt with `{query}` and `{document}`. |
| `embed.output_field` | string | `<step_id>.embedding` | Context key receiving the serialised vector. |

## Notes

- Long input is split on semantic boundaries and recombined: embeddings take a token-weighted mean of chunk vectors (re-normalised when `normalize: true`); rerankers score each chunk and keep the maximum; token counts in `usage` sum the chunks.
- `TextRerank` and `/v1/rerank` accept an embedding model too: they then fall back to embed-and-cosine. A model that is neither fails with "neither an embedding nor a reranker model".
- `top_k: 0` (the default) returns every document, sorted by score.
- `LOGIT` scoring gives raw scores: comparable within one call, not across models. An unrecognised `scoring` value silently falls back to auto-detection.
- `zipped` similarity with mismatched lengths is a 400 on HTTP.
- `remote_embedding` and `remote_reranker` proxy these model types from another server: see [Remote and multi-server](../remote-and-multi-server/README.md).

## See also

- [Classification](../classification/README.md): the other encoder family.
- [Routing](../routing/README.md): `embedding_knn` routing on reference sentences.
- [Pipelines](../../concepts/pipelines/README.md) and [Workspaces](../../workspaces/README.md).
- [HTTP API](../../api/http/README.md), [gRPC API](../../api/grpc/README.md), [Java client](../../api/java-client/README.md).
- OpenAPI: [embeddings, rerank and similarity](../../../openapi/embeddings.openapi.yaml).
