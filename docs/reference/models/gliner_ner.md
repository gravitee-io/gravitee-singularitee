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
| `model_dir` | string | resolved from `name` | Root directory holding `gliner_config.json`, tokenizer files and the `onnx*/` variant folders. |
| `entities` | list of `{name, description}` | unset | Entity types to extract. `description` is optional. |
| `threshold` | float | `0` (no filtering) | Minimum score for a span to be reported. |
| `variant` | string | `onnx` | `onnx`, `onnx_fp16`, `onnx_quantized`. |
| `token_cap` | int | `512` | Encoder window including the entity prompt; longer inputs are chunked. |

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
