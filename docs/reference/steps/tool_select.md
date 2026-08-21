# `tool_select`

> Shortlists the request's tools with a zero-shot classifier so the following `infer` step injects only the relevant schemas.

## Overview

`ToolSelectStepExecutor` binds to a `ClassifierEngine` that accepts per-request labels (a `gliner_classifier`). It classifies the last user message against the request's tools in batches of `batch_size`, each batch extended with a synthetic `none_of_these` label. A tool is selected when its score is at least `threshold` and above the `none_of_these` score. The union over batches becomes the shortlist written to `__selected_tools`; `always_include` names are added only to a non-empty shortlist, so a conversational turn with no selected tool injects nothing. A failing classify call fails open and includes that batch's tools. With `trim_descriptions`, the selected tools' descriptions are condensed and the `infer` step injects the condensed form (patched into each tool's template JSON as well).

## Usage

From `examples/pipelines/tool-router.yaml`:

```yaml
- id: select_tools
  type: tool_select
  next_step: agent
  config:
    model_id: tool-router       # gliner_classifier declared without labels
    batch_size: 4
    threshold: 0.3
    trim_descriptions: true
    # always_include: [ "read_file" ]

- id: agent
  type: infer
  role: output
  config:
    model_id: llm
    output_field: agent.output
    tags:
      tool_open: "<tool_call>"
      tool_close: "</tool_call>"
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a zero-shot classifier model. |
| `input_field` | string | unset | Context key to classify. Unset: the last user message, falling back to `prompt`. The text is capped at 1500 characters. |
| `batch_size` | int | `4` | Tools per classify call (plus `none_of_these`). |
| `threshold` | float | `0.3` | Minimum score for a tool to be selected. |
| `label_template` | string | unset | Jinja template rendered per tool with `{{ tool.name }}` / `{{ tool.description }}` to build the classifier label description. Unset: first sentence of the description, capped at 160 characters. A render failure falls back to the default. |
| `always_include` | list of string | unset | Tool names unioned into a non-empty shortlist (only names present on the request). |
| `trim_descriptions` | bool | `false` | Write condensed descriptions for the selected tools so `infer` injects the short form. |
| `description_template` | string | unset | Jinja template (same `tool` variable) for the injected condensed description. Unset: the default condenser. |

## Context fields

Reads: the request tools, `input_field` or the last user turn of `messages`, then `prompt`.

Writes:

| Field | Value |
| --- | --- |
| `__selected_tools` | Comma-joined selected tool names (empty string when none). Hidden from Jinja; read by `infer` through `PipelineContext.selectedTools()`. |
| `__condensed_tool_descriptions` | `name=description` pairs joined by `;` when `trim_descriptions` is set. |

A request without tools, or an empty input text, leaves both unset and the `infer` step injects every tool.

## Notes

- The shortlist filters caller tools only; server-owned todo tools are never shortlisted or condensed.
- The order of the shortlist follows batch order, then `always_include` order.
- Label descriptions are what the classifier scores against, so a `label_template` that keeps the verb and object of each tool ("Read a file", "Send an email") matters more than length.
- The `gliner_classifier` model should be declared without `labels`; the step supplies them per request.

## See also

- [Tool calling guide](../../guides/tool-calling/README.md)
- [Classification guide](../../guides/classification/README.md)
- [`infer`](./infer.md) (`inject_tools`, `tool_extraction_template`)
