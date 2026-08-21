# Singularitee documentation

> Start with [Getting Started](./getting-started/README.md), then [Concepts](./concepts/README.md). Everything else is reachable from here.

## How the documentation is organised

| Section | Answers |
| --- | --- |
| [Getting Started](./getting-started/README.md) | How do I build, run and call it for the first time? |
| [Concepts](./concepts/README.md) | What is a workspace, a model, a pipeline, a template? Which engine backs what? |
| [Architecture](./architecture/README.md) | How is it put together, how does a request execute, how does a pipeline run? |
| [Workspaces](./workspaces/README.md) | How do I write the YAML that declares what a server publishes? |
| [Reference](#reference) | What is every key, every option, every default? |
| [APIs](#apis) | How do I call it over gRPC or HTTP, and what are the conventions of each? |
| [Guides](#guides) | How do I do X (guard, route, loop, call tools, go multimodal, run multi-server)? |
| [Operations](#operations) | How do I deploy, observe and size it? |

## Reference

One page per model type, one page per step type, one page for templates, one for every configuration key.

| Page | Contents |
| --- | --- |
| [Model types](./reference/models/README.md) | `llama_cpp`, `vllm`, `onnx_classifier`, `onnx_embedding`, `onnx_reranker`, `gliner_classifier`, `gliner_ner`, `llama_cpp_embedding`, `llama_cpp_reranker`, `remote_*`, `regex`, `composite_classifier`. |
| [Step types](./reference/steps/README.md) | `infer`, `classify`, `embed`, `route`, `guard`, `llm_guard`, `loop`, `break`, `sub_pipeline`, `regex_guard`, `tool_select`, `todo`. |
| [Templates](./reference/templates/README.md) | The `templates:` section, Jinja variables, chat and tool-extraction templates. |
| [Context fields](./reference/context-fields.md) | Every key a step reads from or writes to the pipeline context. |
| [Configuration](./reference/configuration.md) | Every `gravitee.yml` key, its default and its environment variable. |

## APIs

| Page | Contents |
| --- | --- |
| [API overview](./api/README.md) | The two fronts, what they share, and a side-by-side of their conventions. |
| [gRPC API](./api/grpc/README.md) | The four services, every RPC, streaming events, errors, auth, TLS. |
| [HTTP API](./api/http/README.md) | The OpenAI-compatible endpoints, error envelope, SSE, auth. |
| [Java client](./api/java-client/README.md) | `SingulariteeClient`: the only dependency a caller needs. |
| [OpenAPI specs](../openapi/README.md) | One machine-readable spec per API type under `openapi/`. |

## Guides

| Guide | What you will build |
| --- | --- |
| [Text generation](./guides/text-generation/README.md) | Stream tokens from a model or a pipeline, with sampling, stop strings and reasoning tags. |
| [Classification](./guides/classification/README.md) | Sequence, token and zero-shot classification; regex and composite classifiers. |
| [Embeddings and reranking](./guides/embeddings-and-reranking/README.md) | Vectors, cross-encoder reranking, similarity. |
| [Guards and redaction](./guides/guards-and-redaction/README.md) | Reject, warn or redact input with classifier, LLM-as-judge or regex guards. |
| [Routing](./guides/routing/README.md) | Branch on a label, on embedding similarity, or on an LLM's structured answer. |
| [Loops and chain-of-thought](./guides/loops-and-cot/README.md) | Bounded self-refinement with `loop` and `break`. |
| [Tool calling](./guides/tool-calling/README.md) | Caller tools, shortlisting, extraction dialects, repair loops. |
| [Todos](./guides/todos/README.md) | Server-executed plans with live progress events. |
| [Sub-pipelines](./guides/sub-pipelines/README.md) | Nest pipelines, locally or on another server. |
| [Multimodal](./guides/multimodal/README.md) | Image and audio content parts. |
| [Remote and multi-server](./guides/remote-and-multi-server/README.md) | Client-side DAGs over `remote_*` models. |

## Operations

| Page | Contents |
| --- | --- |
| [Deployment](./operations/deployment/README.md) | Docker images per engine, build arguments, one model per process. |
| [Observability](./operations/observability/README.md) | OpenTelemetry spans and Prometheus metrics. |
| [Validated models](./operations/models/README.md) | Models run end to end, with measurements. |

## Elsewhere

- [README](../README.md): what the project is and the quick start.
- [examples/](../examples/README.md): runnable workspaces for every capability.
- [CONTRIBUTING](../CONTRIBUTING.md): building, testing, commit conventions.
