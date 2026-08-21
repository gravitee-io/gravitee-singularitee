# `embed`

> Embeds a context field with an embedding model and writes the vector as a JSON-like float array string.

## Overview

`EmbedStepExecutor` binds to an `EmbeddingEngine` (`onnx_embedding`, `llama_cpp_embedding`, `remote_embedding`). It embeds the text in `input_field` and writes `Arrays.toString(float[])` (for example `[0.12, -0.03, ...]`) to `output_field`. Nothing in the engine consumes that string again; it exists for templates, sub-pipelines and clients reading the context. For similarity-based branching use [`route`](./route.md) with `strategy: embedding_knn`, which embeds internally.

## Usage

```yaml
- id: vectorize
  type: embed
  next_step: generate
  config:
    model_id: text-embedding
    input_field: prompt
    output_field: vectorize.embedding
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published embedding model. |
| `input_field` | string | `prompt` | Context key holding the text to embed. An empty value skips the step with a warning. |
| `output_field` | string | `<id>.embedding` | Context key receiving the serialised vector. |

## Context fields

Reads: `input_field` (default `prompt`).

Writes:

| Field | Value |
| --- | --- |
| `<output_field>` | The vector as `[f0, f1, ...]`. |

## Notes

- The vector is a string like every other context field; a 1024-dimension embedding is several kilobytes of text that a `sub_pipeline` forwards in its context snapshot.
- Dimension and normalisation come from the model definition (`pooling_mode`, `normalize`), not from the step.

## See also

- [Embeddings and reranking guide](../../guides/embeddings-and-reranking/README.md)
- [`route`](./route.md)
- [Context fields](../context-fields.md)
