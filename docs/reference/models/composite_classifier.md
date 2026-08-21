# composite_classifier

> A pure-Java union of other classifier models: one id, every member's verdict and spans merged into a single response.

## Overview

`composite_classifier` builds a `CompositeClassifierEngine` over the ids listed in `models`.
A request fans out to each member in declaration order and the responses merge: `results` is
the union of all spans, `allScores` keeps the maximum per label, `topLabel` is the first
member's non-empty top label, `topScore` the maximum. The composite reports
`token-classification` when any member does, otherwise `text-classification`. It lets one
`guard` step collect regex hits, NER entities and a toxicity verdict in a single pass.
`ClientLocalModelRegistrar` registers it after the members.

## Usage

```yaml
workspace:
  name: pii
  models:
    - id: pii-regex-contact
      type: regex
      regex:
        patterns:
          - pattern: '(?U)\b[\p{L}\p{Nd}._%+-]+@[\p{L}\p{Nd}.-]+\.\p{L}{2,}\b'
            entity_type: EMAIL
    - id: pii-ner
      name: gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi
      type: gliner_ner
      memory_check: disabled
      gliner_ner:
        variant: onnx
        threshold: 0.4
        entities:
          - name: person
            description: Full name of a real individual
    - id: pii-detector
      type: composite_classifier
      composite_classifier:
        models: [pii-regex-contact, pii-ner]
```

## Options

`composite_classifier:` block (`WorkspaceDefinition.CompositeClassifierDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `models` | list of string | unset | Ids of classifier models in the same workspace, invoked in this order. Empty or missing skips the composite with a WARN. |

Members may be `regex`, another `composite_classifier`, `onnx_classifier`,
`gliner_classifier`, `gliner_ner` or `remote_classifier`: anything registered as a
`ClassifierEngine`. `name`, `memory_check` and `download` are ignored; `task`, `visible` and
`modalities` apply as on any entry.

## Notes

- **Order of registration.** Local engine models load first, then remote proxies, then
  `regex` models, then composites; a composite can therefore reference any of them, and a
  composite declared earlier in the list. A member id that is not registered, or that is not
  a classifier, skips the composite with a WARN naming the member.
- **One split for everyone.** A huge input is chunked once at about 4096 estimated tokens
  and each member sees the shared chunks with offsets shifted back afterwards; members that
  split finer (ONNX, GLiNER) still do so within a chunk.
- **Errors propagate.** If any member fails, the composite call fails.
- **Empty response when nothing matches**, same convention as `regex`: the `guard` step
  treats it as not triggered.
- **Hide the parts.** Set `visible: false` on the members and publish only the composite id.

## See also

- [Classification](../../guides/classification/README.md), merge semantics in detail.
- [Guards and redaction](../../guides/guards-and-redaction/README.md).
- [regex](./regex.md), [gliner_ner](./gliner_ner.md), [onnx_classifier](./onnx_classifier.md), [remote_classifier](./remote_classifier.md).
