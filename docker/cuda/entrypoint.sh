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

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRAVITEEIO_HOME="${GRAVITEEIO_HOME:-$(dirname "$SCRIPT_DIR")}"
export GRAVITEEIO_HOME
# gravitee.sh keys off GRAVITEE_HOME; keep them aligned.
export GRAVITEE_HOME="${GRAVITEE_HOME:-$GRAVITEEIO_HOME}"

# Source the GPU/native wiring before launch.
# shellcheck source=docker/cuda/cuda-env.sh
. "${SCRIPT_DIR}/cuda-env.sh"

echo "[cuda-entrypoint] LD_LIBRARY_PATH=${LD_LIBRARY_PATH}"

if command -v nvidia-smi >/dev/null 2>&1; then
  nvidia-smi -L 2>/dev/null \
    || echo "[cuda-entrypoint] WARNING: nvidia-smi found no GPU — did you run with '--gpus all'?"
else
  echo "[cuda-entrypoint] WARNING: nvidia-smi not on PATH; GPU may be unavailable."
fi

# Each check is scoped to the flavour that actually carries the payload, so the
# other two images stay quiet instead of warning about something they never ship.

if [ -n "${LLAMA_CPP_LIB_PATH:-}" ]; then
  echo "[cuda-entrypoint] LLAMA_CPP_LIB_PATH=${LLAMA_CPP_LIB_PATH}"
  if compgen -G "${LLAMA_CPP_LIB_PATH}/libggml-cuda.so*" >/dev/null 2>&1; then
    echo "[cuda-entrypoint] llama.cpp CUDA backend present: $(ls "${LLAMA_CPP_LIB_PATH}"/libggml-cuda.so* | tr '\n' ' ')"
  else
    echo "[cuda-entrypoint] WARNING: libggml-cuda.so not found under ${LLAMA_CPP_LIB_PATH}"
  fi
fi

if [ -n "${VLLM4J_VENV:-}" ]; then
  echo "[cuda-entrypoint] VLLM4J_VENV=${VLLM4J_VENV}"
  # vLLM4j derives libpython from pyvenv.cfg, so a venv without it cannot
  # bootstrap CPython no matter what else is in place.
  if [ ! -f "${VLLM4J_VENV}/pyvenv.cfg" ]; then
    echo "[cuda-entrypoint] WARNING: ${VLLM4J_VENV}/pyvenv.cfg missing — vLLM4j cannot bootstrap CPython."
  fi
  if ! compgen -G "${VLLM4J_VENV}/lib/libpython3*.so" >/dev/null 2>&1; then
    echo "[cuda-entrypoint] WARNING: no libpython under ${VLLM4J_VENV}/lib — the native load will fail."
  fi
  "${VLLM4J_VENV}/bin/python" -c 'import vllm; print("[cuda-entrypoint] vllm", vllm.__version__)' 2>/dev/null \
    || echo "[cuda-entrypoint] WARNING: 'import vllm' failed in ${VLLM4J_VENV}."
fi

# A workspace baked in at build time (--build-arg BAKE_WORKSPACE=...) becomes the
# default, which is what a serverless platform with no volumes needs. An explicit
# GRAVITEE_AI_WORKSPACE_PATH still wins, so the same image also takes a mounted
# workspace.
if [ -z "${GRAVITEE_AI_WORKSPACE_PATH:-}" ] && [ -f "${GRAVITEEIO_HOME}/workspace.yaml" ]; then
  export GRAVITEE_AI_WORKSPACE_PATH="${GRAVITEEIO_HOME}/workspace.yaml"
  echo "[cuda-entrypoint] workspace: ${GRAVITEE_AI_WORKSPACE_PATH} (baked)"
fi

# An arbitrary runtime uid or a root-owned mount can leave this unwritable, which
# otherwise fails at first download with the health checks already green.
if [ -z "${GRAVITEE_AI_MODELS_PATH:-}" ]; then
  MODELS_DIR="${GRAVITEEIO_HOME}/models"
  if mkdir -p "${MODELS_DIR}" 2>/dev/null && [ -w "${MODELS_DIR}" ]; then
    echo "[cuda-entrypoint] models: ${MODELS_DIR}"
  else
    FALLBACK="${TMPDIR:-/tmp}/graviteeio-singularitee-models"
    mkdir -p "${FALLBACK}"
    export GRAVITEE_AI_MODELS_PATH="${FALLBACK}"
    echo "[cuda-entrypoint] WARNING: ${MODELS_DIR} is not writable as $(id -u):$(id -g) — falling back to ${FALLBACK}."
    echo "[cuda-entrypoint] WARNING: that path is not persistent; models will be downloaded again on every start."
    echo "[cuda-entrypoint] Set GRAVITEE_AI_MODELS_PATH to a writable persistent path, or chown the mount to $(id -u)."
  fi
fi

exec "${SCRIPT_DIR}/gravitee.sh" "$@"
