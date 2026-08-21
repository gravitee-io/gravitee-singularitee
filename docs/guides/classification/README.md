# Classification

> How to classify text with a fine-tuned ONNX model, a GLiNER zero-shot schema, a regex model or a composite of them, over `POST /v1/classify`, the `Classify` RPC, or a `classify` pipeline step.

## Overview

Any model whose engine implements `ClassifierEngine` answers a classify call with the same shape: a top label and score, a per-label score map, and detailed results that carry character spans for token-level engines. Pick the model type by what you need:

| Model type | Use it when | Task |
| --- | --- | --- |
| `onnx_classifier` | You have a fine-tuned BERT-family model. `classifier_mode: SEQUENCE` labels the whole input; `TOKEN` labels spans (NER). | `text-classification` or `token-classification` |
| `gliner_classifier` | The label set is yours to define in YAML and may change without retraining. | `text-classification` |
| `gliner_ner` | Same, for entity extraction with spans. | `token-classification` |
| `regex` | Deterministic patterns (emails, IBANs, keys). Pure Java, runs anywhere. | `token-classification` |
| `composite_classifier` | One id that fans out to several of the above and merges their verdicts. | from its delegates |

Oversized input is split on semantic boundaries and recombined, never truncated.

## Key types

- `ClassifierEngine` (engine): `rxClassify(request)`, `rxClassify(request, labels)` for per-request GLiNER schemas, `rxClassifyBatch`, `rxClassifyPresplit`.
- `OnnxClassifierEngine`, `GlinerClassifierEngine` / `GlinerNerEngine`, `RegexClassifierEngine`, `CompositeClassifierEngine`.
- `ClassifyStepExecutor`: the `classify` pipeline step.
- `ClassifyHandler` (http): `POST /v1/classify`.
- Protos: `ClassifyRequest`, `ClassifyBatchRequest`, `ClassifyResponse`, `ClassifyResult`, `ClassifyLabel` (`inference.proto`).

## Usage

### Start a server

```bash
./run-server.sh --workspace examples/classifier/toxicity-bert.yaml     # sequence, model id: toxicity
./run-server.sh --workspace examples/classifier/pii-bert.yaml          # token (NER), model id: pii
./run-server.sh --workspace examples/classifier/intent-gliner.yaml     # zero-shot, model id: router
./run-server.sh --workspace examples/classifier/pii-gliner.yaml        # zero-shot NER, model id: pii
./run-server.sh --workspace examples/classifier/guardrails-gliner.yaml # zero-shot safety, model id: gliguard
```

### Call it

HTTP (`GRAVITEE_HTTP_ENABLED=true`). `input` is a string or an array; `labels` is optional and only GLiNER engines honour it:

```bash
curl -s localhost:8080/v1/classify -H 'content-type: application/json' -d '{
  "model": "pii",
  "input": "Contact john.doe@acme.com or +1 415 555 0134",
  "labels": [
    {"name": "email", "description": "Email address"},
    {"name": "phone_number", "description": "Phone or mobile number"}
  ]
}' | jq
```

The response is `{"object":"classification","model":...,"results":[{"top_label","top_score","scores":{...},"spans":[{"label","score","token","start","end"}]}]}`; `spans` is present only for results that carry offsets.

gRPC:

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"model_id":"toxicity","text":"you are worthless"}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/Classify
```

Java:

```java
var client = new SingulariteeClient("localhost", 9090);
ClassifyResponse resp = client
  .classify(ClassifyRequest.newBuilder().setModelId("toxicity").setText("you are worthless").build())
  .blockingGet();
System.out.println(resp.getTopLabel() + " " + resp.getTopScore());
```

### Declare the models

Fine-tuned ONNX, sequence-level (`examples/classifier/toxicity-bert.yaml`):

```yaml
models:
  - id: toxicity
    name: gravitee-io/distilbert-multilingual-toxicity-classifier
    type: onnx_classifier
    memory_check: disabled
    onnx_classifier:
      model_path: model.quant.onnx
      tokenizer_path: tokenizer.json
      config_json_path: config.json
      labels: [non-toxic, toxic]
      max_sequence_length: 512
      classifier_mode: SEQUENCE
```

Switch to `classifier_mode: TOKEN` for a span-emitting NER model (`examples/classifier/pii-bert.yaml`).

Zero-shot GLiNER, labels defined in YAML (`examples/classifier/intent-gliner.yaml`):

```yaml
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
          description: User asks about writing, fixing, or understanding code.
        - name: cooking and food recipe
          description: User asks how to prepare food or drinks.
```

`gliner_ner` takes the same block with `entities:` instead of `labels:` (`examples/classifier/pii-gliner.yaml`).

Regex and composite, layered into one id:

```yaml
models:
  - id: pii-regex
    type: regex
    regex:
      patterns:
        - pattern: '(?U)\b[\p{L}\p{Nd}._%+-]+@[\p{L}\p{Nd}.-]+\.\p{L}{2,}\b'
          entity_type: EMAIL
        - pattern: '\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b'
          entity_type: IPV4_ADDRESS

  - id: pii-detector          # union of regex and NER verdicts
    type: composite_classifier
    composite_classifier:
      models: [pii-regex, pii]
```

### Use it in a pipeline

The `classify` step writes the top label to `output_field` and the score to `<output_field>.score`:

```yaml
steps:
  - id: classify_intent
    type: classify
    next_step: route_by_intent
    config:
      model_id: router
      input_field: prompt
      output_field: intent
```

To act on the verdict, use a [guard](../guards-and-redaction/README.md) (block or redact) or a [route](../routing/README.md) step (branch) instead; both call the classifier themselves.

## Options

Keys used in this guide. Full lists: [onnx_classifier](../../reference/models/onnx_classifier.md), [gliner_classifier](../../reference/models/gliner_classifier.md), [gliner_ner](../../reference/models/gliner_ner.md), [regex](../../reference/models/regex.md), [composite_classifier](../../reference/models/composite_classifier.md), [classify step](../../reference/steps/classify.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `onnx_classifier.classifier_mode` | string | `SEQUENCE` | `SEQUENCE` or `TOKEN`. |
| `onnx_classifier.labels` | list | from `config.json` | Ordered labels matching the logits. |
| `gliner_classifier.labels` / `gliner_ner.entities` | list of `{name, description}` | required | The zero-shot schema. |
| `gliner_*.threshold` | float | `0` | Minimum confidence. |
| `gliner_*.variant` | string | `onnx` | `onnx`, `onnx_fp16` or `onnx_quantized`. |
| `regex.patterns[]` | list of `{pattern, entity_type}` | required | Java regex and the label its matches carry. |
| `composite_classifier.models` | list | required | Delegate model ids, invoked in order. |
| `classify.model_id` | string | required | Classifier model id. |
| `classify.input_field` | string | `prompt` | Context key to read. |
| `classify.output_field` | string | `<step_id>.label` | Key for the top label; score goes to `<output_field>.score`. |

## Notes

- There is no `NER` mode: `classifier_mode: TOKEN` is NER. The engine reports `token-classification` and results carry `start` / `end`.
- Per-request `labels` replace the schema for GLiNER engines only. ONNX, regex and composite engines ignore them.
- Composite merge: `results` is the union of delegate spans, `scores` merges per label with the maximum, `top_label` is the first delegate's non-empty top label, `top_score` the maximum. The composite splits long input once and hands the chunks to its delegates.
- Regex matches all score `1.0` and `top_label` is the first match in the text. Thresholds on regex verdicts are binary.
- `regex` and `composite_classifier` are pure Java and run on a client workspace as well as a server.
- GLiNER sequence results for chunked input include one row per (label, chunk) whose span locates the chunk, not an entity. Only NER and regex spans are entity spans.
- `threshold` on the `classify` step is informational; it does not filter the output.

## See also

- [Guards and redaction](../guards-and-redaction/README.md): block or mask on a classifier verdict.
- [Routing](../routing/README.md): branch on a top label.
- [Embeddings and reranking](../embeddings-and-reranking/README.md): the other encoder families.
- [Pipelines](../../concepts/pipelines/README.md) and [Workspaces](../../workspaces/README.md).
- [HTTP API](../../api/http/README.md), [gRPC API](../../api/grpc/README.md), [Java client](../../api/java-client/README.md).
- [OpenAPI: classification](../../../openapi/classification.openapi.yaml).
