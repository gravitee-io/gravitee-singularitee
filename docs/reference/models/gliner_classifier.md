# gliner_classifier

> Zero-shot sequence classification with GLiNER: the label set and its descriptions come from the workspace, not from training.

## Overview

`gliner_classifier` loads a GLiNER model directory with gliner4j (ONNX Runtime underneath)
and serves it as a `ClassifierEngine` reporting `text-classification`. Labels are declared in
YAML as names with optional descriptions; the description biases the label embedding, so a
precise sentence routes better than a bare word. The same model file serves any label set,
which makes it the usual choice for intent routing. `GlinerClassifierFactory` builds the
engine; `GlinerModelResolver` downloads the directory.

## Usage

`examples/classifier/intent-gliner.yaml` (abbreviated):

```yaml
workspace:
  name: intent-gliner
  models:
    - id: router
      name: gravitee-io/gliner4j-gliner2-base-v1
      type: gliner_classifier
      memory_check: disabled
      gliner_classifier:
        variant: onnx_fp16
        threshold: 0.3
        labels:
          - name: software development question
            description: >
              User asks about writing, fixing, or understanding code; debugging; algorithms;
              APIs; programming languages, tools, or frameworks.
          - name: cooking and food recipe
            description: >
              User asks how to prepare food or drinks; requests recipes or kitchen techniques.
          - name: general conversation
            description: >
              Casual interaction; small talk, opinions, jokes, or queries without a clear intent.
```

## Options

`gliner_classifier:` block (`WorkspaceDefinition.GlinerClassifierDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_dir` | string | resolved from `name` | Root directory holding `gliner_config.json`, the tokenizer files and the `onnx*/` variant folders. Set only for a local directory. |
| `labels` | list of `{name, description}` | unset | The zero-shot schema. `description` is optional and recommended. |
| `threshold` | float | `0` (no filtering) | Minimum score for a label to be reported. |
| `variant` | string | `onnx` | Variant sub-folder: `onnx`, `onnx_fp16`, `onnx_quantized`. |
| `token_cap` | int | `512` | Encoder window including the label prompt; longer inputs are chunked to fit. |

## Notes

- **Per-request labels.** `ClassifyRequest.labels` (and the HTTP `labels` array) replace the
  configured schema for that request. GLiNER is the only family that honours this.
- **Chunking budget.** Each chunk gets `token_cap` minus an estimate of the label prompt
  (about 3.5 characters per token, since gliner4j exposes no tokenizer); scores roll up as the
  maximum per label. For a chunked input, `results` holds one row per (label, chunk) whose
  span locates the chunk, not an entity.
- **What gets downloaded.** `GlinerModelResolver` fetches the repository's root files
  (tokenizer, `gliner_config.json`, ...) and only the `variant` sub-folder; other variants
  are skipped. `download.exclude` narrows that further. The directory lands in
  `<cache>/<org>/<model>/`.
- **Runtime knobs are environment variables**, not YAML: `GRAVITEE_GLINER_ENCODER_INTRA_OP_THREADS`,
  `GRAVITEE_GLINER_ENCODER_INTER_OP_THREADS`, `GRAVITEE_GLINER_SCORING_INTRA_OP_THREADS`,
  `GRAVITEE_GLINER_SCORING_INTER_OP_THREADS` (thread pools, gliner4j defaults sized for CPU),
  `GRAVITEE_GLINER_EXECUTION_PROVIDER` (`cuda`, `cpu`; default auto-detect),
  `GRAVITEE_GLINER_ALLOW_SPINNING=0` (stop busy-waiting when compute is on the GPU),
  `GRAVITEE_GLINER_ORT_PROFILING_DIR` / `_SECONDS` (diagnostic traces only).
- **Memory.** Hundreds of MB; `memory_check: disabled` in every example.

## See also

- [Classification](../../guides/classification/README.md).
- [Routing](../../guides/routing/README.md), label routing on the top label.
- [gliner_ner](./gliner_ner.md), the span-level sibling.
- [onnx_classifier](./onnx_classifier.md), fine-tuned fixed-label classifiers.
