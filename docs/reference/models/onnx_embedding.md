# onnx_embedding

> A BERT-family bi-encoder exported to ONNX that turns text into a dense vector, with CLS or mean pooling and optional L2 normalisation.

## Overview

`onnx_embedding` loads an ONNX encoder plus its tokenizer and serves it as an
`EmbeddingEngine` (`feature-extraction`): `Embed` / `EmbedBatch` over gRPC,
`POST /v1/embeddings` over HTTP, the `embed` pipeline step, and the text-in similarity and
rerank fallbacks of `GraviteeVectorService`. Inputs longer than the window are split and
their vectors combined into a token-weighted mean. `OnnxEmbeddingFactory` builds the engine;
`OnnxModelResolver` downloads the files.

## Usage

`examples/embedding/bge-small-en.yaml`:

```yaml
workspace:
  name: embedding
  models:
    - id: text-embedding
      name: BAAI/bge-small-en-v1.5
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

`examples/embedding/bge-m3.yaml` is the multilingual 1024-dimension variant with the same
block.

## Options

`onnx_embedding:` block (`WorkspaceDefinition.OnnxEmbeddingDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_path` | string | unset | ONNX file inside the repository. Required. |
| `tokenizer_path` | string | unset | Tokenizer file or directory prefix. Required; blank fails the load. |
| `config_json_path` | string | unset | Model `config.json`. Optional. |
| `max_sequence_length` | int | library default | Token cap per input; longer inputs are split and recombined. |
| `pooling_mode` | string | `MEAN` | `CLS` or `MEAN`. Unknown values fall back to `MEAN`. |
| `normalize` | boolean | `false` | L2-normalise the output vector. |

## Notes

- **Pooling must match the model card.** BGE models want `CLS` with `normalize: true`; most
  sentence-transformers want `MEAN`.
- **Long inputs.** Chunks are embedded separately and reduced to a mean weighted by content
  tokens, re-normalised when `normalize: true`. Token counts in `usage` are summed across
  chunks.
- **What gets downloaded.** Same as `onnx_classifier`: `model_path` with its directory
  siblings, `config_json_path`, and the tokenizer file, prefix, or well-known root files.
  `download.exclude` narrows the listings only.
- **Rerank fallback.** `TextRerank` and `/v1/rerank` against an embedding model fall back to
  embed-plus-cosine; a true cross-encoder is an `onnx_reranker` or `llama_cpp_reranker`.
- **Platforms.** CPU by default; `-Pcuda` for the GPU ONNX Runtime on Linux.

## See also

- [Embeddings and reranking](../../guides/embeddings-and-reranking/README.md).
- [Routing](../../guides/routing/README.md), embedding-KNN routing over these vectors.
- [llama_cpp_embedding](./llama_cpp_embedding.md), GGUF encoders through llama.cpp.
- [onnx_reranker](./onnx_reranker.md).
