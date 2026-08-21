# regex

> A pure-Java token classifier: a list of regular expressions, each tagged with an entity type, matched in-process with no engine, no GPU and no download.

## Overview

`regex` builds a `RegexClassifierEngine` reporting `token-classification`. Every match
becomes a `ClassifyResult` with the entity type as label, score `1.0`, the matched text and
its character offsets, which is exactly what `guard` redaction needs. It runs wherever the
workspace is loaded, server or client, and is the deterministic half of a PII detector next
to a learned `gliner_ner` model. `ClientLocalModelRegistrar` registers it.

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
          - pattern: '\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b'
            entity_type: IPV4_ADDRESS
  pipelines:
    - id: redact
      entry: guard
      steps:
        - id: guard
          type: guard
          next_step: generate
          config:
            model_id: pii-regex-contact
            action: REDACT
            redact_with_entity_type: true
```

## Options

`regex:` block (`WorkspaceDefinition.RegexDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `patterns` | list | unset | Pattern entries, below. An empty list gives an engine that never matches. |
| `patterns[].pattern` | string | unset | A `java.util.regex` pattern. Blank entries are dropped. |
| `patterns[].entity_type` | string | `MATCH` | Label attached to matches of that pattern; surfaces as `[EMAIL]`-style replacement text under `redact_with_entity_type`. |

`name`, `memory_check` and `download` are accepted and ignored; `task`, `visible` and
`modalities` apply as on any entry.

## Notes

- **One combined alternation.** Patterns are compiled into a single regex with positional
  named groups (`(?<P0>...)|(?<P1>...)`); earlier patterns win ties at the same position. Do
  not use named groups of the form `P<n>` inside your own patterns.
- **Scores are binary.** Every match is `1.0`; `topLabel` is the first match in the text,
  not the strongest. Thresholds on a regex-backed guard act as on/off.
- **Large inputs are chunked** at about 4096 estimated tokens on semantic boundaries to
  bound backtracking; chunks do not overlap, so a match straddling a boundary can be missed.
  Inputs within budget are matched in one pass.
- **No match is an empty response** (`topLabel` empty, no scores, no results); the `guard`
  step reads that as not triggered.
- **Unknown type vs. bad pattern.** A pattern that fails to compile fails the engine at
  registration, which logs a WARN and skips the model; the workspace still loads.
- **Quote carefully in YAML.** Single quotes keep backslashes literal; double quotes need
  `\\b`.

## See also

- [Guards and redaction](../../guides/guards-and-redaction/README.md), including the `regex_guard` step, which embeds patterns directly in a step instead of a model.
- [Classification](../../guides/classification/README.md).
- [composite_classifier](./composite_classifier.md), combining regex with learned classifiers.
- [gliner_ner](./gliner_ner.md).
