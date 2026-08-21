# Concepts

> The vocabulary the rest of the documentation assumes: workspaces, models, pipelines, templates, logical ids, model families and the engines behind them, tasks and modalities, visibility, and the three ways a request can be executed.

## Overview

Singularitee is an inference server. It loads models in its own process, runs them behind a
gRPC API (primary) and an optional OpenAI-compatible HTTP API, and can chain them into
multi-step pipelines that run next to the models. Everything a server publishes is declared
in one YAML document, the workspace.

## Key types

| Term | What it is | Declared under |
| --- | --- | --- |
| Workspace | The unit of deployment: one YAML file (`workspace:` root key) listing what a process publishes. Composable from fragments with `includes:`. | the file itself |
| Model | One engine-backed or pure-Java component with a stable id: an LLM, a classifier, an embedder, a reranker, or a proxy to one running elsewhere. | `workspace.models` |
| Pipeline | A directed graph of steps over a per-request context. Callable by id exactly like a model. | `workspace.pipelines` |
| Template | A named Jinja2 template (`id` + `content` or `file`) that steps reference by `template_id`. | `workspace.templates` |
| Step | One node of a pipeline with a `type` (`infer`, `classify`, `guard`, `route`, ...) and a `config`. | `pipeline.steps` |
| Logical id | The `id` of a model or pipeline. Pipelines reference models by it, clients call by it. | `id:` on every entry |

### Logical ids

A model's `id` is what pipelines reference and what callers send as `model`. Its `name` is
where the weights come from (a HuggingFace repository or a local path). Keeping them apart is
what lets one pipeline run unchanged over any backend: the same `toxicity-guard.yaml` works
whether `toxicity` is bound to an ONNX classifier on this server or to a `remote_classifier`
proxy pointing at another one. The examples use `llm`, `pii`, `toxicity`, `router`,
`text-embedding`, `reranker` throughout. Several model files may share an id; a server
includes exactly one of them.

### Model families and engines

| `type` | Family | Engine | Task reported | Reference |
| --- | --- | --- | --- | --- |
| `llama_cpp` | text generation (text, vision, audio) | llama.cpp | `text-generation` | [llama_cpp](../reference/models/llama_cpp.md) |
| `vllm` | text generation (text, vision, audio) | vLLM (embedded CPython) | `text-generation` | [vllm](../reference/models/vllm.md) |
| `onnx_classifier` | classifier, sequence or token level | ONNX Runtime | `text-classification` / `token-classification` | [onnx_classifier](../reference/models/onnx_classifier.md) |
| `gliner_classifier` | zero-shot sequence classifier | GLiNER (gliner4j on ONNX Runtime) | `text-classification` | [gliner_classifier](../reference/models/gliner_classifier.md) |
| `gliner_ner` | zero-shot entity extraction | GLiNER (gliner4j on ONNX Runtime) | `token-classification` | [gliner_ner](../reference/models/gliner_ner.md) |
| `onnx_embedding` | embedding (bi-encoder) | ONNX Runtime | `feature-extraction` | [onnx_embedding](../reference/models/onnx_embedding.md) |
| `llama_cpp_embedding` | embedding (bi-encoder) | llama.cpp | `feature-extraction` | [llama_cpp_embedding](../reference/models/llama_cpp_embedding.md) |
| `onnx_reranker` | reranker (cross-encoder) | ONNX Runtime | `reranking` | [onnx_reranker](../reference/models/onnx_reranker.md) |
| `llama_cpp_reranker` | reranker (cross-encoder) | llama.cpp | `reranking` | [llama_cpp_reranker](../reference/models/llama_cpp_reranker.md) |
| `regex` | token classifier, pure Java | none (in-process) | `token-classification` | [regex](../reference/models/regex.md) |
| `composite_classifier` | union of classifiers, pure Java | none (in-process) | widest of its members | [composite_classifier](../reference/models/composite_classifier.md) |
| `remote_llm` | proxy | gRPC to another Singularitee | `text-generation` | [remote_llm](../reference/models/remote_llm.md) |
| `remote_classifier` | proxy | gRPC to another Singularitee | `text-classification` unless declared | [remote_classifier](../reference/models/remote_classifier.md) |
| `remote_embedding` | proxy | gRPC to another Singularitee | `feature-extraction` | [remote_embedding](../reference/models/remote_embedding.md) |
| `remote_reranker` | proxy | gRPC to another Singularitee | `reranking` | [remote_reranker](../reference/models/remote_reranker.md) |

Sequence classification labels the whole input (sentiment, toxicity, intent). Token
classification labels spans inside it (PII, entities) and returns character offsets, which is
what the `guard` step's redaction needs. Zero-shot models take their label set from the
workspace instead of from training. Embedders turn text into a vector; rerankers score a
`(query, document)` pair directly.

llama.cpp is the default engine and the one to develop against: cross-platform, no Python,
set up by `install.sh`. ONNX Runtime and GLiNER need nothing beyond the build. vLLM is opt-in
and Linux/CUDA-first. An engine factory is registered only when its library is on the
classpath, which is how a per-engine distribution reports "no factory for type" instead of a
class-loading error.

### Tasks and modalities

Every published entry advertises a task, the slug `/v1/models` reports and callers route on:
`text-generation`, `text-classification`, `token-classification`, `feature-extraction`,
`reranking`. A model answers it from its engine; a pipeline inherits the model behind its
`role: output` step. `task:` on an entry overrides it; any other value fails the workspace at
load. Nothing is ever advertised as "pipeline".

Modalities are what an entry accepts as input (`text`, `image`, `audio`). They are detected,
not declared: llama.cpp asks its multimodal projector, vLLM reads `vision_config` /
`audio_config` from the checkpoint's `config.json`, a pipeline takes the union over its
model-bound steps. The HTTP API refuses media the target cannot read (`unsupported_modality`)
rather than dropping it. Declare `modalities:` only where detection cannot run (a `remote_*`
proxy, a vLLM model never resolved locally). A vision or audio model is still
`task: text-generation`.

### Visibility

`visible: false` removes an entry from `/v1/models`, `ListModels` / `ListPipelines` and from
HTTP resolution, while it stays callable as a pipeline dependency and over gRPC. Publish the
pipeline, hide the models it is built from.

### Three execution modes

| Mode | Where the DAG runs | Where the models run | How |
| --- | --- | --- | --- |
| Server-side pipeline | on the server | on the server | call a pipeline id (`InferPipeline`, or `model: <pipeline-id>` over HTTP) |
| Direct model | no DAG | on the server | call a model id (`Infer`, `Classify`, `Embed`, `Rerank`) |
| Client-side pipeline | in the calling process (`ClientPipelineExecutor`) | on one or more remote servers | a workspace with `remote:` endpoints and `remote_*` models |

A direct call to a text model returns the raw token stream: channel markers and tool-call
tags are parsed by the `infer` step's `tags:`, so client-facing traffic goes through a
pipeline id. Client-side pipelines run the same step executors as the server; `regex` and
`composite_classifier` run in-process on either side.

### One backend per process

llama.cpp, vLLM and ONNX Runtime each load their own native libraries and compete for GPU
memory. Run one backend per process and compose across processes over gRPC: a server per
engine, a client workspace (or a gateway) that stitches them. `examples/modular/` is that
topology.

## See also

- [Pipelines](./pipelines/README.md), the execution model of a step graph.
- [Workspaces](../workspaces/README.md), the YAML format.
- [Model reference](../reference/models/README.md), every model type and key.
- [Step reference](../reference/steps/README.md), every step type and key.
