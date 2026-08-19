# Workspaces

> Declare models, pipelines, templates, and remote endpoints in a single YAML — the unit of deployment the server loads at startup.

## Overview
A workspace is a YAML document with one root key, `workspace:`, that declares everything a server
(or a client-side executor) publishes: the models to load, the pipelines that orchestrate them,
reusable prompt templates, and optional remote gRPC endpoints. Models are bound to a stable
**logical id** (`llm`, `pii`, `toxicity`, `router`, ...) while `name` points at the actual source —
usually a HuggingFace repo — so the same pipeline runs unchanged against any backend a given
workspace binds to that id. Larger setups compose a workspace from shared fragments via
`includes:`, resolved from conventional `models/`, `pipelines/`, and `templates/` sibling folders.

## Key types
- `WorkspaceDefinition` / `WorkspaceRoot` — Jackson mapping of the document: `name`, `remote`, `models`, `pipelines`, `templates`, `includes` (unknown keys are ignored).
- `YamlWorkspaceLoader` — parses the YAML, resolves includes and globs, builds the template registry, and validates step ids.
- `ModelType` — enum of every `type:` string (parsed case-insensitively) and the builder that turns each config block into a model-load request.
- `ModelDefinition` — one `models:` entry: `id`, `name`, `type`, optional `server`, `memory_check`, `download`, `task`, `visible`, plus exactly one type-specific block.
- `RemoteConfig` / `RemoteEndpoint` — the `remote:` block: a `default` endpoint and/or named `servers` (`id`, `host`, `port`, optional `username`/`password`).
- `TemplateDefinition` — a template entry: `id` plus `content` (inline Jinja2) *or* `file` (mutually exclusive).
- `MemoryCheckPolicyType` — per-model GPU/RAM pre-load check: `fail`, `warn` (default), `disabled`.

## Usage

A minimal server workspace (from `examples/modular/models/llama/llm-qwen3-0.6b.yaml` + `examples/modular/pipelines/infer.yaml`):

```yaml
workspace:
  name: server-llamacpp
  models:
    - id: llm                          # logical id — pipelines reference this
      name: Qwen/Qwen3-0.6B-GGUF       # HF repo (downloaded on first load)
      type: llama_cpp
      memory_check: warn
      llama_cpp:
        path: Qwen3-0.6B-Q8_0.gguf
        n_seq_max: 4
        n_gpu_layers: 999
        flash_attn_type: AUTO
  pipelines:
    - id: infer-pipeline
      name: Inference Pipeline
      entry: infer
      steps:
        - id: infer
          type: infer
          role: output
          config:
            model_id: llm
            output_field: infer.output
            sampling:
              max_tokens: 512
```

Composing from shared fragments (`examples/modular/server-llamacpp.yaml`):

```yaml
workspace:
  name: server-llamacpp
  includes:
    models:
      - llama/llm-qwen3-0.6b.yaml      # resolved in ./models/ — subfolders are fine
    templates:
      - tool-system.yaml               # resolved in ./templates/
    pipelines:                         # resolved in ./pipelines/ — globs allowed
      - infer.yaml
      - tool-calling.yaml
      - cot.yaml
      - reasoning.yaml
```

A client workspace with remote models (see `examples/modular/client-safety-llamacpp.yaml`):

```yaml
workspace:
  name: client
  remote:
    servers:
      - id: pii
        host: 127.0.0.1
        port: 9100
        username: pii
        password: <password>
  models:
    - id: pii-detector
      type: remote_classifier
      server: pii                      # binds to the endpoint id above
```

(`remote.default:` is the single-endpoint shorthand — models with no `server:` use it.)

A client-local composite (production PII, abbreviated):

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

### Top-level `workspace:` fields
| Field | Default | Purpose |
| --- | --- | --- |
| `name` | — | Workspace name (informational). |
| `remote` | — | `default:` endpoint and/or `servers:` list of named gRPC endpoints. |
| `models` | `[]` | Model definitions (see types below). |
| `pipelines` | `[]` | Pipeline DAGs (`id`, `name`, `entry`, `steps`, optional `server` for remote proxy pipelines). |
| `templates` | `[]` | Jinja2 templates: `id` + `content` (inline) or `file` (path), never both. |
| `tags` | `[]` | Named tag sets: `id` + the step `tags:` keys (`reasoning_open/close`, `tool_open/close`, `reasoning_repeatable`). An infer step references one by writing the id as its whole `tags:` value; unknown ids fail loading. Declared in the base file only (not merged from includes). |
| `includes` | — | `models:` / `pipelines:` / `templates:` lists of filenames or globs, resolved in sibling folders of the same name. |

### Model `type:` values (`ModelType`)
| `type:` | Config block | Category |
| --- | --- | --- |
| `llama_cpp` | `llama_cpp` | local GGUF text generation (llamaj.cpp) |
| `vllm` | `vllm` | local HF Transformers via vLLM |
| `onnx_classifier` | `onnx_classifier` | local ONNX sequence/token classifier |
| `onnx_embedding` | `onnx_embedding` | local ONNX embedding model |
| `onnx_reranker` | `onnx_reranker` | local ONNX cross-encoder reranker |
| `gliner_classifier` | `gliner_classifier` | zero-shot classification (gliner4j) |
| `gliner_ner` | `gliner_ner` | zero-shot NER (gliner4j) |
| `llama_cpp_embedding` | `llama_cpp_embedding` | GGUF embedding model |
| `llama_cpp_reranker` | `llama_cpp_reranker` | GGUF reranker |
| `remote_llm` / `remote_classifier` / `remote_embedding` / `remote_reranker` | — (uses `server:`) | proxy to another Singularitee over gRPC |
| `regex` | `regex` | client-local regex entity matcher (pure Java) |
| `composite_classifier` | `composite_classifier` | client-local union of other classifier ids |

### Common model fields
| Field | Default | Purpose |
| --- | --- | --- |
| `id` | — | Stable logical id referenced by pipelines. |
| `name` | — | HuggingFace repo / model name (source of the files). |
| `type` | — | One of the strings above (case-insensitive). |
| `server` | `default` endpoint | Remote endpoint id for `remote_*` models. |
| `memory_check` | `warn` | Pre-load memory policy: `fail`, `warn`, or `disabled`. |
| `download` | — | Narrows what gets pulled from HuggingFace — see below. |
| `task` | engine's own | Overrides the task slug the model is advertised under. |
| `visible` | `true` | `false` keeps the model out of the catalogue — see below. |
| `modalities` | detected | Overrides the input modalities the model is advertised as accepting. |

### `task`, `visible` and `modalities`

All three say how a model or pipeline is *published*, and all three work the same on
either.

`task` is the slug callers route on — `text-generation`, `text-classification`,
`token-classification`, `feature-extraction`, `reranking`. Models answer it from
their engine, so declaring it is only needed when a model serves a surface its
engine cannot infer. Pipelines have no engine to ask: an undeclared pipeline task
is derived at registration from the model behind the `role: output` step (falling
back to the entry step), which is why a guarded, routed pipeline over an LLM
advertises itself as plain `text-generation`. Nothing is ever advertised as a
"pipeline" — see [OpenAI HTTP API](../openai-http-api/README.md).

`visible: false` takes an entry out of the catalogue: gone from `/v1/models` and
from `ListModels` / `ListPipelines`, `404` by id, and `model_not_found` on every
inference route. It stays reachable where composition needs it — as a pipeline's
model, as a sub-pipeline, and over gRPC to another Singularitee. That is the
point: publish the pipeline, hide the four models and two helper pipelines it is
assembled from.

```yaml
models:
  - id: llm                   # the pipeline's engine — not for direct calls
    name: Qwen/Qwen3-0.6B-GGUF
    type: llama_cpp
    visible: false
    llama_cpp: { path: Qwen3-0.6B-Q8_0.gguf }
pipelines:
  - id: assistant             # the one id clients see, advertised as text-generation
    entry: generate
    steps:
      - id: generate
        type: infer
        role: output
        config: { model_id: llm, output_field: generate.output }
```

`http.expose-pipelines: false` still hides *every* pipeline at once; `visible` is
the per-entry switch on top of it.

`modalities` lists what the entry accepts as input — `text`, `image`, `audio`.
Leave it unset and the backend is asked: llama.cpp interrogates the loaded `mmproj`
projector, vLLM reads `vision_config` / `audio_config` out of the checkpoint's
`config.json`, and a pipeline inherits from the model behind its output step.
Declare it only where nothing can be interrogated — a `remote_*` proxy, or a vLLM
model whose weights were never resolved to a local directory — because a model that
under-reports will have its media requests refused with `unsupported_modality`. A
multimodal model's `task` stays `text-generation`: modality says what it reads, not
which endpoint it serves. See [Multimodal](../multimodal/README.md).

### `download` block

Each resolver already drops the formats its engine cannot read (vLLM takes one
weight format plus metadata; GLiNER takes one ONNX variant). `download.exclude`
is for what those rules cannot know: a duplicate the repo ships in the *same*
format, a variant you do not want, a multi-gigabyte extra you would rather not
transfer.

```yaml
models:
  - id: llm
    name: mistralai/Mistral-7B-Instruct-v0.3
    type: vllm
    download:
      exclude:
        - "consolidated*.safetensors"   # duplicate of the sharded weights
        - "original/*"                  # Meta-style original checkpoint
        - "*.pth"
```

| Field | Default | Purpose |
| --- | --- | --- |
| `exclude` | — | Glob patterns for repository files to skip. |

Patterns match the repository-relative path: `*` within a path segment, `**`
across segments, `?` a single character; everything else — `.` included — is
literal, and matching is case-insensitive. A pattern with no `/` also matches on
the file name alone, so `"*.pth"` catches `original/consolidated.00.pth`; a
pattern with a `/` is anchored at the repo root, so `"original/*"` leaves a
nested `nested/original/...` alone.

Excludes only ever *narrow* the built-in selection — naming a `.gguf` will not
make vLLM download one. They apply where a resolver picks a set of files out of
a repository listing (`vllm`, `gliner_*`, and the sibling/tokenizer listings of
the `onnx_*` types). A file named outright in the model definition — a
`llama_cpp` `path:`, an ONNX `model_path:` or `tokenizer_path:` — is always
fetched, since excluding it could only turn a working config into a failed load.

For `vllm`, exclusions are applied *before* the weight format is chosen, so
excluding a repo's safetensors falls back to its `.bin` weights rather than
selecting nothing.

### `llama_cpp` block
| Field | Default | Purpose |
| --- | --- | --- |
| `path` | — | GGUF filename inside the repo/cache. |
| `n_ctx` / `n_batch` / `n_ubatch` / `n_seq_max` | engine default | Context, batch sizes, max parallel sequences (only sent when > 0). |
| `n_gpu_layers` | engine default | Layers to offload to GPU (`999` = all). |
| `pooling_type` / `attention_type` / `flash_attn_type` | engine default | Engine enums as strings (e.g. `flash_attn_type: AUTO`). |
| `offload_kqv` | `false` | Offload KV-cache to GPU. |
| `lora_path` / `mmproj_path` / `media_marker` | — | LoRA adapter, multimodal projector, media marker string. |
| `eog_ramp_start` / `eog_ramp_max_bias` | disabled / `100` | Budget-aware soft landing: end `max_tokens`-limited answers on a finished sentence instead of mid-word ([Text Generation](../text-generation/README.md)). |

### `vllm` block
| Field | Default | Purpose |
| --- | --- | --- |
| `dtype` / `quantization` / `kv_cache_dtype` | engine default | e.g. `dtype: auto`, `quantization: awq`. |
| `max_model_len` / `max_num_seqs` / `max_num_batched_tokens` | engine default | Context and batching limits (sent when > 0). |
| `gpu_memory_utilization` | engine default | Fraction of GPU memory to claim (e.g. `0.35`). |
| `enforce_eager` / `trust_remote_code` / `enable_chunked_prefill` / `enable_lora` | `false` | Boolean toggles. Only ever forwarded when `true`: an omitted key and an explicit `false` are indistinguishable in the proto, so leaving one out means "engine default", not "off". |
| `enable_prefix_caching` | engine default (off on Metal) | Three-valued: unset leaves the engine's own default, and an explicit `false` genuinely disables it. Defaults to off on Metal, where the paged runtime desyncs — see [Deployment](../deployment/README.md#vllm-on-apple-silicon-local-development-only). |
| `seed` / `max_loras` / `max_lora_rank` | engine default | Sent when > 0. |
| `enable_sleep_mode` | unset | Nullable boolean — only forwarded when present. |
| `tensor_parallel_size` | server default, then `1` | GPUs to shard each layer across. Needed for weights that do not fit on one card. |
| `pipeline_parallel_size` | server default, then `1` | Pipeline stages to split the layers into. |
| `distributed_executor_backend` | server default, then vLLM's | `mp` or `ray`. Any of these three above their default switches vLLM to the V1 engine with subprocess workers. |

**Pre-Ampere GPUs (compute capability < 8.0) are adapted automatically.** On a
Turing or Volta card the server reads the capability from `nvidia-smi` before the
engine is built and corrects two vLLM defaults that would otherwise fail at model
load:

- `dtype: auto` resolves to `float16` — those cards have no bfloat16, and vLLM
  refuses the load rather than downgrading.
- FlashInfer is avoided: the sampler is turned off and the attention backend is
  pinned to `TRITON_ATTN`. vLLM would otherwise select FlashInfer there —
  FLASH_ATTN needs sm_80+, and FlashInfer's own `supports_compute_capability()`
  claims Turing works — but its kernels either fail to JIT-build or fail at
  runtime with `BatchPrefillWithPagedKVCache failed with error invalid argument`.

Both are logged when they happen, and an explicit setting always wins: a
`dtype:` in the workspace, or `-Dvllm4j.attentionBackend` /
`VLLM4J_ATTENTION_BACKEND` for the backend.

`gpu_memory_utilization` is a fraction of **total** VRAM, so a value tuned for a
24 GB card can leave nothing for the KV cache on a smaller one. When the budget
cannot even hold the weights the load is refused up front, naming the numbers and
the setting, rather than reaching vLLM's `No available memory for the cache
blocks`. This one is never adjusted for you — it is your capacity decision.

### ONNX blocks (`onnx_classifier` / `onnx_embedding` / `onnx_reranker`)
| Field | Default | Purpose |
| --- | --- | --- |
| `model_path` / `tokenizer_path` / `config_json_path` | — | Files inside the repo (e.g. `onnx/model.onnx`, `tokenizer.json`, `config.json`). |
| `max_sequence_length` | engine default | Token cap per input (oversized inputs are auto-split). |
| `classifier_mode` | — | classifier only: `SEQUENCE` or `TOKEN`. |
| `labels` | from `config.json` | classifier only: label override list. |
| `pooling_mode` / `normalize` | — / `false` | embedding only: e.g. `pooling_mode: CLS`, `normalize: true`. |
| `scoring` | auto | reranker only: score transform. |

### GLiNER blocks (`gliner_classifier` / `gliner_ner`)
| Field | Default | Purpose |
| --- | --- | --- |
| `model_dir` | — | Model directory (usually resolved via `name`). |
| `labels` / `entities` | — | List of `{name, description}` zero-shot labels or entity types. |
| `threshold` | engine default | Minimum confidence (e.g. `0.4`, sent when > 0). |
| `variant` | — | Runtime variant: `onnx`, `onnx_fp16`, ... |
| `token_cap` | engine default | Max tokens per chunk (sent when > 0). |

## Notes
- **The root `workspace:` key is mandatory** — a file without it fails with `IllegalArgumentException`. Unknown fields anywhere are silently ignored, so typos don't error: double-check key spellings.
- **Includes resolve in hardcoded sibling folders** of the workspace file: `includes.models` in `./models/`, `includes.pipelines` in `./pipelines/`, `includes.templates` in `./templates/`. Entries may nest into subfolders of those (`llama/llm-qwen3-0.6b.yaml`), which is how `examples/modular/` groups fragments by backend. Entries may be globs (`*`, `?`, `{}`), expanded in sorted order; missing files log a warning and are skipped. Includes are **not recursive** — an included file's own `includes:` is ignored, and only its matching section is merged (a file included under `models:` contributes only `workspace.models`).
- **Template sources are mutually exclusive**: a `templates:` entry with both `content` and `file` throws; with neither it is skipped with a warning. Steps pick a template via `prompt.template_id` (registry), `prompt.template_file`, or inline `prompt.template` — again, exactly one.
- **Step ids must match `[A-Za-z_][A-Za-z0-9_]*`** because they become Jinja2 identifiers — hyphens are rejected (model and pipeline ids may use hyphens).
- **The VRAM pre-flight reads the model itself**: `memory_check` for `vllm` models no longer needs any hand-written dimensions. The layer count, KV heads, head dimension, context length and quantized weight width are read from the checkpoint's `config.json` (via vLLM's own resolver, so a repo id, a local directory and a gated repo all behave the same), and the parameter count from the Hub's safetensors index. Quantization is honoured — an AWQ checkpoint is sized at 4 bits per weight, not at its `float16` activation dtype. If the shape cannot be read (offline, or a model needing `trust_remote_code`) the check is skipped with a warning rather than blocking the load.
- **The GPU topology has a deployment-wide fallback**: `tensor_parallel_size`, `pipeline_parallel_size` and `distributed_executor_backend` describe the machine rather than the model, so the server reads `ai.vllm.tensor-parallel-size`, `ai.vllm.pipeline-parallel-size` and `ai.vllm.distributed-executor-backend` from `gravitee.yml` (or the matching `GRAVITEE_*` environment variables, e.g. `GRAVITEE_AI_VLLM_TENSORPARALLELSIZE=4`). A model's own value wins; otherwise the server default applies; otherwise vLLM decides.
- **Numeric zero means "engine default"**: omitted numeric fields parse as `0` and are dropped before reaching the engine; you cannot explicitly set a numeric option to `0`. Booleans default to `false` and are always forwarded (except nullable `enable_sleep_mode`).
- **`memory_check` defaults to `warn`** (also for unrecognized values): the load proceeds with a log warning if the model may not fit. Use `fail` to abort the load instead, or `disabled` for small CPU models.
- **`regex` and `composite_classifier` are client-local** — pure-Java models that run inside the loading process (server or CLI client) with no engine load; composite members must be ids of other classifier models in the same workspace.

## See also
- [Getting Started](../getting-started/README.md) — pointing the server at a workspace via `ai.workspace.path`.
- [Pipelines](../pipelines/README.md) — the `steps:` DAG: step types, roles, `entry`/`next_step`.
- [Remote & Multi-Server](../remote-and-multi-server/README.md) — `remote:` endpoints, `remote_*` models, and remote proxy pipelines.
- [Classification](../classification/README.md) — ONNX/GLiNER/regex/composite classifiers in practice.
- [Deployment](../deployment/README.md) — the production workspaces bundled in the distribution.
