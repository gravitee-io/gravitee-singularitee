# Model types

> Every `type:` a `workspace.models` entry accepts, the keys all entries share, and one page per type with its full config block.

## Overview

A model entry names a stable `id`, a source (`name`), a `type`, and one config block keyed by
that type (`llama_cpp:`, `vllm:`, `onnx_classifier:`, ...). The loader (`YamlWorkspaceLoader`)
turns local types into a `ModelLoadRequest` handed to the engine factory that is registered
for that type; remote types become lazy gRPC proxies; `regex` and `composite_classifier` are
built in-process with no engine at all. The type string is parsed case-insensitively.

## Index

| `type` | Family | Engine | Task reported | Platforms |
| --- | --- | --- | --- | --- |
| [`llama_cpp`](./llama_cpp.md) | text generation | llama.cpp | `text-generation` | macOS/Apple Silicon (Metal), Linux/x86_64 (CPU, CUDA) |
| [`vllm`](./vllm.md) | text generation | vLLM | `text-generation` | Linux/CUDA; Apple Silicon venv for development |
| [`onnx_classifier`](./onnx_classifier.md) | sequence or token classifier | ONNX Runtime | `text-classification` or `token-classification` | any (CPU); CUDA with the `-Pcuda` build |
| [`onnx_embedding`](./onnx_embedding.md) | embedding | ONNX Runtime | `feature-extraction` | any (CPU); CUDA with `-Pcuda` |
| [`onnx_reranker`](./onnx_reranker.md) | reranker | ONNX Runtime | `reranking` | any (CPU); CUDA with `-Pcuda` |
| [`gliner_classifier`](./gliner_classifier.md) | zero-shot classifier | gliner4j (ONNX Runtime) | `text-classification` | any (CPU); CUDA via `GRAVITEE_GLINER_EXECUTION_PROVIDER` |
| [`gliner_ner`](./gliner_ner.md) | zero-shot entity extraction | gliner4j (ONNX Runtime) | `token-classification` | any (CPU); CUDA via `GRAVITEE_GLINER_EXECUTION_PROVIDER` |
| [`llama_cpp_embedding`](./llama_cpp_embedding.md) | embedding | llama.cpp | `feature-extraction` | as `llama_cpp` |
| [`llama_cpp_reranker`](./llama_cpp_reranker.md) | reranker | llama.cpp | `reranking` | as `llama_cpp` |
| [`remote_llm`](./remote_llm.md) | proxy | gRPC | `text-generation` | any JVM |
| [`remote_classifier`](./remote_classifier.md) | proxy | gRPC | `text-classification` unless declared | any JVM |
| [`remote_embedding`](./remote_embedding.md) | proxy | gRPC | `feature-extraction` | any JVM |
| [`remote_reranker`](./remote_reranker.md) | proxy | gRPC | `reranking` | any JVM |
| [`regex`](./regex.md) | token classifier | pure Java | `token-classification` | any JVM |
| [`composite_classifier`](./composite_classifier.md) | classifier union | pure Java | widest task of its members | any JVM |

Engine factories are registered only when the engine library is on the classpath
(`SingulariteeConfiguration`): llama.cpp types probe `io.gravitee.llama.cpp.LlamaModel`, `vllm`
probes `io.gravitee.vllm.engine.VllmEngine`, ONNX types probe `ai.onnxruntime.OrtEnvironment`,
GLiNER types probe `io.gravitee.lab.gliner4j.runtime.BaseRuntime`. A distribution flavour that
lacks a library skips the type at boot with "no factory for type".

## Common keys

These keys belong to `WorkspaceDefinition.ModelDefinition` and apply to every type.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | unset | Logical id pipelines and callers use. For `remote_*` types it must equal the id published on the remote server. |
| `name` | string | unset | HuggingFace repository id (`Qwen/Qwen3-0.6B-GGUF`) or a local path. Display name for remote and pure-Java types (falls back to `id`). |
| `type` | string | unset | One of the slugs above, case-insensitive. Unknown or blank: the model is skipped with a WARN, the workspace still loads. |
| `server` | string | `default` | `remote_*` only: id of an endpoint in the `remote:` block. Ignored by other types. |
| `task` | string | unset (engine decides) | One of `text-generation`, `text-classification`, `token-classification`, `feature-extraction`, `reranking`. Any other value fails the workspace. |
| `visible` | boolean | `true` | `false` hides the model from the listings and from HTTP resolution; it stays callable from pipelines and over gRPC. |
| `modalities` | list of string | unset (detected) | Subset of `text`, `image`, `audio`. Any other value fails the workspace. Declare only where detection cannot run. |
| `memory_check` | string | `warn` | Pre-load fit check: `fail` aborts the load, `warn` logs and continues, `disabled` skips it. Unknown values fall back to `warn`. Used by the llama.cpp and vLLM engines. |
| `download.exclude` | list of glob | unset | Repository files not to download where a resolver selects files from a listing (`vllm`, `gliner_*`, the sibling and tokenizer listings of `onnx_*`). A file named outright (`path`, `model_path`, `tokenizer_path`) is always fetched. |
| `<type>` | block | type defaults | The config block named after `type`. Absent block means every key at its default. |

Glob rules for `download.exclude`: `*` within a path segment, `**` across segments, `?` one
character, everything else literal, case-insensitive. A pattern without `/` also matches the
bare file name (`"*.pth"` catches `original/consolidated.00.pth`); a pattern with `/` is
anchored at the repository root. Blank entries are dropped. Excludes only narrow the engine's
own selection; for `vllm` they apply before the weight format is chosen, so excluding the
safetensors falls back to `.bin`.

## Validation at load

- A missing root `workspace:` key fails the load.
- `task` and `modalities` are validated before the type: a bad slug fails the whole workspace.
- A model whose type block cannot be parsed is skipped with `Skipping model '<name>'` and the
  rest of the workspace loads. A model whose weights fail to resolve is skipped at boot with
  `failed to load`; the server still starts.
- Numeric keys left out parse as `0` and are forwarded as "engine default"; a numeric key
  cannot be set to `0` explicitly. Plain booleans default to `false`; nullable booleans
  (`offload_kqv`, `use_mlock`, `prompt_cache`, `enable_prefix_caching`, `enable_sleep_mode`)
  distinguish unset from `false`.
- Unknown keys anywhere are ignored, not rejected.

## Downloads and cache

Weights resolve in Java through `HuggingFaceModelDownloader` into
`ai.models.path` (`run-server.sh` sets `~/.cache/gravitee-singularitee/models`), mirrored as
`<cache>/<org>/<model>/`. A path that already exists locally is used as-is; a cached file is
reused; otherwise the file is fetched from the repository named by `name`. `HF_TOKEN` (or
`ai.huggingface.token`) unlocks gated repositories. Each type's page states what its resolver
downloads.

## See also

- [Concepts](../../concepts/README.md), the model families and which engine backs each.
- [Workspaces](../../workspaces/README.md), the surrounding YAML: `includes`, `templates`, `remote`.
- [Step reference](../steps/README.md), the steps that call these models.
- [Configuration](../configuration.md), `ai.models.path`, `ai.huggingface.token`, `ai.vllm.*`.
