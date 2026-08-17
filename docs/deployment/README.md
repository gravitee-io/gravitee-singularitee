# Deployment

> Ship the server to production: one CUDA image per engine, mounting a workspace, and one-model-per-process topology.

## Overview
This repository ships **three CUDA image definitions, one per engine** — ONNX/GLiNER, llama.cpp,
and vLLM. This is the one-engine-per-process rule made physical: each engine loads its own native CUDA bindings, so
co-locating them in a single process invites library conflicts and GPU-memory contention. Each
image carries only its own engine's JARs (the `dist-*` Maven profiles), and
`SingulariteeConfiguration` probes the classpath at startup, registering only the engines that are
actually present. Asking a vLLM image for a `llama_cpp` model fails with "no factory for type"
rather than a `NoClassDefFoundError`.

No workspace is baked into the images: mount one and point `GRAVITEE_AI_WORKSPACE_PATH` at it. The
recommended topology is **one model (or engine) per process/GPU**, composed over gRPC with
remote/multi-server workspaces — `prod/server/` and `examples/modular/client-safety-llamacpp.yaml`
are working examples.

| Dockerfile | Engine | Base image | Model types |
| --- | --- | --- | --- |
| `Dockerfile.onnx-cuda` | ONNX Runtime + GLiNER | `nvidia/cuda:*-cudnn-runtime` | `onnx_classifier`, `onnx_embedding`, `onnx_reranker`, `gliner_classifier`, `gliner_ner` |
| `Dockerfile.llamacpp-cuda` | llama.cpp | `nvidia/cuda:*-cudnn-runtime` + prebuilt CUDA libs | `llama_cpp`, `llama_cpp_embedding`, `llama_cpp_reranker` |
| `Dockerfile.vllm-cuda` | vLLM | `vllm/vllm-openai` | `vllm` |

The plain `Dockerfile` (CPU, all engines) is for local development.

## Key types
- `Dockerfile` — CPU image, all engines: `graviteeio/java:25-debian` + the standalone distribution; `EXPOSE 9090`, entrypoint `bin/gravitee.sh`. Local development.
- `Dockerfile.onnx-cuda` — ONNX Runtime + GLiNER on `nvidia/cuda:*-cudnn-runtime` + Temurin 25. Build the distribution with `-Pcuda,dist-onnx` so ONNX Runtime is the GPU build.
- `Dockerfile.llamacpp-cuda` — llama.cpp on the same CUDA base; copies prebuilt llama.cpp CUDA libs from `LLAMA_LIBS_IMAGE`. Distribution built with `-Pcuda,dist-llama`. (Was `Dockerfile.cuda`.)
- `Dockerfile.vllm-cuda` — vLLM on `vllm/vllm-openai`, which already carries CUDA, Python and vLLM; adds the JVM and a venv wired for vLLM4j. Distribution built with `-Pdist-vllm`. Keep `VLLM_IMAGE` in step with `scripts/setup-venv.sh`'s `VLLM_VERSION` — vLLM4j is compiled against a specific vLLM Python API.
- `Dockerfile.llama-cuda` — builder: clones `ggml-org/llama.cpp` at `LLAMACPP_VERSION` (default `b10276`) and builds `libllama.so`/`libggml*.so` with `-DGGML_CUDA=ON` for Volta→Blackwell architectures.
- `docker/cuda/entrypoint.sh` / `cuda-env.sh` — shared by all three images; every engine-specific block is conditional on its payload being present. Sets `GRAVITEE_HOME` and `LD_LIBRARY_PATH`, adds `LLAMA_CPP_LIB_PATH` when the llama.cpp natives are there, and for the vLLM image translates `VLLM4J_VENV` into `-Dvllm4j.venv` on `JAVA_OPTS` (the only thing vLLM4j reads) and preloads `libpython`. Runs `nvidia-smi` diagnostics, then execs `gravitee.sh`.
- `prod/server/server.yaml` — production multi-model server workspace (`pii.yaml` + `gliguard.yaml` via includes); `prod/apim/` holds Gravitee APIM Helm values for the fronting gateway.
- `scripts/setup-venv.sh` — creates the vLLM `uv` venv per backend (`metal` / `cuda` / `cpu`).

## Usage

Each image needs its own distribution build — the flavour decides which engine JARs are included.

**ONNX / GLiNER image**:

```bash
mvn clean install -DskipTests -Pcuda,dist-onnx
docker build -f Dockerfile.onnx-cuda -t singularitee:onnx-cuda .
docker run --rm --gpus all -p 9090:9090 singularitee:onnx-cuda
```

**llama.cpp image** — build the llama.cpp libs once, then the runtime image:

```bash
mvn clean install -DskipTests -Pcuda,dist-llama
docker build -f Dockerfile.llama-cuda -t llama-cpp-cuda:local .   # LLAMACPP_VERSION
docker build -f Dockerfile.llamacpp-cuda --build-arg LLAMA_LIBS_IMAGE=llama-cpp-cuda:local \
  -t singularitee:llamacpp-cuda .

# requires the NVIDIA Container Toolkit
docker run --rm --gpus all -p 9090:9090 singularitee:llamacpp-cuda
```

**vLLM image** — no venv setup needed on the host; the base image already has vLLM:

```bash
mvn clean install -DskipTests -Pdist-vllm
docker build -f Dockerfile.vllm-cuda -t singularitee:vllm-cuda .
docker run --rm --gpus all -p 9090:9090 singularitee:vllm-cuda

# mount a workspace and point the server at it:
docker run --rm --gpus all -p 9090:9090 \
  -v "$PWD/examples:/workspaces:ro" \
  -e GRAVITEE_AI_WORKSPACE_PATH=/workspaces/classifier/guardrails-gliner.yaml \
  -e HF_TOKEN=hf_xxx \
  singularitee:cuda
```

**vLLM venv** (host-side, wired by the Maven backend profiles at `initialize`):

```bash
./scripts/setup-venv.sh -d ~/.venv-gravitee-ai -v 3.12 -b cuda   # metal | cuda | cpu
# at runtime the server reads -Dvllm4j.venv (default ~/.venv-gravitee-ai/.venv,
# overridable at build time via -Dvllm.venv.path or VLLM_VENV_PATH)
```

**Production multi-server topology** — one classifier per process, composed by a client workspace:

```yaml
# a client workspace (abbreviated)
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
      server: pii
```

## Options

### Docker build args
| Arg | Default | Purpose |
| --- | --- | --- |
| `CUDA_VERSION` | `12.9.2` | CUDA base image version (`Dockerfile.onnx-cuda`, `Dockerfile.llamacpp-cuda`, `Dockerfile.llama-cuda`). |
| `UBUNTU_VERSION` | `24.04` | Ubuntu base version. |
| `LLAMA_LIBS_IMAGE` | `llama-cpp-cuda:local` | Image providing the prebuilt llama.cpp CUDA `.so`s. |
| `LLAMACPP_VERSION` | `b10276` | llama.cpp tag to build (must match the bundled llamaj.cpp — b10276 ↔ 2.7.0). |
| `CUDA_ARCHITECTURES` | `70-real;...;121-real;90-virtual` | Target GPU architectures (Volta → Blackwell + PTX fallback). |

### CUDA image env vars
| Var | Default | Purpose |
| --- | --- | --- |
| `GRAVITEE_AI_WORKSPACE_PATH` | — (unset) | Path to the workspace YAML to load; mount it into the container. |
| `LLAMA_CPP_LIB_PATH` | `${GRAVITEEIO_HOME}/native/llama-cuda` | Directory of the CUDA llama.cpp libs. |
| `HF_TOKEN` | — | HuggingFace token for gated model downloads. |
| `JAVA_OPTS` | — | Extra JVM flags / `-D` overrides passed to `gravitee.sh`. |

### vLLM on Apple Silicon (local development only)

`vllm-metal` is a plugin over the CPU core, and two of its defaults are wrong for
this server. The server sets both before the interpreter starts, so a workspace
does not have to know:

| Setting | What the server does | Why |
| --- | --- | --- |
| `VLLM_METAL_USE_PAGED_ATTENTION` | forced to `0`, unless the workspace enables LoRA | The paged runtime loses the `RequestState` for cached requests and answers every decode step with placeholder token id 0: generation looks alive, emits nothing, and runs until the context window fills. LoRA is the exception — that backend serves adapters only from the paged path. |
| `enable_prefix_caching` | defaults to off on Metal | Same runtime, same desync. An explicit `true` in the workspace still wins, which is how you re-test once upstream fixes it. |

Two sizing traps follow from unified memory, both fatal at startup rather than
gradual:

- **`gpu_memory_utilization` is a fraction of TOTAL memory, not free memory.** On a
  38.7 GB machine reporting 23.7 GB available and a 28.1 GB wired limit, the usual
  `0.85` asks for 32.9 GB and cannot succeed.
- **The profiling pass allocates `max_num_batched_tokens` in one batch**, which
  defaults to `max_model_len` when chunked prefill is off. A 131072-token window
  therefore builds a 131072-token batch of activations immediately after the weights
  load, and Metal aborts with
  `kIOGPUCommandBufferCallbackErrorOutOfMemory` before the server ever listens.

`examples/vllm/gpt-oss-20b-mac.yaml` is a worked example: 16384 context (0.75 GiB of
KV against 6.0 at 131072), `max_num_batched_tokens: 2048` with chunked prefill,
`gpu_memory_utilization: 0.5`, `enforce_eager: true`. Expect it to *fit*, not to be
fast — that backend runs without CUDA graphs or fused kernels, and the llama.cpp twin
is several times quicker on the same machine.

## Notes
- **One model per process/GPU.** From ARCHITECTURE.md: the engines (llama.cpp, vLLM, ONNX Runtime) each load their own native CUDA bindings, so co-locating engines or several large models in one JVM invites native library/version conflicts and GPU-memory contention. Host each model in its own Singularitee process and compose them with remote/multi-server workspaces — each gets its own lifecycle, memory, and failure domain. The production client convention: pii on 9100, guardrails on 9101, embedding on 9102, semantic-router on 9103.
- **No workspace ships in the image.** Mount one (`-v $PWD/examples:/workspaces:ro`) and select it with `GRAVITEE_AI_WORKSPACE_PATH`; `examples/classifier/`, `examples/embedding/` and `examples/llama/` each hold ready-made single-model workspaces. Model weights are *not* in the image either — they download from HuggingFace on first boot; persist `models/` (e.g. a Docker volume) to avoid re-downloading.
- **vLLM has its own image** (`Dockerfile.vllm-cuda`, built on `vllm/vllm-openai`), so a host venv is not required to run it in production. `scripts/setup-venv.sh` (vLLM `0.26.0`, Python `3.12`) remains the path for local development and for the build machine.
- **Each image serves only its own engine.** A workspace referencing a model type the image does not carry fails at load with "no factory for type". Compose engines across processes over gRPC, not inside one image.
- **Keep `LLAMACPP_VERSION` in lockstep with the llamaj.cpp dependency** — the FFI wrappers drift across llama.cpp bumps and mismatches surface as runtime `NoSuchMethodError`s. `b10276` pairs with llamaj.cpp `2.7.0` (see `docker/cuda/README.md`).
- **The server binds its ports before models load** — a TCP-level readiness check passes while calls still return `UNAVAILABLE` until the workspace finishes loading (see [Getting Started](../getting-started/README.md)). Whatever orchestrator fronts it, size the readiness delay for model-download time on first boot.
- **`prod/apim/apim-values.yaml` is environment-specific** (AKS non-prod, Traefik, MongoDB) and its header says not to commit changes — treat it as a template for fronting the server with a Gravitee APIM gateway.

## See also
- [Getting Started](../getting-started/README.md) — distribution layout, `gravitee.yml`, env-var overrides, boot order.
- [Workspaces](../workspaces/README.md) — the YAML the images point `GRAVITEE_AI_WORKSPACE_PATH` at.
- [Remote & Multi-Server](../remote-and-multi-server/README.md) — composing per-model processes over gRPC.
- [Observability](../observability/README.md) — Prometheus metrics, GPU monitoring, OpenTelemetry.
- [OpenAI HTTP API](../openai-http-api/README.md) — enabling `http.*` behind a gateway or ingress.
