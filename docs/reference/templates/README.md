# Templates

> The `templates:` workspace section, every place a template can be referenced, the Jinja dialect and the variables and filters available when one renders.

## Overview

Every string a pipeline turns into model input is a Jinja template rendered by jinja4j (`io.gravitee.jinja4j:jinja4j`, version `jinja4j.version` in the root `pom.xml`), a Java port of the minijinja/Jinja2 dialect with auto-escaping turned off. Two renderers share one `Environment`: `JinjaRenderer` for step-level strings (message contents, raw templates, `system`, guard messages, `loopback_message`, tool-select label templates) and `Jinja4jChatTemplateRenderer` for chat templates (the model's own GGUF template or a step `chat_template` override). Tool-extraction templates use the same environment through `ToolCallExtractor`. Compiled templates are cached by source string.

The `templates:` section names reusable Jinja sources once. A template is referenced by id from `infer.prompt.template_id`, `llm_guard.prompt.template_id` and `infer.chat_template`; the loader substitutes the content before the pipeline reaches the proto, so the engine never sees an id.

## Key types

- `TemplateDefinition` (`WorkspaceDefinition`): `id` plus `content` or `file`.
- `YamlWorkspaceLoader.buildTemplateRegistry` / `resolveTemplate`: registry construction and `template_id > template_file > template` resolution.
- `JinjaRenderer`, `Jinja4jChatTemplateRenderer`, `JinjaContextHelper`, `PromptAssembler`: rendering and context building.
- `ToolCallExtractor`: tool-extraction templates; built-ins ship under `gravitee-singularitee-engine/src/main/resources/tool-extraction/`.

## Usage

Declare a template, then reference it (`examples/modular/templates/tool-system.yaml` and `examples/modular/pipelines/tool-calling.yaml`, pulled in by `examples/modular/server-llamacpp.yaml`):

```yaml
# server-llamacpp.yaml
workspace:
  name: server-llamacpp
  includes:
    models:    [ llama/llm-qwen3-0.6b.yaml ]   # from ./models/
    templates: [ tool-system.yaml ]            # from ./templates/
    pipelines: [ infer.yaml, tool-calling.yaml, cot.yaml, reasoning.yaml ]
```

```yaml
# templates/tool-system.yaml
workspace:
  templates:
    - id: tool-system
      content: |-
        {%- set ns = namespace(has_system=false) -%}
        {%- for message in messages -%}
          {%- if message.role == "system" -%}{%- set ns.has_system = true -%}{%- endif -%}
        {%- endfor -%}
        {%- if not ns.has_system -%}
        <|im_start|>system
        You are a helpful assistant.
        {%- if tools %}
        You have access to the following tools:
        <tools>
        {% for tool in tools %}
        {{ tool | tojson }}
        {% endfor %}
        </tools>
        When you need to call a tool, output ONLY a JSON object wrapped in <tool_call></tool_call> tags.
        {%- endif %}
        <|im_end|>
        {%- endif -%}
        {%- for message in messages %}
        <|im_start|>{{ message.role }}
        {{ message.content }}<|im_end|>
        {%- endfor %}
        <|im_start|>assistant
```

```yaml
# pipelines/tool-calling.yaml
workspace:
  pipelines:
    - id: tool-calling
      entry: agent
      steps:
        - id: agent
          type: infer
          role: output
          config:
            model_id: llm
            output_field: agent.output
            tags:
              tool_open: "<tool_call>"
              tool_close: "</tool_call>"
            prompt:
              template_id: tool-system      # raw template: bypasses the model's chat template
```

The same file shipped as a `.jinja` and used as a chat-template override instead (`examples/modular/templates/glm-4-9b-compact.jinja`):

```yaml
workspace:
  templates:
    - id: glm-compact
      file: glm-4-9b-compact.jinja          # relative to the workspace file, then ${gravitee.home}/templates
  pipelines:
    - id: chat
      entry: agent
      steps:
        - id: agent
          type: infer
          role: output
          config:
            model_id: llm
            chat_template: glm-compact     # the conversation is rendered with this template
            tool_extraction_template: glm-name-json
```

## Options

### `templates:` entries

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | Reference name. An entry without id is skipped with a warning; duplicate ids overwrite earlier ones. |
| `content` | string | unset | Inline Jinja source. Mutually exclusive with `file` (both set fails the load). |
| `file` | string | unset | Path to the source. Relative paths resolve against the workspace file's directory when the file exists there, otherwise against `${gravitee.home}/templates`; a relative path may not escape its base. Absolute paths are read as given and logged. |

`includes.templates:` lists files (globs allowed, expanded alphabetically) under the workspace's `templates/` folder; only their `workspace.templates` lists are merged. Templates from includes are registered before any pipeline is parsed, so a pipeline file may reference a template declared in another file.

### Where templates are referenced

| Reference | Rendered by | Renders | Notes |
| --- | --- | --- | --- |
| `infer.prompt.template_id` / `template_file` / `template` | `JinjaRenderer` | A bare prompt string sent as-is | Bypasses the chat template: the template owns every special token. `template_id` wins, then `template_file`, then `template`; two set at once fail the load. `template_file` resolves against `${gravitee.home}/templates` on a running server. |
| `infer.prompt.messages[].content` | `JinjaRenderer` | One message body | The resolved messages still go through the chat template. |
| `infer.system` | `JinjaRenderer` | A system turn merged with the caller's | Rendered per request, so `{{ todos }}` and step outputs are live. |
| `infer.chat_template` | `Jinja4jChatTemplateRenderer` | The full prompt from `messages` | A `templates:` id or inline source; replaces the model's GGUF template for that step. |
| `infer.tool_extraction_template` | `ToolCallExtractor` | A JSON array of calls from `{{ output }}` and `{{ tools }}` | Built-in names: `chatml-json`, `xml-function`, `gemma-call`, `glm-name-json`, `harmony`. Unset tries the first three in order. Any other value is inline source, not a `templates:` id. |
| `llm_guard.prompt.*` | `JinjaRenderer` | Screening prompt or messages | Same resolution as `infer.prompt`. |
| `guard.message`, `llm_guard.message`, `regex_guard.message` | `JinjaRenderer` | The failure message on `reject` | Sees the guard's own fields (`{{ <id>.label }}`, `{{ <id>.verdict }}`). |
| `loop.loopback_message.content` | `JinjaRenderer` | A corrective turn | Render failure skips the injection. |
| `tool_select.label_template`, `description_template` | `JinjaRenderer` | One label or description per tool | Context is only `{{ tool.name }}` and `{{ tool.description }}`. |
| `workspace.tags[]` | none | Marker strings, not templates | Listed here because it is the other workspace-level registry steps reference by id. |

### Two prompt paths for `infer`

| Path | When | What the engine receives |
| --- | --- | --- |
| Chat template | No `prompt.template*` set | `PromptAssembler` resolves messages (step `prompt.messages`, else the caller's conversation, else the bare prompt), prepends request `instructions`, merges `system`, trims to the context window, escapes the model's special tokens inside message text and tool definitions, then renders the chat template with the variables below. |
| Raw template | `prompt.template*` set | The rendered string, verbatim. No trimming, no escaping, no chat template; `messages`, `tools`, `bos_token` are still available as variables. |

When the engine exposes no chat template (remote metadata not yet fetched) the structured messages are forwarded for engine-side rendering with the step `context` as `template_context`.

### Variables

Base context (`JinjaContextHelper.buildBaseContext`), available to every step template:

| Variable | Shape |
| --- | --- |
| `prompt` | Last user message or bare prompt. |
| `system` | First system turn's content, or empty. |
| `history` | `role: content` lines. |
| `messages` | `[{role, content, tool_calls?, tool_call_id?, name?}]`; tool-call arguments are parsed maps. |
| `generated_messages` | `[{role: assistant, content, step}]`, thinking removed. |
| `verdicts` | `[{verdict, details, step}]`. |
| `tool_names` | `[string]`. |
| `todos` | `[{id, title, status, proof}]`. |
| `constraints` | string. |
| `<step_id>` | Map of that step's context fields. |
| request `context` entries | Strings, nested on the first dot. |

Added for `infer` (`PromptAssembler.buildJinjaContext`) and `llm_guard`:

| Variable | Shape |
| --- | --- |
| `add_generation_prompt` | `true`. |
| `bos_token`, `eos_token` | The engine's tokens, or empty strings. |
| `tools` | `[{type: function, function: {name, description, parameters}}]`; absent with `inject_tools: false`; filtered by `tool_select`; server tools appended unless `server_tools: false`. |
| step `context:` entries | Typed values (bool, number, string, list, map), overlaid last. |
| `reasoning_effort` | Request value, else the step `context` value. |

Tool-extraction templates receive only `output` (the captured span or the step text) and `tools` (`[{name, description}]`).

### Filters, tests and globals

Filters shipped by jinja4j: `abs`, `attr`, `batch`, `capitalize`, `center`, `default`, `dictsort`, `escape`, `filesizeformat`, `first`, `float`, `format`, `groupby`, `indent`, `int`, `items`, `join`, `keys`, `last`, `length`, `list`, `lower`, `map`, `max`, `min`, `regex_findall`, `regex_first`, `reject`, `rejectattr`, `replace`, `reverse`, `round`, `safe`, `select`, `selectattr`, `sort`, `split`, `string`, `striptags`, `sum`, `title`, `tojson`, `trim`, `truncate`, `unique`, `upper`, `urlencode`, `values`, `wordcount`, `xmlattr`.

`regex_findall(pattern)` returns the list of matches (tuples of groups when the pattern has several), `regex_first(pattern, group?)` the first match or `none`; both take Java regex syntax and are what the built-in extraction templates are written with. `tojson` serialises maps and lists, the usual way to print a tool schema.

Globals: `namespace`, `range`, `dict`, `cycler`, `joiner`, `lipsum`, `raise_exception`, `strftime_now`. Standard Jinja2 tests (`defined`, `none`, `true`, `false`, `string`, ...) and control structures (`if`, `for` with `loop.last`, `set`, `macro`) are supported; `{%- -%}` whitespace control works as in Jinja2.

### System prompt precedence (chat-template path)

1. Request `instructions` (from the request context) as a leading system turn, render time only.
2. The caller's system message, as sent.
3. The step `system` template: appended to the caller's system turn after a blank line, or inserted as the first turn when the caller sent none.
4. A `prompt.messages` override replaces the conversation entirely, including any caller system turn; its own `role: system` entries are what the model sees.

There is no persona layer: `WorkspaceDefinition` has no `persona` key and the loader adds no implicit turns.

## Notes

- Step ids must be Jinja identifiers (`[A-Za-z_][A-Za-z0-9_]*`); the loader rejects hyphens so `{{ my-step.output }}` can never be written by accident.
- YAML booleans in `context:` must be unquoted: chat templates test `enable_thinking is false`, and the string `"false"` passes neither test. The loader logs a warning when it sees a quoted boolean.
- Raw templates receive the caller's text unescaped. A chat template receives message text with the model's special tokens neutralised (`<|im_start|>` becomes `<\|im_start|>`), so a user cannot forge a turn; the same escaping is applied to tool names, descriptions and arguments.
- `chat_template` and `tool_extraction_template` resolve differently: the first looks a `templates:` id up and otherwise treats the value as inline source; the second knows only the five built-in names and otherwise treats the value as inline source. A `templates:` id passed to `tool_extraction_template` is compiled as a literal template.
- `template_file` on a step and `file` on a template entry do not share a base: the step path resolves against `${gravitee.home}/templates` on a running server, the entry path prefers the workspace directory. Prefer `templates:` entries with `file` and reference them by id.
- `--debug` logs every rendered prompt at TRACE together with a dump of the rendering context (`JinjaContextHelper.dump`), long values truncated.

## See also

- [`infer`](../steps/infer.md), [`llm_guard`](../steps/llm_guard.md), [`loop`](../steps/loop.md), [`tool_select`](../steps/tool_select.md)
- [Context fields](../context-fields.md)
- [Workspaces](../../workspaces/README.md)
- [Text generation guide](../../guides/text-generation/README.md)
