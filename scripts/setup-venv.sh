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
# Sets up the uv virtual environment and installs vLLM with the appropriate
# backend-specific packages. This script is invoked from the backend Maven
# profile during the 'initialize' phase.
#
# Usage:
#   ./setup-venv.sh -d <venv_dir> -v <python_version> -b <backend>
#
#   -d  directory where .venv will be created  (default: ~/.venv-gravitee-ai)
#   -v  Python version (e.g. 3.12)
#   -b  backend: metal | cuda | cpu
#

set -euo pipefail

VENV_PARENT="${HOME}/.venv-gravitee-ai"
PYTHON_VERSION="3.12"
BACKEND=""
VLLM_VERSION="0.23.0"
# See install_common() — newer xgrammar segfaults on import.
XGRAMMAR_VERSION="0.2.2"
TVM_FFI_VERSION="0.1.12"

print_usage() {
  echo "Usage: $0 [-d <venv_dir>] [-v <python_version>] -b <backend>"
  echo "  backend: metal | cuda | cpu"
}

while getopts ":d:v:b:h" opt; do
  case ${opt} in
    d) VENV_PARENT=$OPTARG ;;
    v) PYTHON_VERSION=$OPTARG ;;
    b) BACKEND=$OPTARG ;;
    h) print_usage; exit 0 ;;
    \?) echo "Invalid option: -$OPTARG" >&2; print_usage; exit 1 ;;
    :)  echo "Option -$OPTARG requires an argument." >&2; print_usage; exit 1 ;;
  esac
done

if [[ -z "$BACKEND" ]]; then
  echo "Missing required argument: -b <backend>" >&2
  print_usage
  exit 1
fi

mkdir -p "$VENV_PARENT"
VENV_DIR="${VENV_PARENT}/.venv"

# ═══════════════════════════════════════════════════════════════════════════════
# 1. Ensure uv is installed
# ═══════════════════════════════════════════════════════════════════════════════

if ! command -v uv &>/dev/null; then
  echo "uv not found — installing via official installer..."
  curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
fi

UV_BIN="$(command -v uv)"
echo "Using uv: $UV_BIN  ($(uv --version))"

# ═══════════════════════════════════════════════════════════════════════════════
# 2. Create venv
# ═══════════════════════════════════════════════════════════════════════════════

# Always a uv-managed standalone CPython, never a system/Homebrew one: a venv
# built on a brew python breaks the moment that formula is upgraded or removed
# (its bin/python is a symlink into the Cellar), and framework builds also put
# the stdlib somewhere PYTHONHOME cannot be derived from naively.
export UV_PYTHON_PREFERENCE=only-managed
"$UV_BIN" python install "$PYTHON_VERSION"

# An existing venv is only reusable if its interpreter still exists and is
# managed — otherwise every later uv command fails with "No virtual environment
# or system Python installation found".
if [[ -d "$VENV_DIR" ]]; then
  VENV_HOME="$(sed -n 's/^home[[:space:]]*=[[:space:]]*//p' "${VENV_DIR}/pyvenv.cfg" 2>/dev/null | head -1)"
  if [[ ! -x "${VENV_DIR}/bin/python" || "$VENV_HOME" != *"/uv/python/"* ]]; then
    echo "Existing venv is unusable (interpreter: ${VENV_HOME:-unknown}) — recreating."
    rm -rf "$VENV_DIR"
  fi
fi

if [[ ! -d "$VENV_DIR" ]]; then
  echo "Creating virtual environment at $VENV_DIR (Python $PYTHON_VERSION) ..."
  "$UV_BIN" venv "$VENV_DIR" --python "$PYTHON_VERSION"
else
  echo "Virtual environment already exists at $VENV_DIR"
fi

VENV_PYTHON="${VENV_DIR}/bin/python"

# ═══════════════════════════════════════════════════════════════════════════════
# 3. Install packages based on backend
# ═══════════════════════════════════════════════════════════════════════════════

install_common() {
  # jinja2 is the only dependency vLLM does not pull in transitively.
  # ninja, setuptools, and transformers are all bundled by vllm>=0.23.0.
  "$UV_BIN" pip install --python "$VENV_PYTHON" jinja2

  # Pin xgrammar and its tvm-ffi runtime.
  #
  # vLLM only asks for "xgrammar>=0.2.0,<1.0.0", so a fresh resolve takes the
  # newest. xgrammar 0.2.4 (with apache-tvm-ffi 0.1.13) segfaults inside its own
  # static initialiser the moment `import vllm` loads it:
  #   !!!!!!! Segfault encountered !!!!!!!
  #     TVMFFIEnvRegisterCAPI / xgrammar::__TVMFFIStaticInitFunc0()
  # taking the whole process down before any vLLM code runs — a plain
  # `python -c "from vllm import LLM"` crashes too, so it is not JVM-specific.
  #
  # 0.2.2/0.1.12 is the last combination verified to import cleanly. Revisit on
  # a VLLM_VERSION bump; xgrammar only backs guided decoding, which is not
  # exposed here.
  "$UV_BIN" pip install --python "$VENV_PYTHON" \
    "xgrammar==${XGRAMMAR_VERSION}" "apache-tvm-ffi==${TVM_FFI_VERSION}"
}

case "$BACKEND" in

  metal)
    # Check if the correct vllm version is already installed. vllm-metal is
    # reinstalled unconditionally below — it is cheap (a prebuilt wheel) and
    # guarantees the plugin matches the vllm core pinned above.
    VLLM_OK=false
    if "$VENV_PYTHON" -c "
import vllm, importlib.metadata as m
assert m.version('vllm') == '${VLLM_VERSION}', f'wrong vllm {m.version(\"vllm\")}'
" &>/dev/null; then
      VLLM_OK=true
    fi

    if [[ "$VLLM_OK" == "false" ]]; then
      echo "Installing vLLM ${VLLM_VERSION} (Apple Silicon Metal/MLX) ..."

      # vLLM is not on PyPI for metal — install from the GitHub release tarball,
      # same as the official vllm-metal install.sh does.
      VLLM_TARBALL="/tmp/vllm-${VLLM_VERSION}.tar.gz"
      if [[ ! -f "$VLLM_TARBALL" ]]; then
        echo "  Downloading vLLM ${VLLM_VERSION} tarball..."
        curl -fsSL "https://github.com/vllm-project/vllm/releases/download/v${VLLM_VERSION}/vllm-${VLLM_VERSION}.tar.gz" \
          -o "$VLLM_TARBALL"
      fi

      # Always extract fresh — a prior failed build leaves stale CMake cache
      # (baked ninja paths from uv's temp build-isolation dir) that causes
      # subsequent builds to fail. Fresh extraction is cheap (~1s) and ensures
      # idempotent builds.
      VLLM_SRC="/tmp/vllm-${VLLM_VERSION}"
      rm -rf "$VLLM_SRC"
      tar xf "$VLLM_TARBALL" -C /tmp

      "$UV_BIN" pip install --python "$VENV_PYTHON" \
        -r "${VLLM_SRC}/requirements/cpu.txt" \
        --index-strategy unsafe-best-match

      "$UV_BIN" pip install --python "$VENV_PYTHON" "$VLLM_SRC"
    else
      echo "vllm ${VLLM_VERSION} already installed — skipping vllm core install."
    fi

    # Pinned prebuilt wheel rather than git@main: vllm-metal's main branch
    # tracks vLLM's main, so an unpinned install drifts out of sync with the
    # pinned vllm core above and fails at import. The wheel also ships the
    # Metal kernels precompiled, so there is nothing to build here.
    echo "Installing vllm-metal (prebuilt wheel) ..."
    "$UV_BIN" pip install --python "$VENV_PYTHON" \
      "https://github.com/vllm-project/vllm-metal/releases/download/v0.3.0.dev20260616093506/vllm_metal-0.3.0.dev20260616093506-cp312-cp312-macosx_11_0_arm64.whl"

    install_common
    ;;

  cuda)
    {
      # Pick the wheel that matches the *driver*, not the newest build.
      #
      # The wheel on PyPI is a CUDA 13 build — vllm._C links libcudart.so.13 —
      # and --torch-backend=auto pairs it with whatever torch the driver allows.
      # On a pre-580 driver those two disagree and the engine dies at import:
      #   ImportError: libcudart.so.13: cannot open shared object file
      # Forcing torch to cu130 instead only moves the failure one step later:
      #   RuntimeError: The NVIDIA driver on your system is too old (found
      #   version 12080)
      # because CUDA 13 needs r580+, which no 12.x driver satisfies.
      #
      # The release page carries a +cu129 wheel alongside the default one, and
      # that whole stack (vllm._C, torch, the nvidia-*-cu12 runtime) runs on any
      # 12.x driver. Choose between them on the driver version, and pin
      # --torch-backend to match so uv cannot resolve torch into the other major.
      DRIVER_MAJOR="$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null \
        | head -1 | cut -d. -f1)"

      if [[ -n "$DRIVER_MAJOR" && "$DRIVER_MAJOR" -lt 580 ]]; then
        echo "Driver ${DRIVER_MAJOR}.x predates CUDA 13 (needs r580+) — using the +cu129 wheel."
        VLLM_PACKAGE="https://github.com/vllm-project/vllm/releases/download/v${VLLM_VERSION}/vllm-${VLLM_VERSION}+cu129-cp38-abi3-manylinux_2_28_$(uname -m).whl"
        TORCH_BACKEND="cu129"
        WANT_CUDA_MAJOR="12"
      else
        VLLM_PACKAGE="vllm==${VLLM_VERSION}"
        TORCH_BACKEND="auto"
        WANT_CUDA_MAJOR="13"
      fi
    }

    # "Already installed" is not the same as "usable". A venv built before this
    # check existed — or on a machine with a different driver — can hold a CUDA
    # 13 vllm beside a cu129 torch, a pairing that only fails at model load:
    #   ImportError: libcudart.so.13: cannot open shared object file
    # Compare what is actually installed against what the driver needs and
    # reinstall over it, so re-running this script repairs a venv instead of
    # declaring it fine.
    CURRENT_CUDA_MAJOR="$("$VENV_PYTHON" -c \
      'import torch; print((torch.version.cuda or "").split(".")[0])' 2>/dev/null || true)"

    # Probe vllm._C, not vllm. The mismatch this repairs lives in the compiled
    # extension, and `import vllm` sails straight past it:
    #   >>> import vllm            # fine
    #   >>> import vllm._C         # ImportError: libcudart.so.13
    # The engine only trips over it at model load, which is exactly the late,
    # opaque failure this check exists to prevent.
    if "$VENV_PYTHON" -c "import vllm._C" &>/dev/null &&
       [[ "$CURRENT_CUDA_MAJOR" == "$WANT_CUDA_MAJOR" ]]; then
      echo "vllm already installed and built for CUDA ${CURRENT_CUDA_MAJOR} — skipping."
    else
      if "$VENV_PYTHON" -c "import importlib.metadata as m; m.version('vllm')" &>/dev/null; then
        # Two different breakages, and the torch version alone cannot tell them
        # apart: a cu13 vllm wheel next to a cu12 torch has the *right* torch and
        # still fails to load its kernels.
        if "$VENV_PYTHON" -c "import vllm._C" &>/dev/null; then
          echo "Repairing venv: torch targets CUDA ${CURRENT_CUDA_MAJOR:-unknown}," \
               "this driver needs CUDA ${WANT_CUDA_MAJOR}."
        else
          echo "Repairing venv: vllm's compiled extension does not load — its CUDA runtime" \
               "does not match torch (CUDA ${CURRENT_CUDA_MAJOR:-unknown})."
        fi
      fi

      echo "Installing vLLM ${VLLM_VERSION} (CUDA wheel, torch backend ${TORCH_BACKEND}) ..."

      # --reinstall-package is a no-op on a fresh venv and the whole point on a
      # stale one: without it uv keeps the satisfied-but-wrong versions.
      "$UV_BIN" pip install --python "$VENV_PYTHON" \
        --reinstall-package vllm --reinstall-package torch \
        "$VLLM_PACKAGE" --torch-backend="$TORCH_BACKEND"
    fi

    install_common
    ;;

  cpu)
    if "$VENV_PYTHON" -c "import vllm" &>/dev/null; then
      echo "vllm already installed — skipping."
    else
      echo "Installing vLLM ${VLLM_VERSION} (CPU-only wheel) ..."

      "$UV_BIN" pip install --python "$VENV_PYTHON" \
        "vllm==${VLLM_VERSION}" --torch-backend cpu
    fi

    install_common
    ;;

  *)
    echo "ERROR: Unknown backend '$BACKEND'. Supported: metal, cuda, cpu" >&2
    exit 1
    ;;
esac

echo "venv setup complete for backend '$BACKEND' at $VENV_DIR"
