# Singularitee — NVIDIA CUDA image

A generic, self-contained GPU image built entirely inside Docker (no host
toolchain, no pre-built distribution). It accelerates:

| Backend            | How                                                                 |
|--------------------|---------------------------------------------------------------------|
| **llama.cpp**      | compiled from source with `GGML_CUDA=ON` (→ `libggml-cuda.so`)      |
| **GLiNER / ONNX**  | distribution built with `-Pcuda` → `onnxruntime_gpu` (CUDA EP)      |

> vLLM is intentionally **not** included in this image.

## How it works

The slow part — the from-source CUDA compile of llama.cpp — lives in its own
image so it's built **once** and reused across commits:

- **`../../Dockerfile.llama-cuda`** — clones and compiles llama.cpp on
  `nvidia/cuda:*-devel`, producing the dynamically-loaded backend set
  (`libllama.so`, `libggml*.so`, `libggml-cpu-*.so`, **`libggml-cuda.so`**) under
  `/llama-libs`. Tagged `llama-cpp-cuda` with a
  tag keyed on the llama.cpp / CUDA / Ubuntu versions.
- **`../../Dockerfile.llamacpp-cuda`** — the server image. Two stages:
  1. **llama-build** — `FROM ${LLAMA_LIBS_IMAGE}`, i.e. the prebuilt image above.
     No compile; just makes `/llama-libs` available to copy.
  2. **runtime** — `nvidia/cuda:*-cudnn-runtime` + JDK 25. Copies the pre-built
     `-Pcuda` distribution (built separately by Maven; `-Pcuda` swaps the ONNX
     Runtime backend to `onnxruntime_gpu`) and wires the CUDA libraries in *ahead
     of* the JAR's bundled CPU-only libraries.

### Build flow

`Dockerfile.llama-cuda` compiles the llama.cpp CUDA libraries once; `Dockerfile.llamacpp-cuda`
then consumes them via `--build-arg LLAMA_LIBS_IMAGE=<tag>` — fast, no recompile. Tag the
prebuilt image on the version `ARG` defaults (`LLAMACPP_VERSION`, `CUDA_VERSION`,
`UBUNTU_VERSION`) so bumping any of them yields a fresh prebuild rather than a stale reuse.

### The wiring (sourced before launch)

`llamaj.cpp`'s loader checks the `LLAMA_CPP_LIB_PATH` environment variable first;
when set it loads that directory's `.so` set (registering the CUDA backend via
`ggml_backend_load_all_from_path`) instead of extracting the CPU-only libraries
bundled in the jar. `bin/cuda-entrypoint.sh` **sources** `bin/cuda-env.sh` to set
`LLAMA_CPP_LIB_PATH` and `LD_LIBRARY_PATH` (CUDA + cuDNN) *before* exec'ing
`bin/gravitee.sh`, so the GPU backends are in place before any model loads.

## Version pinning (important)

llama.cpp is compiled at `--build-arg LLAMACPP_VERSION` (default `b10276`). This
**must** match the build the bundled `llamaj.cpp` JNI binding targets, or the Java
FFM symbol lookups fail at runtime. To find the right value for a given
`gravitee-inference` version, inspect the resolved jar's SONAME:

```bash
unzip -l ~/.m2/repository/io/gravitee/llama/cpp/llamaj.cpp/<ver>/llamaj.cpp-<ver>.jar \
  | grep -oE 'libllama\.so\.0\.0\.[0-9]+'
# libllama.so.0.0.10276  ->  LLAMACPP_VERSION=b10276
```

(`llamaj.cpp 2.7.0` → `b10276`.)

## Build

`Dockerfile.llamacpp-cuda` consumes the prebuilt llama.cpp libraries via `LLAMA_LIBS_IMAGE`,
so build the base image first:

```bash
# 1. Build the prebuilt llama.cpp CUDA libraries once.
#    Fast, single GPU arch (e.g. Turing / RTX 20xx = 75) for local work:
DOCKER_BUILDKIT=1 docker build -f Dockerfile.llama-cuda \
  --build-arg CUDA_ARCHITECTURES=75 -t llama-cpp-cuda:local .

# 2. Build the server image against it (no recompile, fast):
DOCKER_BUILDKIT=1 docker build -f Dockerfile.llamacpp-cuda \
  --build-arg LLAMA_LIBS_IMAGE=llama-cpp-cuda:local -t singularitee:cuda .
```

Version defaults are the `ARG` defaults in the Dockerfiles — the single source of
truth, which CI reads (`.circleci/config.yml` → `compute-llama-image`) to key the
prebuilt image tag:

| build-arg            | Dockerfile        | default                              | notes                                       |
|----------------------|-------------------|--------------------------------------|---------------------------------------------|
| `LLAMA_LIBS_IMAGE`   | `Dockerfile.llamacpp-cuda` | `llama-cpp-cuda:local`               | prebuilt llama.cpp image to copy `/llama-libs` from |
| `CUDA_VERSION`       | both              | `12.9.2`                             | CUDA 12.x (satisfies ONNX Runtime); minor-version compat runs on 12.0+ drivers |
| `UBUNTU_VERSION`     | both              | `24.04`                              |                                             |
| `LLAMACPP_VERSION`   | `Dockerfile.llama-cuda` | `b10276`                        | must match the bundled `llamaj.cpp` ABI     |
| `CUDA_ARCHITECTURES` | `Dockerfile.llama-cuda` | `70-real;75-real;80-real;86-real;89-real;90-real;100-real;103-real;120-real;121-real;90-virtual` | Volta→Blackwell + PTX fallback. One nvcc pass per arch — trim to the GPU(s) you deploy (e.g. `89-real;90-virtual` for Ada) for much faster builds |

## Run

Requires the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)
on the host.

```bash
docker run --rm --gpus all -p 9090:9090 singularitee:cuda
```

The startup log prints the detected GPU and confirms the CUDA backend, e.g.:

```
[cuda-entrypoint] llama.cpp CUDA backend present: .../libggml-cuda.so ...
GPU 0: NVIDIA ...
```
