# Singularitee — Capabilities

One folder per capability, one page each. Start with **[Getting Started](./getting-started/README.md)** — every other page builds on it. Architecture and rationale live in [ARCHITECTURE.md](../ARCHITECTURE.md); ready-to-run configs in [examples/](../examples/README.md).

### Core
| Capability | What it does |
| --- | --- |
| [Getting Started](./getting-started/README.md) | Build the standalone distribution, run it via `gravitee.sh` or `SingulariteeContainer`, and configure the `grpc`/`http`/`ai` blocks, model cache, and HuggingFace downloads. |
| [Workspaces](./workspaces/README.md) | The workspace YAML format: models (all types with their config blocks), pipelines, templates, remote endpoints, `includes:` resolution, and the logical-id convention. |
| [Text Generation](./text-generation/README.md) | Stream tokens from a published LLM via the `Infer` RPC or an `infer` pipeline step — sampling params, Jinja chat templates, stop strings, and reasoning-tag routing. |
| [Pipelines](./pipelines/README.md) | Compose models into a reactive DAG of steps — guards, classifiers, routers, loops, and streaming generation — walked by `PipelineExecutor` over a shared `PipelineContext`. |

### Pipeline steps
| Capability | What it does |
| --- | --- |
| [Guards & Redaction](./guards-and-redaction/README.md) | Screen pipeline input with classifier, LLM-as-judge, or regex guards, and reject, warn, or redact before generation. |
| [Routing](./routing/README.md) | Branch a pipeline to different steps based on a classifier label, embedding similarity, or an LLM's structured output. |
| [Loops & Chain-of-Thought](./loops-and-cot/README.md) | Build bounded self-refinement cycles with `loop` back-edges, `break` halts, and infer step roles that separate reasoning from the final answer. |
| [Sub-pipelines](./sub-pipelines/README.md) | Invoke another published pipeline as a nested step — locally or on a remote server — and capture its output in the parent context. |
| [Engine-managed to-dos](./todos/README.md) | Plan-and-execute with server-executed `set_todos`/`complete_todo` tools, `{{todos}}` in prompts, `todos.remaining` loop gates, and streamed `gravitee.progress` events. |

### Classification & retrieval
| Capability | What it does |
| --- | --- |
| [Classification](./classification/README.md) | Classify text with ONNX BERT, GLiNER zero-shot, regex, and composite models via the `Classify` RPC, the `classify` step, or `POST /v1/classify`. |
| [Embeddings & Reranking](./embeddings-and-reranking/README.md) | Turn text into dense vectors and score documents against queries with ONNX or llama.cpp backends over gRPC, pipeline steps, or the OpenAI/Cohere-style HTTP endpoints. |
| [Multimodal (Vision & Audio)](./multimodal/README.md) | Attach images and audio to chat messages via OpenAI `image_url`/`input_audio` content parts or gRPC `MediaContent`, decoded by llama.cpp's mtmd projector. |

### Serve & integrate
| Capability | What it does |
| --- | --- |
| [gRPC API & Java Client](./grpc-api-and-client/README.md) | The four gRPC services (model, pipeline, inference, vector), their request/response shapes, the `SingulariteeClient` Java client, and port/TLS/basic-auth configuration. |
| [OpenAI HTTP API](./openai-http-api/README.md) | The opt-in OpenAI-compatible HTTP/JSON server over the same models and pipelines — chat completions, embeddings, and Gravitee classify/rerank/similarity extensions. |
| [OpenAPI spec](./openapi/singularitee.openapi.yaml) | Machine-readable schema for every HTTP endpoint above, plus `/health`. Feed it to a client generator or an API gateway. |
| [Validated Models](./models/README.md) | Models validated end-to-end (reasoning routing, structured tool calls, prefix caching) with their exact workspace configuration; dialect-as-config ground rules. |
| [Remote Models & Multi-Server](./remote-and-multi-server/README.md) | Run pipelines on the client with `remote_*` model proxies over gRPC, composing models that live on one or many servers. |

### Operations
| Capability | What it does |
| --- | --- |
| [Observability](./observability/README.md) | OpenTelemetry spans per RPC/pipeline/step/model-call, plus Micrometer metrics scraped by Prometheus at `/_node/metrics/prometheus`. |
| [Deployment](./deployment/README.md) | Production deployment: CPU and CUDA Docker images, bundled production workspaces, vLLM venv setup, and one-model-per-process topology. |
