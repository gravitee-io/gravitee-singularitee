# `regex_guard`

> Model-free pattern guard: reject, warn or redact on a list of named Java regular expressions.

## Overview

`RegexGuardStepExecutor` applies the `patterns` list to `input_field`. For `reject` and `warn` the patterns are combined into one alternation with positional named groups, so the first matching entry's `name` is known without the author writing group syntax. For `redact` every pattern is scanned for spans, overlapping or adjacent spans are merged, and each span is replaced with `[NAME]` or `[REDACTED]`; `messages` and `prompt` are rewritten so downstream templates see the redacted text. The executor is deprecated: declare a `regex` model (or a `composite_classifier`) and reference it from a [`guard`](./guard.md) step, which offers the same matching with the common trigger and redaction semantics.

## Usage

```yaml
- id: secrets
  type: regex_guard
  next_step: generate
  config:
    input_field: prompt
    action: redact
    redact_with_entity_type: true
    output_field: secrets.output
    patterns:
      - name: CREDIT CARD
        pattern: "\\b(?:\\d[ -]?){13,16}\\b"
      - name: AWS KEY
        pattern: "AKIA[0-9A-Z]{16}"
    message: "Blocked: {{ secrets.entity_type }} detected."
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `input_field` | string | `prompt` | Context key to scan. An empty value skips the step. |
| `patterns` | list of `{name, pattern}` | required | Named Java regexes. An empty list skips the step. |
| `patterns[].name` | string | required | Free-form label (spaces and punctuation allowed); used as `[NAME]` replacement and in the `entity_type*` fields. |
| `patterns[].pattern` | string | required | Java `Pattern` source. Blank patterns are ignored in `redact` mode. |
| `action` | string | `reject` | `reject`, `warn` or `redact`. Unknown values fall back to `reject`. |
| `redact_with_entity_type` | bool | `false` | `redact` only: replace spans with `[NAME]` (upper-cased) instead of `[REDACTED]`. |
| `output_field` | string | `<id>.output` | `redact` only: context key receiving the redacted text (the original text when nothing matched). |
| `message` | string | unset | Jinja template rendered on `reject` and returned as the failure message. |

## Context fields

Reads: `input_field` (default `prompt`), `messages` (for redaction).

Writes, `reject` / `warn` on match:

| Field | Value |
| --- | --- |
| `<id>.triggered` | `true` |
| `<id>.match` | The matched substring of the first matching entry. |
| `<id>.pattern` | That entry's pattern source. |
| `<id>.entity_type` | That entry's `name`. |
| `__guard_triggered` | `<id>` (`warn` only). |

Writes, `redact` on match:

| Field | Value |
| --- | --- |
| `<id>.triggered` | `true` |
| `<id>.entity_types` | Comma-separated distinct names of the merged spans. |
| `<output_field>` | The redacted text; `prompt` and the matching user turn in `messages` are rewritten too. |

## Notes

- Patterns are compiled with default flags; add inline flags (`(?i)`) inside the pattern when needed.
- In `reject` / `warn` mode only the first match is reported, in entry order for matches at the same position.
- Compiled patterns are cached per executor: the combined alternation by its pattern list, single patterns by source.
- The `name` rendering in `redact` mode upper-cases the label: `name: email` becomes `[EMAIL]`.

## See also

- [Guards and redaction guide](../../guides/guards-and-redaction/README.md)
- [`guard`](./guard.md) with a `regex` model, the recommended replacement
- [Context fields](../context-fields.md)
