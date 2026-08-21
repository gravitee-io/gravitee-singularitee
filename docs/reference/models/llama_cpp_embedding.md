# llama_cpp_embedding

> A GGUF encoder served by llama.cpp as an embedding model, with MEAN, CLS or LAST pooling and an optional instruction template.

## Overview

`llama_cpp_embedding` loads a GGUF with the same llama.cpp runtime as `llama_cpp` but opens
it as an `EmbeddingEngine` (`feature-extraction`). It serves the same surfaces as
`onnx_embedding` (`Embed`, `/v1/embeddings`, the `embed` step) and is the way to run GGUF
encoders such as Qwen3-Embedding or BGE-M3 conversions with GPU offload.
`LlamaCppEmbeddingFactory` builds the engine; `GgufModelResolver` downloads the file.

## Usage

```yaml
workspace:
  name: gguf-embedding
  models:
    - id: text-embedding
      name: some-org/embedding-gguf          # HuggingFace repository
      type: llama_cpp_embedding
      llama_cpp:
        path: model.gguf                     # file to download: read from this model-level block
      llama_cpp_embedding:
        llama_cpp:
          n_ctx: 2048
          pooling_type: MEAN
        embedding_template: "Instruct: Given a query, retrieve relevant passages.\nQuery: {text}"
```

## Options

`llama_cpp_embedding:` block (`WorkspaceDefinition.LlamaCppEmbeddingDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `llama_cpp` | block | all defaults | Engine parameters, below. |
| `embedding_template` | string | unset (raw text) | Wrapper applied to each input before tokenisation; `{text}` is replaced by the input. For instruction-aware models. |

Nested `llama_cpp:` keys the factory forwards, with the encoder defaults
(`LlamaCppEmbeddingFactory`):

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `n_ctx` | int | `512` | Context window per sequence; inputs beyond it are split and recombined. |
| `n_batch` | int | `512` | Logical batch size. |
| `n_ubatch` | int | `512` | Physical micro-batch size. |
| `n_seq_max` | int | `8` | Parallel sequences. |
| `n_gpu_layers` | int | `999` | Layers offloaded to the GPU. |
| `pooling_type` | string | `UNSPECIFIED` (the GGUF's own) | `MEAN`, `CLS`, `LAST`, `NONE`. |
| `attention_type` | string | `UNSPECIFIED` | `CAUSAL` or `NON_CAUSAL`. |
| `flash_attn_type` | string | `AUTO` | `AUTO`, `ENABLED`, `DISABLED`. |
| `offload_kqv` | boolean | `true` | Keep KV on the GPU. |
| `lora_path` | string | unset | LoRA adapter, as a local path (not resolved from the repository for this type). |

The model-level `llama_cpp.path` (outside the `llama_cpp_embedding:` block) names the GGUF
file to download from `name`; `YamlWorkspaceLoader` reads the file name from that block only.

## Notes

- **Pooling is a load-time choice.** For llama.cpp encoders the pooling type is a context
  parameter of the GGUF load, not a post-processing option; set it to what the model card
  says. Without a pooling type the model's own metadata applies.
- **Memory is `n_ctx x n_seq_max`** plus the weights, as for `llama_cpp`; encoders are small
  so the default `512 x 8` is cheap.
- **Long inputs** are split on semantic boundaries and reduced to a token-weighted mean,
  same as the ONNX embedder.
- **What gets downloaded.** Exactly the model-level `llama_cpp.path` from `name`, cached
  under `<cache>/<org>/<model>/`. `download.exclude` has no effect on this type.
- **Same distribution flavour as `llama_cpp`.** The factory is registered when llamaj.cpp
  is on the classpath (`-Pdist-llama` or the default build).

## See also

- [Embeddings and reranking](../../guides/embeddings-and-reranking/README.md).
- [llama_cpp](./llama_cpp.md), the full `llama_cpp` key reference and platform notes.
- [onnx_embedding](./onnx_embedding.md).
- [llama_cpp_reranker](./llama_cpp_reranker.md).
