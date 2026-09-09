# gliner_ner

> Zero-shot entity extraction with GLiNER: declare the entity types in YAML and get labelled spans with character offsets.

## Overview

`gliner_ner` loads a GLiNER model directory with gliner4j and serves it as a
`ClassifierEngine` reporting `token-classification`. Each hit is a `ClassifyResult` with
the entity name as label, a score, the matched text and its `start` / `end` offsets, which
the `guard` step uses for redaction. `GlinerNerFactory` builds the engine;
`GlinerModelResolver` downloads the directory.

## Usage

`examples/classifier/pii-gliner.yaml` (abbreviated; the full file declares the whole PII
schema):

```yaml
workspace:
  name: pii-gliner
  models:
    - id: pii
      name: gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi
      type: gliner_ner
      memory_check: disabled
      gliner_ner:
        variant: onnx_fp16
        threshold: 0.4
        entities:
          - name: person
            description: Full name of a real individual
          - name: email
            description: Email address
          - name: phone_number
            description: Phone or mobile number
          - name: national_id_number
            description: National ID / SSN-like number
```

## Options

`gliner_ner:` block (`WorkspaceDefinition.GlinerNerDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_dir` | string | resolved from `name` | Root directory holding `gliner4j_config.json`, tokenizer files and the `onnx*/` variant folders (or `gguf/` for a llama.cpp bundle). |
| `entities` | list of `{name, description}` | unset | Entity types to extract. `description` is optional. |
| `threshold` | float | `0` (no filtering) | Minimum score for a span to be reported. |
| `variant` | string | `onnx` | ONNX bundles: `onnx`, `onnx_fp16`, `onnx_quantized`. llama.cpp/ggml bundles (`"engine": "llamacpp"` in `gliner4j_config.json`, weights under `gguf/`): the GGUF quantisation, `q8_0` or `q4_0`; anything else selects the f16 `model.gguf`. |
| `token_cap` | int | `512` | Encoder window including the entity prompt; longer inputs are chunked. |

## Engines

The bundle decides the engine: gliner4j-core reads `"engine"` from `gliner4j_config.json` and
dispatches to ONNX Runtime (default) or, when `gliner4j-llamacpp` is on the classpath, to the
llama.cpp/ggml implementation of the same family. The GLiNER4j ggml bundles are separate
HuggingFace repositories (`<org>/gliner4j-<model>-llamacpp`); point `name` at one and the resolver
downloads its `gguf/` weights (only the requested quantisation) instead of an `onnx*/` folder.
On CUDA the ggml engine offloads to the GPU by default (`GRAVITEE_GLINER_EXECUTION_PROVIDER=cpu`
keeps it on the CPU) and uses the llama.cpp natives from `LLAMA_CPP_LIB_PATH`; the CUDA image
also carries gliner4j's `libggml-deberta` plugin under `<natives>/plugins/`, the fused DeBERTa
kernels used there.
Thread and batching knobs are the `GRAVITEE_GLINER_*` variables in both cases.

## Notes

- **Spans are real.** Unlike a chunked `gliner_classifier`, every result's offsets locate an
  entity in the original text; chunk offsets are shifted back before the response is built.
- **Per-request entity sets** arrive through `ClassifyRequest.labels`, same as the classifier.
- **Combine with regex.** A `composite_classifier` over a `regex` model and a `gliner_ner`
  model gives one guard both deterministic patterns and learned entities.
- **What gets downloaded.** Root files plus the `variant` sub-folder only; `download.exclude`
  narrows it further.
- **Runtime knobs** are the `GRAVITEE_GLINER_*` environment variables listed on
  [gliner_classifier](./gliner_classifier.md).

## See also

- [Classification](../../guides/classification/README.md).
- [Guards and redaction](../../guides/guards-and-redaction/README.md), redacting the spans.
- [gliner_classifier](./gliner_classifier.md).
- [regex](./regex.md), [composite_classifier](./composite_classifier.md).
