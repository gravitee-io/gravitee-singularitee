# Deployment

> One container image per engine, a mounted workspace, and one model per process composed over gRPC.

## Overview

The repository ships one CUDA image definition per engine (ONNX Runtime with GLiNER,
llama.cpp, vLLM) plus a CPU image for development. Each engine loads its own native CUDA
libraries, so they are not co-located: each image carries only its engine's jars (the
`dist-*` Maven profiles) and `SingulariteeConfiguration` registers an engine only when its
probe class is on the classpath. A vLLM image asked for a `llama_cpp` model fails with
"no factory for type", not with a `NoClassDefFoundError`.

No workspace is baked into the images by default. Mount one and point
`GRAVITEE_AI_WORKSPACE_PATH` at it. The intended topology is one model (or one engine) per
process and GPU, composed over gRPC by a workspace that declares `remote:` endpoints.

| Dockerfile | Engine | Base image | Distribution profile | Model types |
| --- | --- | --- | --- | --- |
| `Dockerfile` | all, CPU | `graviteeio/java:25-debian` | default | every type; local development |
| `Dockerfile.onnx-cpu` | ONNX Runtime, GLiNER | `graviteeio/java:25-debian` | `-Pdist-onnx` | `onnx_classifier`, `onnx_embedding`, `onnx_reranker`, `gliner_classifier`, `gliner_ner` |
| `Dockerfile.onnx-cuda` | ONNX Runtime, GLiNER | `nvidia/cuda:<v>-cudnn-runtime-ubuntu<v>` | `-Pcuda,dist-onnx` | same as above, CUDA execution provider |
| `Dockerfile.llamacpp-cuda` | llama.cpp | `nvidia/cuda:<v>-cudnn-runtime-ubuntu<v>` plus the libs from `LLAMA_LIBS_IMAGE` | `-Pcuda,dist-llama` | `llama_cpp`, `llama_cpp_embedding`, `llama_cpp_reranker` |
| `Dockerfile.llamacpp-cuda` with `-Pcuda,dist-llama-onnx` | llama.cpp + ONNX Runtime, GLiNER | same image | `-Pcuda,dist-llama-onnx` | llama.cpp types plus `onnx_*`, `gliner_classifier`, `gliner_ner` on the CUDA execution provider (one image for an LLM + guardrails) |
| `Dockerfile.llama-cuda` | builder only | `nvidia/cuda:<v>-devel-ubuntu<v>` | none | compiles llama.cpp with `GGML_CUDA` into `/llama-libs` and gliner4j's `libggml-deberta` plugin into `/llama-libs/plugins` (`GLINER4J_REF`); consumed by the line above |
| `Dockerfile.vllm-cuda` | vLLM | `vllm/vllm-openai:v0.28.0-cu129` | `-Pdist-vllm` | `vllm` |

## Key types

- `docker/cuda/entrypoint.sh`: sets `GRAVITEE_HOME`, sources `cuda-env.sh`, prints `nvidia-smi` diagnostics, falls back to a baked `workspace.yaml` when `GRAVITEE_AI_WORKSPACE_PATH` is unset, then execs `gravitee.sh`.
- `docker/cuda/cuda-env.sh`: shared by the three CUDA images; each block is conditional on its payload. Extends `LD_LIBRARY_PATH`, exports `LLAMA_CPP_LIB_PATH` when the llama.cpp natives are present, and turns `VLLM4J_VENV` into `-Dvllm4j.venv` on `JAVA_OPTS` with `libpython` preloaded.
- `SingulariteeConfiguration`: classpath probes that decide which engine factories register.
- `scripts/setup-venv.sh`: the host-side vLLM virtualenv for development and build machines (`metal`, `cuda`, `cpu`).
- `scripts/gen-dev-certs.sh`: throwaway CA, server and client certificates for the mutual-TLS example.

## Usage

Each image needs a distribution built with the matching profile first.

ONNX and GLiNER:

```bash
mvn clean install -DskipTests -Pcuda,dist-onnx
docker build -f Dockerfile.onnx-cuda -t singularitee:onnx-cuda .
docker run --rm --gpus all -p 9090:9090 \
  -v "$PWD/examples:/workspaces:ro" \
  -e GRAVITEE_AI_WORKSPACE_PATH=/workspaces/classifier/guardrails-gliner.yaml \
  singularitee:onnx-cuda
```

llama.cpp (compile the libraries once, then build the runtime image against them):

```bash
mvn clean install -DskipTests -Pcuda,dist-llama
docker build -f Dockerfile.llama-cuda -t llama-cpp-cuda:local .
# GLiNER's DEBERTA plugin comes from GLINER4J_REF on GitHub; a local GLiNER4j checkout can stand in:
#   --build-context gliner4j=/path/to/GLiNER4j/gliner4j-llamacpp/native
# Always assemble the distribution with the WHOLE reactor under -Pcuda: a partial `-pl` rebuild
# resolves the other modules from ~/.m2, where the profile does not apply, and the CPU
# onnxruntime jar lands next to onnxruntime_gpu (the CPU one wins on the classpath).
docker build -f Dockerfile.llamacpp-cuda --build-arg LLAMA_LIBS_IMAGE=llama-cpp-cuda:local \
  -t singularitee:llamacpp-cuda .
docker run --rm --gpus all -p 9090:9090 \
  -v "$PWD/examples:/workspaces:ro" \
  -e GRAVITEE_AI_WORKSPACE_PATH=/workspaces/llama/qwen3-0.6b.yaml \
  -e HF_TOKEN=hf_xxx \
  singularitee:llamacpp-cuda
```

vLLM (the base image already carries CUDA, Python and vLLM; no host venv):

```bash
mvn clean install -DskipTests -Pdist-vllm
docker build -f Dockerfile.vllm-cuda -t singularitee:vllm-cuda .
docker run --rm --gpus all -p 9090:9090 \
  -v "$PWD/examples:/workspaces:ro" \
  -e GRAVITEE_AI_WORKSPACE_PATH=/workspaces/vllm/qwen3-0.6b.yaml \
  singularitee:vllm-cuda
```

A self-contained vLLM image for a platform without volumes (weights and workspace baked in;
the token travels as a BuildKit secret, never a build arg):

```bash
HF_TOKEN=hf_xxx docker build -f Dockerfile.vllm-cuda \
  --secret id=hf_token,env=HF_TOKEN \
  --build-arg BAKE_MODEL=openai/gpt-oss-20b \
  --build-arg BAKE_WORKSPACE=vllm/gpt-oss-20b.yaml \
  -t singularitee:gpt-oss-20b .
```

Enable the HTTP API and change ports with the usual environment variables
(`GRAVITEE_HTTP_ENABLED=true -p 8080:8080`); see [Configuration](../../reference/configuration.md).

Multi-server composition (one classifier per process, stitched by a client workspace):

```yaml
workspace:
  name: client
  remote:
    servers:
      - id: pii
        host: pii.internal
        port: 9090
        ssl: true
        username: pii
        password: <password>
  models:
    - id: pii
      type: remote_classifier
      server: pii
```

## Options

### Build arguments

| Arg | Default | Dockerfiles | Purpose |
| --- | --- | --- | --- |
| `CUDA_VERSION` | `12.9.2` | `*-cuda` | CUDA base image version. |
| `UBUNTU_VERSION` | `24.04` | `*-cuda` | Ubuntu base release. |
| `TEMURIN_APT_CODENAME` | `noble` | `onnx-cuda`, `llamacpp-cuda` | Adoptium repository codename for the JDK. |
| `LLAMA_LIBS_IMAGE` | `llama-cpp-cuda:local` | `llamacpp-cuda` | Image holding the prebuilt llama.cpp CUDA libraries. |
| `LLAMACPP_VERSION` | `b10276` | `llama-cuda` | llama.cpp tag to compile. Must match the llamaj.cpp binding (`2.7.0`). |
| `CUDA_ARCHITECTURES` | `70-real;...;121-real;90-virtual` | `llama-cuda` | Target GPU architectures. Trim to the cards you deploy for a faster build. |
| `VLLM_IMAGE` | `vllm/vllm-openai:v0.28.0-cu129` | `vllm-cuda` | Base image. Keep its vLLM version equal to `scripts/setup-venv.sh`'s `VLLM_VERSION`. The `cu129` tag runs on driver r525+; the default CUDA 13 tags need r580+. |
| `BAKE_MODEL` | unset | `vllm-cuda` | HuggingFace repo to download into the image. |
| `BAKE_WORKSPACE` | unset | `vllm-cuda` | Path under `examples/` copied to `${GRAVITEEIO_HOME}/workspace.yaml` and used when `GRAVITEE_AI_WORKSPACE_PATH` is unset. |

### Runtime environment

| Variable | Default | Purpose |
| --- | --- | --- |
| `GRAVITEE_AI_WORKSPACE_PATH` | unset (or the baked workspace) | Workspace to load. |
| `GRAVITEE_AI_MODELS_PATH` | `${GRAVITEEIO_HOME}/models` | Model cache. Mount a volume here to keep weights across restarts. |
| `HF_TOKEN` | unset | Gated repos and higher download rate limits. |
| `LLAMA_CPP_LIB_PATH` | `${GRAVITEEIO_HOME}/native/llama-cuda` | Directory llamaj.cpp loads the CUDA natives from (`llamacpp-cuda`). |
| `VLLM4J_VENV` | `${GRAVITEEIO_HOME}/vllm-venv` | Virtualenv `cuda-env.sh` passes as `-Dvllm4j.venv` (`vllm-cuda`). |
| `JAVA_OPTS` | unset | Extra JVM flags and `-D` overrides. |
| `GRAVITEE_*` | | Any `gravitee.yml` key; see [Configuration](../../reference/configuration.md). |

Every image exposes `9090`. Publish `8080` as well when `GRAVITEE_HTTP_ENABLED=true`, and
`18092` only to your metrics network.

### vLLM on Apple Silicon (development only)

`vllm-metal` is a plugin over the CPU core. The server adjusts two of its defaults before
the interpreter starts:

| Setting | Server behaviour | Reason |
| --- | --- | --- |
| `VLLM_METAL_USE_PAGED_ATTENTION` | forced to `0` unless the workspace enables LoRA | The paged runtime loses cached request state and decodes placeholder tokens until the window fills. LoRA adapters are only served from the paged path. |
| `enable_prefix_caching` | off on Metal unless the workspace sets `true` | Same runtime, same desync. |

Two sizing rules follow from unified memory, both fatal at startup:

- `gpu_memory_utilization` is a fraction of total memory, not free memory. On a 38.7 GB machine with a 28.1 GB wired limit, `0.85` asks for 32.9 GB and cannot succeed.
- The profiling pass allocates `max_num_batched_tokens` at once, which defaults to `max_model_len` when chunked prefill is off. A 131072-token window builds a 131072-token batch right after the weights load and Metal aborts with `kIOGPUCommandBufferCallbackErrorOutOfMemory`.

`examples/vllm/gpt-oss-20b-mac.yaml` applies both: 16384 context, `max_num_batched_tokens: 2048`
with chunked prefill, `gpu_memory_utilization: 0.5`, `enforce_eager: true`. It fits; the
llama.cpp twin is several times faster on the same machine.

## Notes

- One model per process and GPU. llama.cpp, vLLM and ONNX Runtime each load their own native CUDA libraries; several engines or several large models in one JVM compete for GPU memory and can conflict on library versions. Give each its own process and compose them with `remote_*` models.
- Weights are not in the images (unless `BAKE_MODEL` is set). They download on first boot into `GRAVITEE_AI_MODELS_PATH`; persist that directory.
- Each image serves only its own engine. A workspace naming another engine's type fails at load with "no factory for type".
- Keep `LLAMACPP_VERSION` and the llamaj.cpp dependency in lockstep (`b10276` with `2.7.0`). The FFM bindings are ABI-specific; a mismatch is a runtime `NoSuchMethodError`. `docker/cuda/README.md` shows how to read the pinned build out of the llamaj.cpp jar.
- Keep `VLLM_IMAGE` and `scripts/setup-venv.sh`'s `VLLM_VERSION` equal (`0.28.0`). vLLM4j is compiled against one vLLM Python API and fails at model load, not at build, when they drift.
- Ports bind before models load. `/health` answers `200` while calls return `UNAVAILABLE` or `503`; make the readiness probe poll `/v1/models` or `ListModels` and size its timeout for a first download.
- The gRPC and HTTP ports bind `0.0.0.0` with auth off. Set `grpc.auth` or `http.auth` (and TLS) before exposing them beyond the pod network; the server logs a warning otherwise.
- Build truststores with `keytool -importcert`; Java reads zero entries from an openssl cert-only PKCS12 bundle.

## See also

- [Configuration](../../reference/configuration.md): every key the images accept through `GRAVITEE_*`.
- [Getting started](../../getting-started/README.md): boot order and the readiness gate.
- [Workspaces](../../workspaces/README.md): the YAML `GRAVITEE_AI_WORKSPACE_PATH` points at.
- [Remote and multi-server](../../guides/remote-and-multi-server/README.md): composing per-model processes, `grpc.client.ssl.*`.
- [Observability](../observability/README.md): the metrics port and GPU gauges.
- [gRPC API](../../api/grpc/README.md) and [HTTP API](../../api/http/README.md): auth and TLS from the caller's side.
