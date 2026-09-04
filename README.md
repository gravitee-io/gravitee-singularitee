<p align="center">
  <img src="images/logo.svg" alt="Gravitee - Singularitee" width="500">
</p>

<p align="center">
  <a href="https://circleci.com/gh/gravitee-io/gravitee-singularitee"><img src="https://img.shields.io/circleci/build/github/gravitee-io/gravitee-singularitee/main?style=flat-square&color=EE7B2E&label=build" alt="CircleCI"></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-1E90FF?style=flat-square" alt="License"></a>
  <a href="https://community.gravitee.io"><img src="https://img.shields.io/badge/community-forum-FFFFFF?style=flat-square&labelColor=555555" alt="Community"></a>
</p>

**Gravitee's inference server.**

Singularitee hosts LLMs, classifiers, embedders and rerankers in a process of its own and runs multi-step pipelines over them. Whatever calls it (a Gravitee gateway, an OpenAI SDK, `curl`) stays lightweight; Singularitee owns the GPU, the native libraries and the model lifecycle. It is built on [gravitee-node](https://github.com/gravitee-io/gravitee-node) with Vert.x 5 and RxJava 3.

## Why the name

A play on Gravitee. Models are heavy, and a singularity is what gravity produces at its extreme: all that mass collapsed into one point. Everything the gateway cannot carry (the GPU, the native libraries, the weights) is pulled out of it and concentrated into one process. Gravitee attracts; Singularitee is where it all ends up.

## What it does

A workspace YAML declares the models and pipelines to publish. Weights download from HuggingFace on first start, then everything is served with streaming as a first-class primitive.

| Capability | Notes |
| --- | --- |
| Text generation | Streamed token by token, from a model or from a pipeline. |
| Pipelines | A DAG of steps over those models: guards, classifiers, routers, loops, tool calling, sub-pipelines. |
| Classification | ONNX sequence and token classifiers, GLiNER zero-shot, regex, composite. |
| Embeddings and reranking | Vectors, cross-encoder reranking, similarity. |
| Discovery | List and inspect what a server publishes. |

Two fronts serve the same registries and engines, so a capability behaves identically on either:

- **gRPC** (default, port 9090), the primary API: [gRPC API](./docs/api/grpc/README.md)
- **HTTP** (opt-in, port 8080), OpenAI-compatible: [HTTP API](./docs/api/http/README.md), schemas in [openapi/](./openapi/README.md)

### Engines

| Engine | Backend | Use case |
| --- | --- | --- |
| llama.cpp | [llamaj.cpp](https://github.com/gravitee-io/llamaj.cpp) | GGUF models on CPU, Metal or CUDA. No Python. The default. |
| vLLM | [vLLM4j](https://github.com/gravitee-io/vLLM4j) | HuggingFace Transformers checkpoints through vLLM. CUDA first. |
| ONNX | ONNX Runtime | Classifiers, embedders and rerankers. CPU with optional GPU. |
| GLiNER | [gliner4j](https://github.com/gravitee-io/gliner4j) | Zero-shot NER and classification. |

## Documentation

Everything lives under [`docs/`](./docs/README.md). Read in this order:

1. [Getting Started](./docs/getting-started/README.md): build, run, first calls.
2. [Concepts](./docs/concepts/README.md): workspaces, models, pipelines, templates, engines.
3. [Workspaces](./docs/workspaces/README.md): the YAML format.

Then, by need:

- **Reference**: [model types](./docs/reference/models/README.md), [step types](./docs/reference/steps/README.md), [templates](./docs/reference/templates/README.md), [context fields](./docs/reference/context-fields.md), [configuration](./docs/reference/configuration.md)
- **APIs**: [overview and conventions](./docs/api/README.md), [gRPC](./docs/api/grpc/README.md), [HTTP](./docs/api/http/README.md), [Java client](./docs/api/java-client/README.md), [OpenAPI specs](./openapi/README.md)
- **Guides**: [text generation](./docs/guides/text-generation/README.md), [classification](./docs/guides/classification/README.md), [embeddings and reranking](./docs/guides/embeddings-and-reranking/README.md), [guards and redaction](./docs/guides/guards-and-redaction/README.md), [routing](./docs/guides/routing/README.md), [loops and chain-of-thought](./docs/guides/loops-and-cot/README.md), [tool calling](./docs/guides/tool-calling/README.md), [todos](./docs/guides/todos/README.md), [sub-pipelines](./docs/guides/sub-pipelines/README.md), [multimodal](./docs/guides/multimodal/README.md), [remote and multi-server](./docs/guides/remote-and-multi-server/README.md)
- **Operations**: [deployment](./docs/operations/deployment/README.md), [observability](./docs/operations/observability/README.md), [validated models](./docs/operations/models/README.md)
- **Internals**: [Architecture](./docs/architecture/README.md)

## Quick start

From a fresh clone, with Java 25 and Maven:

```bash
./install.sh
```

This checks prerequisites, downloads the llama.cpp native libraries into `~/.llama.cpp` (they are not bundled in the jar), builds the distribution and starts the server with the HTTP API on port 8080. From then on:

```bash
./run-server.sh --list                                          # every runnable workspace
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml      # small and fast
./run-server.sh --debug                                         # TRACE-log rendered prompts
```

Weights download on first start into `~/.cache/gravitee-singularitee/models`. The port binds immediately; calls answer "Model server is still loading" until the workspace is loaded, so poll `/v1/models` rather than the TCP port.

## Calling it

```bash
curl -s localhost:8080/v1/models | jq

curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"agent","messages":[{"role":"user","content":"Say hi in 3 words"}]}' | jq
```

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="sk-local")
client.chat.completions.create(model="agent", messages=[{"role": "user", "content": "hi"}], stream=True)
```

`model` takes a model id or a pipeline id. Beyond the standard routes the server adds `/v1/classify`, `/v1/rerank` and `/v1/similarity`. Over gRPC, use `SingulariteeClient` ([Java client](./docs/api/java-client/README.md)) or `grpcurl` ([gRPC API](./docs/api/grpc/README.md)).

Smoke tests need only [uv](https://docs.astral.sh/uv/):

```bash
BASE_URL=http://localhost:8080/v1 uv run --with openai examples/scripts/openai_test.py
BASE_URL=http://localhost:8080/v1 uv run --with requests examples/scripts/classify_test.py
```

## Configuration

Every key in `gravitee.yml` also accepts a `GRAVITEE_`-prefixed environment variable or a `-D` system property (`grpc.port` becomes `GRAVITEE_GRPC_PORT`). The ones needed to start:

| Key | Default | Purpose |
| --- | --- | --- |
| `grpc.port` | `9090` | The primary API. |
| `http.enabled` / `http.port` | `false` / `8080` | The OpenAI-compatible HTTP API, opt-in. |
| `ai.workspace.path` | unset | Workspace loaded at startup. Unset starts an empty server. |
| `ai.huggingface.token` | `$HF_TOKEN` | Gated repositories. |

The full list, with TLS, auth, the model cache, streaming back-pressure, the management port and OpenTelemetry, is in [Configuration](./docs/reference/configuration.md).

## Workspaces

A workspace declares what to publish. Models carry a stable logical id, so the same pipeline runs against any backend bound to that id.

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

Ready-made workspaces live in [`examples/`](./examples/README.md): one folder per model family, multi-step `pipelines/`, and a `modular/` tree composing servers from shared include fragments. Run any with `./run-server.sh --workspace <file>`.

## Production

Host one model (or one engine) per Singularitee process and compose them over gRPC with remote workspaces. Each engine loads its own native libraries, so co-locating engines or several large models in one JVM invites library conflicts and GPU-memory contention. See [Deployment](./docs/operations/deployment/README.md) and [Remote and multi-server](./docs/guides/remote-and-multi-server/README.md).

## Traces

Turn on OpenTelemetry (`services.opentelemetry.enabled`) and every request exports a span tree over OTLP: a pipeline `CHAIN` nesting each step, `LLM` steps carrying the model, token counts, sampling parameters and time-to-first-token, and guard/gate steps a `GUARDRAIL` span. Point it at Jaeger (`GRAVITEE_SERVICES_OPENTELEMETRY_ENABLED=true`, UI on `:16686`) to see the full tree and every attribute.

When a generation captured log-probabilities, its `infer` step also carries a family of free **confidence signals** on the span (and in the pipeline context as `<step>.*`): the mean `perplexity`, the top-1 vs top-2 token `min_margin`, per-token entropy, robust peaks and more. They fall out of the one generation the model already ran, so they cost no extra inference, and a downstream step can calibrate the one it wants against resolved outcomes. Which summary tracks correctness, if any, depends on the model and the task, so measure before trusting one. See [Observability](./docs/operations/observability/README.md) and [Context fields](./docs/reference/context-fields.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: issues go to [gravitee-io/issues](https://github.com/gravitee-io/issues/issues); branch from `main` as `issue/<id>-<name>`; use [Conventional Commits](https://conventionalcommits.org/); run `mvn clean install` before opening a PR. The build enforces formatting and license headers; `mvn prettier:write license:format` fixes both.

## Security

Report vulnerabilities through the process in [SECURITY.md](SECURITY.md), not through a public issue.

## License

[Apache License 2.0](LICENSE). Copyright 2015 The Gravitee team (http://gravitee.io)
