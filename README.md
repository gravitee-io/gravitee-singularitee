<p align="center">
  <img src="images/logo.svg" alt="Gravitee - Singularitee" width="500">
</p>

<p align="center">
  <a href="https://circleci.com/gh/gravitee-io/gravitee-singularitee"><img src="https://img.shields.io/circleci/build/github/gravitee-io/gravitee-singularitee/main?style=flat-square&color=EE7B2E&label=build" alt="CircleCI"></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-1E90FF?style=flat-square" alt="License"></a>
  <a href="https://community.gravitee.io"><img src="https://img.shields.io/badge/community-forum-FFFFFF?style=flat-square&labelColor=555555" alt="Community"></a>
</p>

**Gravitee's Inference Server.**

A production-grade inference server for running LLM pipelines outside the Gravitee gateway process — built on [gravitee-node](https://github.com/gravitee-io/gravitee-node) with Vert.x 5 and RxJava 3. It can also expose an optional **OpenAI-compatible HTTP API** over the same models and pipelines (see [Calling it](#calling-it)).

## Why the name

A play on **Gravitee**. Models are heavy — and a singularity is what gravity produces at its extreme: all that mass collapsed into one single point. That is exactly what this server is. Every heavy thing the gateway cannot carry — the GPU, the native libraries, the model weights — is pulled out of the gateway and concentrated into one process. Gravitee attracts; Singularitee is where it all ends up.

## Why this exists

Singularitee runs model-hosting and inference as a **separate process**. Whatever calls it — a Gravitee gateway, an OpenAI SDK, `curl` — stays lightweight; Singularitee owns the GPU, the native libraries, and the model lifecycle.

## What it does

Models and pipelines are published declaratively from a workspace YAML at startup (weights download from HuggingFace automatically), then served with streaming as a first-class primitive:

| Capability | Notes |
|---|---|
| **Text generation** | Streamed token by token, from a model or a named pipeline. |
| **Pipelines** | A DAG of steps over those models — guards, classifiers, routers, loops, sub-pipelines. |
| **Classification** | ONNX BERT, GLiNER zero-shot, regex, composite. |
| **Embeddings & reranking** | Vectors, cross-encoder reranking, similarity. |
| **Discovery** | List and inspect the published models and pipelines. |

Two front-ends over the same registries and engines, so a capability behaves identically on either:

- **gRPC** (default, port 9090) — the primary API → [gRPC API & client](./docs/grpc-api-and-client/README.md)
- **OpenAI-compatible HTTP** (opt-in, port 8080) — point any OpenAI SDK at it → [OpenAI HTTP API](./docs/openai-http-api/README.md)

### Engines

| Engine | Backend                                                 | Use case |
|--------|---------------------------------------------------------|----------|
| **llama.cpp** | [llamaj.cpp](https://github.com/gravitee-io/llamaj.cpp) | GGUF models on CPU/Metal/CUDA. Single binary, no Python. |
| **vLLM** | [vLLM4j](https://github.com/gravitee-io/vLLM4j)      | HF Transformers via vLLM. Full CUDA, PagedAttention, continuous batching. |
| **ONNX** | ONNX Runtime                                            | Classifier, embedding, and reranker models. CPU with optional GPU. |
| **GLiNER** | [gliner4j](https://github.com/gravitee-io/gliner4j)  | Zero-shot NER and classification. |

Text-generation engines share a unified `BatchEngine` abstraction — vendored in-tree under `gravitee-singularitee-inference/` — for loading, batched inference, and token streaming.

Built on [gravitee-node](https://github.com/gravitee-io/gravitee-node) with Vert.x 5 and RxJava 3. The execution model, module breakdown, and **how pipelines work** are documented in **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## Documentation

Full documentation lives in **[`docs/`](./docs/README.md)** — one page per capability. New here, read in this order:

- **[Getting Started](./docs/getting-started/README.md)** — build, run, and every `gravitee.yml` key
- **[Workspaces](./docs/workspaces/README.md)** — the YAML that declares what to publish
- **[Pipelines](./docs/pipelines/README.md)** — composing models into a DAG of steps

Then, by topic:

- **APIs** — [gRPC & Java client](./docs/grpc-api-and-client/README.md) · [OpenAI HTTP API](./docs/openai-http-api/README.md) · [OpenAPI spec](./docs/openapi/singularitee.openapi.yaml)
- **Capabilities** — [text generation](./docs/text-generation/README.md) · [classification](./docs/classification/README.md) · [embeddings & reranking](./docs/embeddings-and-reranking/README.md) · [multimodal](./docs/multimodal/README.md)
- **Pipeline building blocks** — [guards & redaction](./docs/guards-and-redaction/README.md) · [routing](./docs/routing/README.md) · [loops & chain-of-thought](./docs/loops-and-cot/README.md) · [sub-pipelines](./docs/sub-pipelines/README.md)
- **Running it** — [validated models](./docs/models/README.md) · [remote & multi-server](./docs/remote-and-multi-server/README.md) · [observability](./docs/observability/README.md) · [deployment](./docs/deployment/README.md)

## Quick start

From a fresh clone (Java 25 + Maven):

```bash
./install.sh
```

That checks prerequisites, downloads the llama.cpp native libraries into `~/.llama.cpp` (they are **not** bundled in the jar, for licensing), builds the distribution, and starts the server with the OpenAI-compatible API on port 8080. From then on, switch workspaces with:

```bash
./run-server.sh --list                                          # every runnable workspace
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml      # small and fast
./run-server.sh --debug                                         # TRACE-log rendered prompts
```

Model weights download from HuggingFace on first start into `~/.cache/gravitee-singularitee/models`, so the first run of a new workspace is slow and later ones are not.

**→ [Getting Started](./docs/getting-started/README.md)** covers the manual build-and-run path, the distribution layout, the full `gravitee.yml` reference, and boot order. IntelliJ run configurations are committed under `.run/` (start a `Server` first, wait for it to load its model — the port binds immediately but calls return "Model server is still loading" until then).

## Configuration

Everything is configured in `gravitee.yml`, and **every key** also accepts a `GRAVITEE_`-prefixed
environment variable or a `-D` system property (`grpc.port` → `GRAVITEE_GRPC_PORT`,
`ai.workspace.path` → `GRAVITEE_AI_WORKSPACE_PATH`).

The few you need to start:

| Key | Default | |
|---|---|---|
| `grpc.port` | `9090` | The primary API. |
| `http.enabled` / `http.port` | `false` / `8080` | The OpenAI-compatible HTTP API — opt-in. |
| `ai.workspace.path` | — | Workspace loaded at startup. Unset = start empty. |
| `ai.huggingface.token` | `$HF_TOKEN` | Needed for gated repos. |

TLS and mTLS, gRPC Basic and HTTP Bearer auth, the model cache, streaming back-pressure, the
management port and OpenTelemetry are all covered — with defaults and every key — in
**[Getting Started](./docs/getting-started/README.md)**.

## Calling it

Point any OpenAI SDK — or `curl` — at the HTTP API once `http.enabled` is on:

```bash
curl -s localhost:8080/v1/models | jq                    # what this server publishes

curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"agent","messages":[{"role":"user","content":"Say hi in 3 words"}]}' | jq
```

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="sk-local-…")
client.chat.completions.create(model="agent", messages=[{"role": "user", "content": "hi"}], stream=True)
```

The `model` field takes a model id or a pipeline id. Beyond the standard OpenAI routes the server
adds `/v1/classify`, `/v1/rerank` and `/v1/similarity`; the full endpoint reference, streaming
semantics, auth and the error envelope are in
**[OpenAI HTTP API](./docs/openai-http-api/README.md)** (machine-readable schema:
[OpenAPI spec](./docs/openapi/singularitee.openapi.yaml)).

Over gRPC, use `SingulariteeClient` — see **[gRPC API & client](./docs/grpc-api-and-client/README.md)**.

Ready-to-run smoke tests live in [`examples/scripts/`](examples/scripts/) and need only
[uv](https://docs.astral.sh/uv/):

```bash
BASE_URL=http://localhost:8080/v1 uv run --with openai examples/scripts/openai_test.py
BASE_URL=http://localhost:8080/v1 uv run --with requests examples/scripts/classify_test.py
```

## Workspaces & examples

A **workspace** YAML declares the models and pipelines to publish at startup (`ai.workspace.path`). Models are referenced by a stable logical `id`, so the same pipeline runs against any backend bound to that id.

Every ready-made workspace lives in **[`examples/`](examples/README.md)** — one folder per kind (`llama/`, `vllm/`, `classifier/`, `embedding/`, `reranker/`), multi-step `pipelines/` (guards, routers, chain-of-thought), and a `modular/` tree showing how to compose one server from shared include fragments. Run any of them with `./run-server.sh --workspace <file>` (`--list` prints them all).

### Workspace format

```yaml
workspace:
  name: toxicity-guard
  models:
    - id: llm
      name: Qwen/Qwen3-0.6B-GGUF
      type: llama_cpp
      llama_cpp:
        path: Qwen3-0.6B-Q8_0.gguf
        n_ctx: 4096
        n_gpu_layers: 999
    - id: toxicity
      name: gravitee-io/distilbert-multilingual-toxicity-classifier
      type: onnx_classifier
      onnx_classifier:
        model_path: model.quant.onnx
        tokenizer_path: tokenizer.json
        classifier_mode: SEQUENCE
  pipelines:
    - id: toxicity-guard-pipeline
      entry: toxicity_guard
      steps:
        - id: toxicity_guard
          type: guard
          next_step: generate
          config:
            model_id: toxicity
            input_field: prompt
            action: reject
            trigger:
              label: toxic
              score: 0.75
        - id: generate
          type: infer
          role: output
          config:
            model_id: llm
            output_field: generate.output
```

A workspace can also pull shared `models:` / `pipelines:` / `templates:` from sibling folders via `includes:`, and declare `remote:` endpoints for client-side / multi-server execution. See the step types, guard/routing/loop semantics, and the full schema in **[ARCHITECTURE.md → How pipelines work](ARCHITECTURE.md#how-pipelines-work)**.

## Production deployment

Host models in **separate Singularitee processes** rather than packing many into one JVM, and compose them with remote / multi-server workspaces (see [ARCHITECTURE.md → Remote & multi-server](ARCHITECTURE.md#remote--multi-server)). Each model then has its own lifecycle, memory, and failure domain.

This matters most on **CUDA / GPU**: the engines (llama.cpp, vLLM, ONNX Runtime) each load their own native CUDA bindings, so co-locating different engines — or several large models — in a single process invites native library/version conflicts and GPU-memory contention. Run **one model (or engine) per process/GPU** and let them talk over gRPC, so you can scale, place, and restart each independently.

## Contributing

Contributions are welcome. **[CONTRIBUTING.md](CONTRIBUTING.md)** covers setting up a dev
environment, running the server and the tests, our commit conventions, and how to open a pull
request. In short:

- Bugs and feature requests go to the central [gravitee-io/issues](https://github.com/gravitee-io/issues/issues) repository — search the archive first
- Branch from `main` as `issue/<issue-id>-my-fix-branch`, and use [Conventional Commits](https://conventionalcommits.org/)
- Include tests, and run `mvn clean install` before opening the PR — CI builds with `-DskipTests`
- The build validates formatting and license headers; `mvn prettier:write license:format` fixes both

## Security

To report a security vulnerability, follow the central Gravitee process described in
**[SECURITY.md](SECURITY.md)**. Please do not open a public issue for security reports.

## License

[Apache License 2.0](LICENSE) — Copyright © 2015 The Gravitee team (http://gravitee.io)
