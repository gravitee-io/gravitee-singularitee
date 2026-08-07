#!/usr/bin/env bash
#
# Copyright © 2015 The Gravitee team (http://gravitee.io)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Run Singularitee against any workspace under examples/.
#
# Usage:
#   ./run-server.sh [--workspace FILE] [--port PORT] [--venv DIR] [--debug] [--list]
#
#   --workspace  Workspace YAML, relative to the repo or absolute.
#                Default: examples/llama/qwen3-0.6b.yaml
#   --port       HTTP port (default 8080)
#   --venv       Python venv holding vLLM. Only needed for vllm workspaces, and
#                auto-detected from $VLLM_VENV or ~/.venv-gravitee-ai/.venv —
#                pass this to point somewhere else. See scripts/setup-venv.sh.
#   --debug      TRACE-log every rendered prompt: you see EXACTLY what clients
#                (pi, OpenCode, curl, ...) send — system prompt, history, tools —
#                as the model receives it after template rendering.
#   --list       Print every runnable example workspace and exit.
#
# Every file under examples/ is a complete, runnable workspace. The generation
# models all expose one pipeline called 'agent', so client config and curls carry
# over unchanged when you switch between them:
#
#   ./run-server.sh --workspace examples/llama/gpt-oss-20b.yaml     # a bigger LLM
#   ./run-server.sh --workspace examples/pipelines/guard.yaml       # PII + toxicity guards
#   ./run-server.sh --workspace examples/pipelines/tool-router.yaml # tool shortlisting
#   ./run-server.sh --workspace examples/classifier/pii-bert.yaml   # classifier only
#   ./run-server.sh --workspace examples/embedding/bge-m3.yaml      # embeddings only
#
# Model weights download from HuggingFace on first start (into the shared cache
# below) — the first run of a new workspace is slow, later ones are not.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

PORT=8080
DEBUG=false
WORKSPACE="$REPO_ROOT/examples/llama/qwen3-0.6b.yaml"

list_examples() {
  echo "Runnable example workspaces (examples/<folder>/<file>.yaml):"
  for dir in llama vllm classifier embedding reranker pipelines modular; do
    [[ -d "$REPO_ROOT/examples/$dir" ]] || continue
    echo
    case "$dir" in
      llama)      echo "  llama/       llama.cpp GGUF models — cross-platform, the default backend" ;;
      vllm)       echo "  vllm/        vLLM models — Linux/CUDA only, venv auto-detected (work in progress)" ;;
      classifier) echo "  classifier/  PII / toxicity / guardrails / intent classifiers" ;;
      embedding)  echo "  embedding/   embedding models for retrieval and KNN routing" ;;
      reranker)   echo "  reranker/    cross-encoder rerankers" ;;
      pipelines)  echo "  pipelines/   multi-step pipelines (guards, routers, chain-of-thought)" ;;
      modular)    echo "  modular/     includes-based composition: server-*.yaml / client-*.yaml" ;;
    esac
    # Only the top level of modular/ is runnable — models/ and pipelines/ under it
    # are fragments that exist to be included, not loaded on their own.
    find "$REPO_ROOT/examples/$dir" -maxdepth 1 -name '*.yaml' | sort |
      sed "s|$REPO_ROOT/|    |"
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)     DEBUG=true; shift ;;
    --port)      PORT="$2"; shift 2 ;;
    --workspace) WORKSPACE="$2"; shift 2 ;;
    --venv)      VLLM_VENV="$2"; shift 2 ;;
    --list)      list_examples; exit 0 ;;
    -h|--help)   sed -n '19,45p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [[ ! -f "$WORKSPACE" ]]; then
  echo "Workspace not found: $WORKSPACE" >&2
  echo >&2
  list_examples >&2
  exit 1
fi
# Relative paths are fine on the command line, but the server resolves this from
# its own working directory — hand it an absolute path.
WORKSPACE="$(cd "$(dirname "$WORKSPACE")" && pwd)/$(basename "$WORKSPACE")"

# Fragments under modular/models|pipelines|templates declare no entry point of
# their own: loading one directly publishes a model with no pipeline, which looks
# like a silent no-op rather than an error. Catch it here.
case "$WORKSPACE" in
  */examples/modular/models/*|*/examples/modular/pipelines/*|*/examples/modular/templates/*)
    echo "That file is an include FRAGMENT, not a runnable workspace." >&2
    echo "Run one of the examples/modular/server-*.yaml files that includes it." >&2
    exit 1 ;;
esac

DIST="$REPO_ROOT/gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution"
[[ -x "$DIST/bin/gravitee.sh" ]] || { echo "Distribution not built — run ./install.sh first." >&2; exit 1; }

# gravitee-node re-loads logback programmatically from $GRAVITEE_HOME/config/logback.xml,
# so a -Dlogback.configurationFile override does not stick — toggle the dist file itself
# (a build artifact, regenerated on rebuild).
LOGBACK="$DIST/config/logback.xml"
sed_i() { if [[ "$(uname -s)" == "Darwin" ]]; then sed -i '' "$@"; else sed -i "$@"; fi; }
for pkg in pipeline inference; do
  if [[ "$DEBUG" == true ]]; then
    sed_i "s|<logger name=\"io.gravitee.singularitee.$pkg\" level=\"INFO\"/>|<logger name=\"io.gravitee.singularitee.$pkg\" level=\"TRACE\"/>|" "$LOGBACK"
  else
    sed_i "s|<logger name=\"io.gravitee.singularitee.$pkg\" level=\"TRACE\"/>|<logger name=\"io.gravitee.singularitee.$pkg\" level=\"INFO\"/>|" "$LOGBACK"
  fi
done
[[ "$DEBUG" == true ]] && echo ">> DEBUG: rendered prompts + streamed tokens are TRACE-logged"

echo ">> Workspace: ${WORKSPACE#"$REPO_ROOT"/}"
echo ">> OpenAI-compatible API on http://localhost:$PORT/v1"

# On Linux the ggml backends are dlopen'd by name from the same directory rather
# than through an rpath, so the loader needs it on the search path. Harmless on
# macOS, where the dylibs carry install names.
NATIVE_DIR="${NATIVE_DIR:-$HOME/.llama.cpp}"

# vLLM runs from a Python venv that the JVM loads CPython out of, and vLLM4j
# finds it ONLY through -Dvllm4j.venv. Without this a vllm workspace fails at
# model load with "Cannot locate a .venv directory", which reads like a bug in
# the workspace rather than a missing flag.
#
# Detected from the workspace so `--workspace examples/vllm/...` just works;
# --venv or $VLLM_VENV override the location.
JAVA_OPTS="${JAVA_OPTS:-}"
# Three ways a workspace can be a vLLM one: it declares the type directly, it
# lives in examples/vllm/, or — the case that is easy to miss — it is a modular
# server whose "type: vllm" sits in an included fragment (includes.models:
# "vllm/llm-*.yaml") rather than in the file itself.
if grep -qE '^[[:space:]]*type:[[:space:]]*vllm[[:space:]]*$' "$WORKSPACE" 2>/dev/null \
   || grep -qE '^[[:space:]]*-[[:space:]]*vllm/' "$WORKSPACE" 2>/dev/null \
   || [[ "$WORKSPACE" == */examples/vllm/* ]]; then
  VLLM_VENV="${VLLM_VENV:-$HOME/.venv-gravitee-ai/.venv}"
  if [[ ! -d "$VLLM_VENV" ]]; then
    echo "This workspace needs vLLM, but no venv was found at: $VLLM_VENV" >&2
    echo "Create one with:  ./scripts/setup-venv.sh -b cuda   (or -b metal / -b cpu)" >&2
    echo "Or point at an existing one:  --venv /path/to/.venv" >&2
    exit 1
  fi
  JAVA_OPTS="$JAVA_OPTS -Dvllm4j.venv=$VLLM_VENV"
  echo ">> vLLM venv: $VLLM_VENV"

  # The JVM opens libpython through FFM with RTLD_LOCAL, which hides CPython's
  # symbols from every extension module dlopen'd afterwards. torch is the first
  # to need them and dies with:
  #   ImportError: .../libtorch_python.so: undefined symbol: PyByteArray_Type
  # Preloading libpython forces RTLD_GLOBAL. docker/cuda/cuda-env.sh does the
  # same for the container path; this is the host equivalent.
  # `|| true` on every lookup: under `set -euo pipefail` a no-match `ls` fails the
  # pipeline and kills the script outright — which is precisely the venv layout
  # the fallback below exists to handle.
  LIBPYTHON="$(ls "$VLLM_VENV"/lib/libpython3*.so* 2>/dev/null | head -1 || true)"
  if [[ -z "$LIBPYTHON" ]]; then
    # A uv venv carries no libpython of its own — it lives with the interpreter
    # that pyvenv.cfg's `home` points at (which is <prefix>/bin).
    PY_HOME="$(sed -n 's/^home[[:space:]]*=[[:space:]]*//p' "$VLLM_VENV/pyvenv.cfg" 2>/dev/null | head -1 || true)"
    if [[ -n "$PY_HOME" ]]; then
      LIBPYTHON="$(ls "${PY_HOME%/bin}"/lib/libpython3*.so* 2>/dev/null | head -1 || true)"
    fi
  fi

  # libjsig ahead of libpython, and not optional: apache-tvm-ffi (via xgrammar,
  # which vLLM imports) hijacks SIGSEGV from a library constructor. HotSpot uses
  # SIGSEGV for implicit null checks and safepoint polls and recovers from them
  # in its own handler; once tvm-ffi replaces it, the next routine JVM segfault
  # prints "!!!!!!! Segfault encountered !!!!!!!" and re-raises with SIG_DFL,
  # killing the process with exit 139 and no hs_err_pid. libjsig interposes
  # signal()/sigaction() so such handlers chain behind the JVM's instead.
  LIBJSIG="$(ls "${JAVA_HOME:-/nonexistent}"/lib/libjsig.so 2>/dev/null | head -1 || true)"
  if [[ -z "$LIBJSIG" ]]; then
    LIBJSIG="$(ls "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"/lib/libjsig.so 2>/dev/null | head -1 || true)"
  fi
  if [[ -n "$LIBPYTHON" ]]; then
    export LD_PRELOAD="$LIBPYTHON${LD_PRELOAD:+:$LD_PRELOAD}"
    echo ">> libpython: $LIBPYTHON (preloaded)"
  else
    echo ">> WARNING: no libpython found for $VLLM_VENV — the model load will fail" >&2
    echo "   with 'undefined symbol: PyByteArray_Type'. Set LD_PRELOAD yourself." >&2
  fi

  # Prepended last so it ends up FIRST in LD_PRELOAD, ahead of libpython.
  if [[ -n "$LIBJSIG" ]]; then
    export LD_PRELOAD="$LIBJSIG${LD_PRELOAD:+:$LD_PRELOAD}"
    echo ">> libjsig:   $LIBJSIG (preloaded — JVM keeps its SIGSEGV handler)"
  else
    echo ">> WARNING: libjsig.so not found; a native library may hijack SIGSEGV" >&2
  fi
fi

exec env \
  JAVA_OPTS="$JAVA_OPTS" \
  LD_PRELOAD="${LD_PRELOAD:-}" \
  LD_LIBRARY_PATH="$NATIVE_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  GRAVITEE_HTTP_ENABLED=true \
  GRAVITEE_HTTP_PORT="$PORT" \
  GRAVITEE_AI_WORKSPACE_PATH="$WORKSPACE" \
  GRAVITEE_AI_MODELS_PATH="$HOME/.cache/gravitee-singularitee/models" \
  "$DIST/bin/gravitee.sh"
