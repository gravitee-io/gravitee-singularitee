# Embeddings & Reranking

> Turn text into dense vectors and score documents against queries — ONNX BERT or llama.cpp backends, over gRPC, pipeline steps, or the OpenAI/Cohere-style HTTP endpoints.

## Overview
The vector side of the server lives in `GraviteeVectorService` (`vector.proto`). Two
model families produce embeddings: `onnx_embedding` (BERT encoders via ONNX Runtime,
CPU-friendly, e.g. BGE-M3) and `llama_cpp_embedding` (GGUF encoders through llama.cpp
with MEAN/CLS/LAST pooling and an optional instruction template). Two families rerank:
`onnx_reranker` and `llama_cpp_reranker` — cross-encoders that score `(query, document)`
pairs directly. On top of raw `Embed`/`EmbedBatch`, the service exposes high-level
text-in/scores-out RPCs: `TextSimilarity` (cosine between two text arrays, cross-product
or zipped) and `TextRerank` (cross-encoder when the model is a reranker, bi-encoder
embed+cosine fallback when it is an embedding model), plus pure vector math
(`CosineSimilarity`, `Rank`). HTTP mirrors these as OpenAI-style `POST /v1/embeddings`,
Cohere-style `POST /v1/rerank`, and the Gravitee extension `POST /v1/similarity`.
Pipelines consume embeddings through the `embed` step (`EmbedStepExecutor`). Inputs
longer than the model window are split and recombined automatically (see Notes).

## Key types
- `EmbeddingEngine` — engine interface: `rxEmbed(EmbedRequest)` → `EmbedResponse(float[] embedding, int tokenCount)`, plus `rxEmbedBatch(List<String>)`.
- `OnnxEmbeddingEngine` / `OnnxEmbeddingFactory` — `onnx_embedding` models over `OnnxBertEmbeddingModel` (pooling `MEAN` or `CLS`, optional L2 normalisation).
- `LlamaCppEmbeddingEngine` / `LlamaCppEmbeddingFactory` — `llama_cpp_embedding` models over `LlamaCppEmbeddingModel`; the GGUF must load with a pooling type, and `embedding_template` can wrap each input (instruction-aware models).
- `RerankerEngine` / `AbstractRerankerEngine` — shared rerank flow: build `(query, doc)` `RerankPair`s, score them all, sort descending, apply `top_k`; `RerankRequest(query, documents, topK)` → `RerankResponse(List<RerankResult>, totalTokens)` with `RerankResult(index, score)` (index into the *original* documents list).
- `OnnxRerankerEngine` — `onnx_reranker` over `OnnxBertRerankerModel`; scoring `SIGMOID` / `SOFTMAX` / `LOGIT`, auto-detected from output shape when unset.
- `LlamaCppRerankerEngine` — `llama_cpp_reranker` over `LlamaCppRerankerModel` (GGUF with `pooling_type: RANK`); same scoring options plus `rerank_template` with `{query}`/`{document}` placeholders for chat-style rerankers (Qwen3-Reranker).
- `EmbedStepExecutor` — executes the `embed` pipeline step; writes the vector into the pipeline context as a JSON-style array string.
- `GraviteeVectorService` RPCs (`vector.proto`): `CosineSimilarity`, `Rank`, `Embed`, `EmbedBatch`, `TextSimilarity`, `TextRerank`.
- `EmbeddingsHandler` / `RerankHandler` / `SimilarityHandler` — HTTP handlers for `/v1/embeddings`, `/v1/rerank`, `/v1/similarity`.
- `RecursiveTextSplitter` — token-budget-aware splitter behind the oversized-input handling of embeddings, classifiers, and rerankers.

## Usage
ONNX embedding model — `examples/embedding/bge-m3.yaml` (BGE-M3, multilingual,
1024-dim, CLS pooling, L2-normalized):

```yaml
workspace:
  name: embedding
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

llama.cpp-backed embedding and reranker models:

```yaml
workspace:
  models:
    - id: gguf-embedding
      name: some-org/embedding-gguf
      type: llama_cpp_embedding
      llama_cpp_embedding:
        llama_cpp:
          path: model.gguf
          n_ctx: 2048
          pooling_type: MEAN
        embedding_template: "Instruct: Given a query, retrieve relevant passages.\nQuery: {text}"

    - id: gguf-reranker
      name: some-org/reranker-gguf
      type: llama_cpp_reranker
      llama_cpp_reranker:
        llama_cpp:
          path: model.gguf
          n_ctx: 2048
          pooling_type: RANK
        scoring: SIGMOID
        rerank_template: "<query>{query}</query><document>{document}</document>"
```

`embed` pipeline step:

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

HTTP — `POST /v1/embeddings` (OpenAI-compatible; `encoding_format` `float` or `base64`;
requires `http.enabled: true`, default port 8080):

```bash
curl -s http://localhost:8080/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model": "text-embedding", "input": ["Bonjour le monde", "Hello world"]}'
# → {"object":"list","model":"text-embedding",
#    "data":[{"object":"embedding","index":0,"embedding":[0.0123,...]}, ...],
#    "usage":{"prompt_tokens":12,"total_tokens":12}}
```

`POST /v1/rerank` (Cohere-style):

```bash
curl -s http://localhost:8080/v1/rerank \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "text-embedding",
    "query": "What is the capital of France?",
    "documents": ["Paris is the capital of France.",
                  "Berlin is the capital of Germany.",
                  "The Eiffel Tower is in Paris."],
    "top_k": 2
  }'
# → {"object":"rerank","model":"text-embedding",
#    "results":[{"index":0,"score":0.91,"document":"Paris is the capital of France."},
#               {"index":2,"score":0.74,"document":"The Eiffel Tower is in Paris."}],
#    "usage":{"total_tokens":42}}
```

`POST /v1/similarity` (Gravitee extension — `cross` scores every input against every
candidate, `zipped` pairs them positionally):

```bash
curl -s http://localhost:8080/v1/similarity \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "text-embedding",
    "input": ["cat", "dog"],
    "candidates": ["kitten", "puppy"],
    "mode": "cross"
  }'
# cross  → "results": [[0.83, 0.41], [0.39, 0.85]]   (|input| rows × |candidates| cols)
# zipped → "results": [0.83, 0.85]                    (arrays must be the same length)
```

gRPC equivalents: `Embed(EmbedRequest{model_id, text})` → `EmbedResponse{embedding, token_count}`;
`TextRerank(TextRerankRequest{model_id, query, documents, top_k})`;
`TextSimilarity(TextSimilarityRequest{model_id, input, candidates, mode})` with
`SIMILARITY_MODE_CROSS` (flat row-major `|input| × |candidates|` scores) or
`SIMILARITY_MODE_ZIPPED`.

## Options

### `onnx_embedding`
| Field | Default | Purpose |
| --- | --- | --- |
| `model_path` | — | ONNX model file inside the HF bundle. |
| `tokenizer_path` | — | HuggingFace tokenizer file/directory. |
| `config_json_path` | — | Optional `config.json`. |
| `max_sequence_length` | `0` (library default 510) | Tokenizer sequence cap; also the split budget. |
| `pooling_mode` | `MEAN` | `MEAN` or `CLS`. |
| `normalize` | `false` | L2-normalise the output vector. |

### `llama_cpp_embedding`
| Field | Default | Purpose |
| --- | --- | --- |
| `llama_cpp` | — | Core llama.cpp params (`n_ctx`, `n_batch`, `n_gpu_layers`, `pooling_type` MEAN/CLS/LAST, ...). |
| `embedding_template` | empty (raw text) | Instruction wrapper applied to each input before tokenisation. |

### `onnx_reranker` / `llama_cpp_reranker`
| Field | Default | Purpose |
| --- | --- | --- |
| `model_path` / `llama_cpp` | — | ONNX file, or core llama.cpp params (GGUF needs `pooling_type: RANK`). |
| `scoring` | empty (auto-detect) | `SIGMOID`, `SOFTMAX`, or `LOGIT`; auto-selected from the model's output shape / `nClsOut` when unset. |
| `rerank_template` (llama.cpp only) | empty (plain concatenation) | Chat-style prompt with `{query}` / `{document}` placeholders. |
| `max_sequence_length` (ONNX only) | `0` (510) | Pair sequence cap; oversized documents split against it. |

### `embed` step config
| Field | Default | Purpose |
| --- | --- | --- |
| `model_id` | — | Logical id of an embedding model. |
| `input_field` | step input | Pipeline-context key to read text from. |
| `output_field` | `<step_id>.embedding` | Key the serialised `float[]` (JSON array string) is written to. |

## Notes
- **Smart content split** — no silent truncation anywhere. Text longer than the model window is split by `RecursiveTextSplitter` on semantic boundaries (paragraph → line → sentence → clause, token-window fallback, never mid-word), batched through the encoder, and recombined per family: embeddings reduce chunk vectors to a single **token-weighted mean** (weight = content tokens per chunk, minus specials) re-normalised when `normalize: true`; rerankers score each `(query, chunk)` pair and take the **max over chunks** ("a document is as relevant as its most relevant passage"), reserving the query's tokens from the budget; classifiers keep per-split rows with max-per-label rollup (see the Classification page).
- **`TextRerank` model dispatch**: if `model_id` resolves to a `RerankerEngine` you get true cross-encoder scoring; if it resolves to an `EmbeddingEngine` the service falls back to bi-encoder embed+cosine; anything else fails with "neither an embedding nor a reranker model". The `/v1/rerank` example above works against `text-embedding` only through that fallback.
- **`top_k = 0` returns all** results, sorted by score descending — in `Rank`, `TextRerank`, and `/v1/rerank` alike. `RerankResult.index` always refers to the original `documents` order.
- **`zipped` similarity requires equal lengths**; the HTTP handler rejects mismatched `input`/`candidates` with 400. `cross` returns `|input| × |candidates|` scores — flat row-major on gRPC, nested rows on HTTP.
- **`/v1/embeddings` extras**: `encoding_format: "base64"` emits little-endian float32 bytes (OpenAI-compatible); `return_documents` on `/v1/rerank` defaults to `true`.
- **Scoring auto-detect** (`AbstractRerankerEngine.parseScoring`) treats an unrecognised `scoring` string as unset — it silently falls back to shape-based auto-detection instead of failing. `LOGIT` returns raw scores: monotonic ordering only, not comparable across models.
- **Token counts are real**: every `EmbedResponse`/`RerankResponse` carries the encoder token count (summed across chunks for split inputs); HTTP surfaces them under `usage`.
- **Pipelines get vectors as strings**: `EmbedStepExecutor` stores `Arrays.toString(embedding)` in the context — fine for templating or logging, but there is no in-pipeline vector math step; use the vector RPCs for similarity.
- **Pooling must match the model card**: BGE-M3 wants `CLS` + `normalize: true`; most sentence-transformers want `MEAN`. For llama.cpp embeddings the pooling type is a context parameter of the GGUF load, not a post-hoc choice.

## See also
- [Classification](../classification/README.md) — the classifier engines that share the `RecursiveTextSplitter` smart content split.
- [Pipelines](../pipelines/README.md) — where the `embed` step runs.
- [OpenAI HTTP API](../openai-http-api/README.md) — enabling the HTTP listener that serves `/v1/embeddings`, `/v1/rerank`, `/v1/similarity`.
- [gRPC API & Client](../grpc-api-and-client/README.md) — calling `GraviteeVectorService` directly.
- [Workspaces](../workspaces/README.md) — declaring embedding/reranker models in `workspace.yaml`.
- [Remote & Multi-Server](../remote-and-multi-server/README.md) — `remote_embedding` / `remote_reranker` proxies to another server.
