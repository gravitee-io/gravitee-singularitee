# llama_cpp_reranker

> A GGUF cross-encoder served by llama.cpp with RANK pooling, for BERT-style and chat-style rerankers.

## Overview

`llama_cpp_reranker` loads a GGUF that exports a classifier head and serves it as a
`RerankerEngine` (`reranking`): `TextRerank` and `/v1/rerank`. The factory forces
`pooling_type: RANK` whatever the YAML says. `rerank_template` formats the pair for
chat-style rerankers such as Qwen3-Reranker; BERT-family GGUFs use plain concatenation.
`LlamaCppRerankerFactory` builds the engine; `GgufModelResolver` downloads the file.

## Usage

```yaml
workspace:
  name: gguf-reranker
  models:
    - id: reranker
      name: some-org/reranker-gguf
      type: llama_cpp_reranker
      llama_cpp:
        path: model.gguf                     # file to download: read from this model-level block
      llama_cpp_reranker:
        llama_cpp:
          n_ctx: 2048
        scoring: SIGMOID
        rerank_template: "<query>{query}</query><document>{document}</document>"
```

## Options

`llama_cpp_reranker:` block (`WorkspaceDefinition.LlamaCppRerankerDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `llama_cpp` | block | all defaults | Engine parameters, below. `pooling_type` is overridden to `RANK`. |
| `scoring` | string | unset (auto-detect) | `SIGMOID` (one-logit heads), `SOFTMAX` (two-class heads), `LOGIT` (raw). Unknown values fall back to auto-detection. |
| `rerank_template` | string | unset (plain concatenation) | Prompt with `{query}` and `{document}` placeholders. |

Nested `llama_cpp:` keys the factory forwards (`LlamaCppRerankerFactory`):

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `n_ctx` | int | `512` | Context window for the concatenated pair; longer documents are split against it. |
| `n_batch` | int | `512` | Logical batch size. |
| `n_ubatch` | int | `512` | Physical micro-batch size. |
| `n_seq_max` | int | `8` | Parallel sequences. |
| `n_gpu_layers` | int | `999` | Layers offloaded to the GPU. |
| `attention_type` | string | `UNSPECIFIED` | `CAUSAL` or `NON_CAUSAL`. |
| `flash_attn_type` | string | `AUTO` | `AUTO`, `ENABLED`, `DISABLED`. |
| `offload_kqv` | boolean | `true` | Keep KV on the GPU. |
| `lora_path` | string | unset | LoRA adapter, as a local path. |

The model-level `llama_cpp.path` (outside the `llama_cpp_reranker:` block) names the GGUF
file to download from `name`.

## Notes

- **The GGUF must carry a classifier head.** A plain causal LM converted to GGUF has no
  RANK output and fails at load.
- **Scoring per chunk, maximum per document.** A document longer than `n_ctx` minus the
  query is split; the document's score is the maximum over its chunks.
- **`LOGIT` is ordering only**; scores are not comparable across models.
- **What gets downloaded.** Exactly the model-level `llama_cpp.path` from `name`;
  `download.exclude` has no effect on this type.
- **Same distribution flavour as `llama_cpp`.**

## See also

- [Embeddings and reranking](../../guides/embeddings-and-reranking/README.md).
- [llama_cpp](./llama_cpp.md), the full `llama_cpp` key reference and platform notes.
- [onnx_reranker](./onnx_reranker.md).
- [llama_cpp_embedding](./llama_cpp_embedding.md).
