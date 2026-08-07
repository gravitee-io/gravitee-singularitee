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
# Shared CUDA environment for the three engine images (onnx / llamacpp / vllm).
#
# Each block is conditional on the payload actually being present, so one script
# serves all three flavours: the ONNX image has neither a llama.cpp native
# directory nor a Python venv, and skips both.
#

: "${GRAVITEEIO_HOME:=/opt/graviteeio-singularitee}"

export LD_LIBRARY_PATH="/usr/local/cuda/lib64:/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"

# ── llama.cpp CUDA natives (llamacpp image only) ─────────────────────────────
if [ -z "${LLAMA_CPP_LIB_PATH:-}" ] && [ -d "${GRAVITEEIO_HOME}/native/llama-cuda" ]; then
  export LLAMA_CPP_LIB_PATH="${GRAVITEEIO_HOME}/native/llama-cuda"
fi
if [ -n "${LLAMA_CPP_LIB_PATH:-}" ]; then
  export LD_LIBRARY_PATH="${LLAMA_CPP_LIB_PATH}:${LD_LIBRARY_PATH}"
fi

# ── vLLM virtualenv (vllm image only) ────────────────────────────────────────
if [ -n "${VLLM4J_VENV:-}" ]; then
  # vLLM4j locates the venv ONLY through the 'vllm4j.venv' system property (or
  # a .venv beside the CWD / HOME). Exporting VLLM4J_VENV alone is not enough —
  # it has to reach the JVM as -D, which is what this does.
  case " ${JAVA_OPTS:-} " in
    *" -Dvllm4j.venv="*) : ;; # caller already set it; leave their value alone
    *) export JAVA_OPTS="${JAVA_OPTS:+${JAVA_OPTS} }-Dvllm4j.venv=${VLLM4J_VENV}" ;;
  esac

  # libpython has to be resolvable before the JVM dlopen()s it through the FFM
  # bridge; preloading it is the most reliable way to guarantee that.
  _libpython="$(ls "${VLLM4J_VENV}"/lib/libpython3*.so 2>/dev/null | head -1)"
  if [ -n "${_libpython}" ]; then
    export LD_PRELOAD="${_libpython}${LD_PRELOAD:+:${LD_PRELOAD}}"
  fi
  unset _libpython

  # libjsig FIRST in the chain, and it is not optional here.
  #
  # apache-tvm-ffi (pulled in by xgrammar, which vLLM imports) installs a
  # SIGSEGV handler from a library constructor — the moment it loads, before
  # anything can object:
  #     __attribute__((constructor)) void TVMFFIInstallSignalHandler() {
  #       // this may override already installed signal handlers
  #       std::signal(SIGSEGV, TVMFFISegFaultHandler);
  #     }
  # HotSpot *relies* on SIGSEGV for ordinary work — implicit null checks,
  # safepoint polling, stack-bang guard pages — and recovers from those faults
  # in its own handler. Once tvm-ffi replaces it, the next routine JVM segfault
  # reaches TVMFFISegFaultHandler instead, which prints
  #     !!!!!!! Segfault encountered !!!!!!!
  # and re-raises with SIG_DFL, killing the process with exit 139. It also
  # explains the missing hs_err_pid file: the JVM's handler was gone.
  #
  # libjsig interposes signal()/sigaction() so a native library's handler is
  # *chained* behind the JVM's rather than replacing it. Preloaded ahead of
  # libpython so it is in place before any Python extension loads.
  _libjsig="$(ls /usr/lib/jvm/*/lib/libjsig.so 2>/dev/null | head -1)"
  if [ -z "${_libjsig}" ] && [ -n "${JAVA_HOME:-}" ]; then
    _libjsig="$(ls "${JAVA_HOME}"/lib/libjsig.so 2>/dev/null | head -1)"
  fi
  if [ -n "${_libjsig}" ]; then
    export LD_PRELOAD="${_libjsig}${LD_PRELOAD:+:${LD_PRELOAD}}"
    echo "[cuda-env] libjsig preloaded: ${_libjsig} (JVM keeps its SIGSEGV handler)"
  else
    echo "[cuda-env] WARNING: libjsig.so not found — a native library may hijack SIGSEGV" >&2
  fi
  unset _libjsig

  # 'spawn' rather than 'fork': vLLM's engine-core workers would otherwise be
  # forked from a process that has already initialised the JVM and CUDA, neither
  # of which survives a fork.
  export VLLM_WORKER_MULTIPROC_METHOD="${VLLM_WORKER_MULTIPROC_METHOD:-spawn}"
  export VLLM_LOGGING_LEVEL="${VLLM_LOGGING_LEVEL:-WARNING}"
fi
