# onnx_classifier

> A BERT-family classifier exported to ONNX, run by ONNX Runtime at sequence level (one label per input) or token level (labelled spans with character offsets).

## Overview

`onnx_classifier` loads an ONNX graph plus its HuggingFace tokenizer and serves it as a
`ClassifierEngine`. `classifier_mode: SEQUENCE` reports `text-classification` and a label
with a score for the whole input; `classifier_mode: TOKEN` reports `token-classification`
and one result per detected span, which is what `guard` redaction consumes. Inputs longer
than the model window are split on semantic boundaries and recombined, never truncated.
`OnnxClassifierFactory` builds the engine; `OnnxModelResolver` downloads the files.

## Usage

Token-level PII detection (`examples/classifier/pii-bert.yaml`):

```yaml
workspace:
  name: pii-bert
  models:
    - id: pii
      name: gravitee-io/bert-small-pii-detection
      type: onnx_classifier
      memory_check: disabled
      onnx_classifier:
        model_path: model.onnx
        tokenizer_path: tokenizer.json
        config_json_path: config.json
        max_sequence_length: 512
        classifier_mode: TOKEN
```

Sequence-level with an explicit label list (`examples/classifier/toxicity-bert.yaml`):

```yaml
    - id: toxicity
      name: gravitee-io/distilbert-multilingual-toxicity-classifier
      type: onnx_classifier
      memory_check: disabled
      onnx_classifier:
        model_path: model.quant.onnx
        tokenizer_path: tokenizer.json
        config_json_path: config.json
        labels:
          - non-toxic
          - toxic
        max_sequence_length: 512
        classifier_mode: SEQUENCE
```

## Options

`onnx_classifier:` block (`WorkspaceDefinition.OnnxClassifierDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_path` | string | unset | ONNX file inside the repository (`model.onnx`, `onnx/model.onnx`, a quantised variant). Required. |
| `tokenizer_path` | string | unset | Tokenizer file (`tokenizer.json`) or directory prefix. Required; a blank value fails the load. |
| `config_json_path` | string | unset | `config.json` providing `id2label`. Optional when `labels` is set. |
| `labels` | list of string | from `config.json` | Ordered labels matching the output logits. |
| `max_sequence_length` | int | library default | Token cap per input; longer inputs are split and recombined. |
| `classifier_mode` | string | `SEQUENCE` | `SEQUENCE` or `TOKEN`. Unknown values fall back to `SEQUENCE`. |

## Notes

- **`TOKEN` mode is NER.** There is no separate NER value; token mode returns `start`/`end`
  character spans and the engine's task becomes `token-classification`.
- **What gets downloaded.** `OnnxModelResolver` fetches `model_path` together with every
  sibling in the same repository directory (an `onnx/` folder often carries external data
  files), `config_json_path`, and the tokenizer: a single file, every file under a directory
  prefix, or the well-known tokenizer files at the repository root (`tokenizer.json`,
  `tokenizer_config.json`, `special_tokens_map.json`, `vocab.txt`). `download.exclude`
  narrows the sibling and tokenizer listings; the named files themselves are always fetched.
  A `.complete-tokenizer` marker is written once the tokenizer files are all on disk, and only
  that marker makes the next start reuse the cached tokenizer directory.
- **Split, not truncated.** Sequence classifiers classify each chunk and keep the maximum
  score per label; token classifiers use a sliding window and shift spans back to the
  original text.
- **Per-request label overrides are ignored.** `ClassifyRequest.labels` only applies to
  GLiNER models; ONNX classifiers use their configured label set.
- **Memory.** These models are small (tens to a few hundred MB); `memory_check: disabled` is
  the norm. Quantised exports (`model.quant.onnx`) are faster on CPU.
- **Platforms.** CPU everywhere by default. The `-Pcuda` build swaps ONNX Runtime for the GPU
  artifact, which then fails on macOS; rebuild without it for local work.

## See also

- [Classification](../../guides/classification/README.md), the `classify` step and the HTTP endpoint.
- [Guards and redaction](../../guides/guards-and-redaction/README.md), span-based redaction on `TOKEN` output.
- [gliner_classifier](./gliner_classifier.md), [gliner_ner](./gliner_ner.md), zero-shot alternatives.
- [regex](./regex.md), [composite_classifier](./composite_classifier.md), pure-Java companions.
