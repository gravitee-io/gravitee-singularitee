# onnx_reranker

> A cross-encoder exported to ONNX that scores `(query, document)` pairs directly for reranking.

## Overview

`onnx_reranker` loads an ONNX cross-encoder plus its tokenizer and serves it as a
`RerankerEngine` (`reranking`): `TextRerank` over gRPC and `POST /v1/rerank` over HTTP.
Each document is scored against the query; results come back sorted by score with the index
into the original list. Documents longer than the window are split and scored chunk by
chunk, keeping the maximum. `OnnxRerankerFactory` builds the engine; `OnnxModelResolver`
downloads the files.

## Usage

`examples/reranker/bge-reranker-base.yaml`:

```yaml
workspace:
  name: reranker
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

## Options

`onnx_reranker:` block (`WorkspaceDefinition.OnnxRerankerDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_path` | string | unset | ONNX file inside the repository. Required. |
| `tokenizer_path` | string | unset | Tokenizer file or directory prefix. Required; blank fails the load. |
| `config_json_path` | string | unset | Model `config.json`. Optional. |
| `max_sequence_length` | int | library default | Cap for the concatenated pair; oversized documents are split against it, with the query's tokens reserved. |
| `scoring` | string | unset (auto-detect) | `SIGMOID` (one-logit heads), `SOFTMAX` (two-class heads), `LOGIT` (raw score). Unknown values fall back to auto-detection from the output shape. |

## Notes

- **`LOGIT` is ordering only.** Raw scores are monotonic within a model and not comparable
  across models; `SIGMOID` / `SOFTMAX` give probabilities.
- **`top_k: 0` returns every document**, sorted descending. `RerankResult.index` always
  refers to the original order.
- **What gets downloaded.** Same as the other ONNX types: `model_path` with its directory
  siblings, `config_json_path`, the tokenizer file, prefix, or well-known root files.
- **Platforms.** CPU by default; `-Pcuda` for the GPU ONNX Runtime on Linux.

## See also

- [Embeddings and reranking](../../guides/embeddings-and-reranking/README.md).
- [llama_cpp_reranker](./llama_cpp_reranker.md), GGUF cross-encoders and chat-style rerankers.
- [onnx_embedding](./onnx_embedding.md).
