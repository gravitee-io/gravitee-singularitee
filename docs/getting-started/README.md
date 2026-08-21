# Getting started

> From a fresh clone to a first chat completion: prerequisites, `./install.sh`, running a workspace, the first calls, and what to check when something does not answer.

## Overview

Singularitee is a gRPC inference server with an opt-in OpenAI-compatible HTTP API. The Maven
build assembles a self-contained distribution (`bin/`, `config/`, `lib/`, `plugins/`) under
`gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution/`,
launched by `bin/gravitee.sh` or, from an IDE, by the `SingulariteeContainer` main class.

At boot the node binds its ports first, then loads the workspace named by
`ai.workspace.path`, downloading model weights from HuggingFace on first use. Until every
model and pipeline is published, calls answer `UNAVAILABLE` (gRPC) or `503` (HTTP).

llama.cpp is the default backend and the one to develop against: cross-platform, no Python,
set up by `install.sh`. ONNX and GLiNER need nothing beyond the build. vLLM is opt-in and
Linux/CUDA-first; see [vLLM](#vllm) below.

## Key types

- `Bootstrap`: production entry point. Requires `gravitee.home` (`-Dgravitee.home` or `GRAVITEE_HOME`), builds the `lib/ext/` then `lib/` classloader chain and starts the container.
- `SingulariteeContainer`: the Spring container with its own `main()`; what an IDE runs.
- `SingulariteeNode`: registers components in boot order: monitoring services, `GrpcServerComponent`, `HttpApiServerComponent`, `WorkspaceLoaderComponent`.
- `WorkspaceLoaderComponent`: parses the workspace, publishes models and pipelines, then flips `ReadinessState`.
- `HuggingFaceModelDownloader` and the `*ModelResolver` classes: local file, then cache hit, then download.

## Usage

### 1. Prerequisites

- Java 25 (`.java-version` pins `25.0.4`). The FFM-based engines need `--enable-preview --enable-native-access=ALL-UNNAMED`; `gravitee.sh` adds both.
- Maven.
- Optional: [go-task](https://taskfile.dev) (`brew install go-task`) and [uv](https://docs.astral.sh/uv/) for the `task` shortcuts and demo scripts.
- A supported host: macOS on Apple Silicon or Linux on x86_64, the two llamaj.cpp ships bindings for.

### 2. `./install.sh`

```bash
./install.sh            # prereq check, llama.cpp natives, mvn build, then run
```

The script downloads the llama.cpp native libraries into `~/.llama.cpp` (they are not in the
jar), builds the distribution, prints client configuration for any OpenAI-compatible client,
and starts `examples/llama/gpt-oss-20b.yaml` on port 8080. Flags: `--port`, `--skip-build`,
`--no-run`, `--llama-version bNNNN`. Export `HF_TOKEN` first to avoid anonymous download
rate limits and to read gated repos.

### 3. Manual build

```bash
mvn clean install -DskipTests
```

The build enforces formatting and Apache-2.0 license headers before compiling. A failure
reading `Incorrectly formatted file` or a license-check error is fixed with
`mvn prettier:write license:format` (add `-pl <module>` to scope it). YAML and shell files
carry the header too.

### 4. Run a workspace

```bash
./run-server.sh --list                                      # every runnable workspace
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml  # the reference model, small and fast
./run-server.sh --port 8081 --workspace <file>
./run-server.sh --debug                                     # TRACE-log rendered prompts
```

`run-server.sh` enables the HTTP API, points the model cache at
`~/.cache/gravitee-singularitee/models` and execs `bin/gravitee.sh`. `--debug` logs the prompt
after template rendering, which is what the model actually received.

Without the script:

```bash
GRAVITEE_HOME=/path/to/distribution \
GRAVITEE_HTTP_ENABLED=true \
GRAVITEE_AI_WORKSPACE_PATH=$PWD/examples/llama/qwen3-0.6b.yaml \
./bin/gravitee.sh
```

Every example generation workspace publishes one model under the id `llm` and one pipeline
called `agent`, so the calls below work unchanged when you switch files.

### 5. First calls

Wait for the catalogue to be non-empty, then call the pipeline:

```bash
curl -s localhost:8080/v1/models | jq '.data[].id'

curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"agent","messages":[{"role":"user","content":"Say hello in five words."}]}' | jq

curl -sN localhost:8080/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"agent","stream":true,"messages":[{"role":"user","content":"Count to five."}]}'
```

Over gRPC (no reflection; point grpcurl at the protos):

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/model.proto \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeModelService/ListModels

grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"agent","prompt":"Say hello."}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

Or with the scripts:

```bash
uv run --with openai examples/scripts/openai_test.py       # or: task chat
uv run --with requests examples/scripts/classify_test.py   # or: task classify
```

### 6. `task` shortcuts

`task` alone lists every target. Server tasks run in the foreground; demo tasks need a server
already running in another shell.

| Task | Does |
| --- | --- |
| `task build`, `task test`, `task test:examples` | Build, full suite, load every `examples/**.yaml` through the loader. |
| `task run -- <file>`, `task run:debug -- <file>` | Any workspace, optionally with TRACE prompts. |
| `task run:qwen`, `task run:gpt-oss`, `task run:vision`, `task run:audio` | Generation models (`VLLM=1` picks the `examples/vllm/` twin where one exists). |
| `task run:pii`, `task run:guardrails`, `task run:intent`, `task run:embedding`, `task run:reranker` | Single classifier, embedding or reranker workspaces. |
| `task run:guard`, `task run:tool-router`, `task run:gliner-router`, `task run:embedding-router`, `task run:cot` | Pipeline examples. |
| `task certs`, `task run:mtls-safety`, `task run:mtls-llm`, `task mtls-check` | The mutual-TLS server pair. |
| `task chat`, `task classify`, `task vision`, `task audio`, `task models` | Demos against the running server. |

`PORT=8081 task run:qwen` changes the HTTP port for both server and demo tasks.

### 7. IntelliJ

`.run/` holds committed run configurations. Main class
`io.gravitee.singularitee.standalone.SingulariteeContainer`, VM options
`-Dgravitee.home=<path to target/distribution> --enable-preview --enable-native-access=ALL-UNNAMED`,
plus `-Dai.workspace.path=<file>` and `-Dhttp.enabled=true` as needed.

### vLLM

vLLM runs from a Python virtualenv that the JVM loads CPython out of. It is skipped by
default (`vllm.setupVenv.skip=true`).

```bash
./scripts/setup-venv.sh -b metal        # or -b cuda | -b cpu; -d <dir>, -v 3.12
./run-server.sh --workspace examples/vllm/gpt-oss-20b-mac.yaml
```

`run-server.sh` detects a vLLM workspace, finds `~/.venv-gravitee-ai/.venv` (or `--venv` /
`$VLLM_VENV`) and passes `-Dvllm4j.venv`, the only thing vLLM4j reads. On Apple Silicon use
the `*-mac.yaml` files: the others are sized for an 80 GB card.

## Options

The keys needed for a first run. The full list, with environment variable forms, is in
[Configuration](../reference/configuration.md).

| Key | Default | Purpose |
| --- | --- | --- |
| `ai.workspace.path` | unset | Workspace loaded at boot. Unset starts an empty server. |
| `ai.models.path` | `${gravitee.home}/models` | Model cache. `run-server.sh` overrides it to `~/.cache/gravitee-singularitee/models` so `mvn clean` leaves weights alone. |
| `ai.huggingface.token` | `$HF_TOKEN` | Gated repos and higher download rate limits. |
| `grpc.port` | `9090` | Primary API. |
| `http.enabled` | `false` | The OpenAI-compatible API is opt-in. |
| `http.port` | `8080` | HTTP API port. |
| `services.core.http.port` | `18092` | Management API: `/_node/metrics/prometheus`, basic auth `admin:adminadmin`. |

Every key accepts `-D<key>=<value>` or a `GRAVITEE_`-prefixed environment variable
(`grpc.port` becomes `GRAVITEE_GRPC_PORT`).

## Notes

**Boot order and readiness.** Ports bind before the workspace loads. `GET /health` on either
port answers `200` immediately while service calls return `UNAVAILABLE` / `503` ("Model server
is still loading"). A TCP probe is not a readiness probe; poll `/v1/models` (HTTP) or
`ListModels` (gRPC) until the catalogue is populated. Size readiness timeouts for the first
download of a multi-GB model.

**Where weights go.** The cache is `ai.models.path`. The shipped default sits under
`target/`, which is why `run-server.sh` and `install.sh` move it to
`~/.cache/gravitee-singularitee/models`. Persist that directory in containers.

### Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Server is healthy but `/v1/models` returns `[]` | A model failed to load. `WorkspaceLoaderComponent` logs a WARN and continues; startup does not fail. | `grep "failed to load" <log>`; check the HuggingFace repo name and file `path`. |
| Calls return `UNAVAILABLE` or `503 Model server is still loading` | The workspace is still loading (or still downloading). | Wait for `Models loaded` in the log; poll `/v1/models`. |
| Out of memory at model load | KV cache is `n_ctx x n_seq_max` per llama.cpp model. | Lower `n_seq_max` before lowering `n_ctx` or changing the quantisation. |
| Old behaviour after a code change; ONNX fails on macOS after a `-Pcuda` build | `run-server.sh` launches whatever is in `target/distribution`. | Rebuild with the default profile. |
| vLLM workspace fails with `Cannot locate a .venv directory` | `-Dvllm4j.venv` is not set; a venv on `PATH` is not enough. | `./scripts/setup-venv.sh -b <backend>`, or `--venv <dir>`. |
| vLLM on Apple Silicon aborts with `kIOGPUCommandBufferCallbackErrorOutOfMemory` | `gpu_memory_utilization` is a share of total unified memory and the profiling pass allocates a full batch. | Use `examples/vllm/*-mac.yaml`, or shrink `max_model_len`, `max_num_batched_tokens` and `gpu_memory_utilization`. |
| `NoSuchMethodError` from the llama.cpp binding | The natives in `~/.llama.cpp` do not match the llamaj.cpp release. | Rerun `./install.sh` (it re-pins `b10276`). |
| Bare model id returns channel markers in `content` | The raw model bypasses the pipeline's `tags:`. | Call the pipeline id (`agent`), not the model id. |
| A `client-*.yaml` workspace fails fast at start | Its `remote:` endpoints are not up. | Start the `server-*.yaml` first, then the client on other ports (`GRAVITEE_GRPC_PORT=9190 ./run-server.sh --port 8180 --workspace <client>`). |
| Build fails before compiling with `Incorrectly formatted file` or a license error | Formatting and header checks run first. | `mvn prettier:write license:format -pl <module>`. |

## See also

- [Concepts](../concepts/README.md): models, pipelines, templates, engines.
- [Workspaces](../workspaces/README.md): the YAML behind `ai.workspace.path`.
- [Configuration](../reference/configuration.md): every key and environment variable.
- [HTTP API](../api/http/README.md) and [gRPC API](../api/grpc/README.md): the endpoints called above.
- [examples/](../../examples/README.md): every runnable workspace.
- [Deployment](../operations/deployment/README.md): container images.
