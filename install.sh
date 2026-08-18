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
# Local setup: build Singularitee and run it as an OpenAI-compatible backend
# for your coding agent. Supported hosts are the two llamaj.cpp ships bindings
# for: macOS on Apple Silicon (Metal) and Linux on x86_64.
#
# Usage:
#   ./install.sh [--port PORT] [--skip-build] [--no-run] [--llama-version bNNNN]
#
# The llama.cpp native libraries are NOT bundled in the jar (licensing); they are
# downloaded here into ~/.llama.cpp, which is where llamaj.cpp's loader looks
# after LLAMA_CPP_LIB_PATH. Same libraries, same layout as the llamaj.cpp repo's
# own build. The host's release archive is picked automatically.
#
# The workspace is a checked-in file: examples/llama/gpt-oss-20b.yaml (MoE, 3.6B active,
# native MXFP4 ~12 GB with no quantization loss, Harmony tool dialect). One file
# per validated model lives in examples/ — switch with:
#   ./run-server.sh --workspace examples/llama/glm-4-9b.yaml
#
# The GGUF is downloaded from HuggingFace by the server itself on first start,
# into ~/.cache/gravitee-singularitee/models (outside the build tree, so 'mvn clean'
# does not wipe it). Set HF_TOKEN (https://huggingface.co/settings/tokens):
# required for gated repos, and authenticated downloads avoid anonymous rate
# limits — often noticeably faster for multi-GB files.

set -euo pipefail

PORT=8080
# Stable model cache OUTSIDE the maven build tree — the distribution's default models dir
# lives under target/ and is obliterated by every 'mvn clean'.
MODELS_DIR="$HOME/.cache/gravitee-singularitee/models"
# Where llamaj.cpp's loader looks for the native libraries when they are not on
# LLAMA_CPP_LIB_PATH. Must match the llama.cpp release the llamaj.cpp dependency
# was generated against: the jextract bindings are ABI-specific, and a mismatched
# dylib crashes the JVM rather than failing to load.
NATIVE_DIR="${NATIVE_DIR:-$HOME/.llama.cpp}"
DEFAULT_LLAMA_CPP_VERSION="b10276"
LLAMA_CPP_VERSION="${LLAMA_CPP_VERSION:-$DEFAULT_LLAMA_CPP_VERSION}"
# SHA-256 of the release archives for the PINNED version above, per host. These are
# native libraries the JVM loads, so a tampered or truncated download executes as us —
# HTTPS protects the transport, not the artifact. Verified before extraction.
# Re-pin when bumping LLAMA_CPP_VERSION:
#   curl -fsSL <url> | shasum -a 256
LLAMA_CPP_SHA256_macos_arm64="20d10cd3bb6004cf1343f8f62c56194c1115a5a926787de0dd65f806faff9887"
LLAMA_CPP_SHA256_ubuntu_x64="6fe102c1b3681d7386c9939db544063f6349472dc44e195bbbb4a7681b2b3870"
# Override to verify a version other than the pinned one (with --llama-version).
LLAMA_CPP_SHA256="${LLAMA_CPP_SHA256:-}"
SKIP_BUILD=false
NO_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)       PORT="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --no-run)     NO_RUN=true; shift ;;
    --llama-version) LLAMA_CPP_VERSION="$2"; shift 2 ;;
    -h|--help)    sed -n '19,40p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
DIST="$REPO_ROOT/gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution"

# ── Prerequisites ────────────────────────────────────────────────────────────
# The archive to fetch and the library extension to keep are the only things that
# differ between hosts. Anything else has no llamaj.cpp bindings, so there is
# nothing to install rather than a slower fallback.
case "$(uname -sm)" in
  "Darwin arm64")  LLAMA_CPP_ARCHIVE_HOST="macos-arm64";  LIB_EXT="dylib" ;;
  "Linux x86_64")  LLAMA_CPP_ARCHIVE_HOST="ubuntu-x64";   LIB_EXT="so" ;;
  *)
    echo "Unsupported host: $(uname -sm)." >&2
    echo "llamaj.cpp ships bindings for macOS arm64 and Linux x86_64 only." >&2
    exit 1
    ;;
esac
if ! command -v java >/dev/null 2>&1; then
  echo "Java 25 is required (e.g. 'sdk install java 25-open' via sdkman)." >&2
  exit 1
fi
JAVA_MAJOR="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
if [[ "${JAVA_MAJOR:-0}" -lt 25 ]]; then
  echo "Java 25+ is required; found major version ${JAVA_MAJOR:-unknown}." >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required (brew install maven / apt install maven)." >&2
  exit 1
fi

# ── Native libraries ─────────────────────────────────────────────────────────
# A stamp records which release is installed: the directory is replaced wholesale
# on a version change, because leftover dylibs from another build are picked up by
# the loader's glob and produce a mismatched set.
STAMP="$NATIVE_DIR/.llama-cpp-version"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$LLAMA_CPP_VERSION" ]]; then
  echo ">> llama.cpp $LLAMA_CPP_VERSION natives already in $NATIVE_DIR"
else
  echo ">> Downloading llama.cpp $LLAMA_CPP_VERSION natives ($LLAMA_CPP_ARCHIVE_HOST) into $NATIVE_DIR"
  ARCHIVE="llama-${LLAMA_CPP_VERSION}-bin-${LLAMA_CPP_ARCHIVE_HOST}.tar.gz"
  URL="https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_CPP_VERSION}/${ARCHIVE}"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  if ! curl -fsSL -o "$TMP_DIR/$ARCHIVE" "$URL"; then
    echo "Failed to download $URL" >&2
    echo "Check that the release exists, or pass --llama-version." >&2
    exit 1
  fi
  # ── Integrity ──────────────────────────────────────────────────────────────
  # Only the pinned version has a known digest; --llama-version needs one supplied
  # via LLAMA_CPP_SHA256, else we can only warn.
  EXPECTED_SHA="$LLAMA_CPP_SHA256"
  if [[ -z "$EXPECTED_SHA" && "$LLAMA_CPP_VERSION" == "$DEFAULT_LLAMA_CPP_VERSION" ]]; then
    case "$LLAMA_CPP_ARCHIVE_HOST" in
      macos-arm64) EXPECTED_SHA="$LLAMA_CPP_SHA256_macos_arm64" ;;
      ubuntu-x64)  EXPECTED_SHA="$LLAMA_CPP_SHA256_ubuntu_x64" ;;
    esac
  fi
  if [[ -n "$EXPECTED_SHA" ]]; then
    if command -v shasum >/dev/null 2>&1; then
      ACTUAL_SHA="$(shasum -a 256 "$TMP_DIR/$ARCHIVE" | awk '{print $1}')"
    elif command -v sha256sum >/dev/null 2>&1; then
      ACTUAL_SHA="$(sha256sum "$TMP_DIR/$ARCHIVE" | awk '{print $1}')"
    else
      echo "Neither shasum nor sha256sum found — cannot verify the download." >&2
      exit 1
    fi
    if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
      echo "SHA-256 mismatch for $ARCHIVE — refusing to install." >&2
      echo "  expected: $EXPECTED_SHA" >&2
      echo "  actual:   $ACTUAL_SHA" >&2
      echo "The archive was tampered with, truncated, or the pin is stale." >&2
      exit 1
    fi
    echo ">> SHA-256 verified"
  else
    echo ">> WARNING: no known SHA-256 for llama.cpp $LLAMA_CPP_VERSION on $LLAMA_CPP_ARCHIVE_HOST." >&2
    echo "   These libraries are loaded into the JVM unverified. Pass LLAMA_CPP_SHA256=<digest>" >&2
    echo "   to verify, or use the pinned default version." >&2
  fi

  rm -rf "$NATIVE_DIR"
  mkdir -p "$NATIVE_DIR"
  tar -xzf "$TMP_DIR/$ARCHIVE" -C "$NATIVE_DIR"
  # The archive nests the libs under build/bin; the loader expects them flat.
  find "$NATIVE_DIR" \( -type f -o -type l \) \( -name "*.$LIB_EXT" -o -name "*.$LIB_EXT.*" -o -name "LICENSE" -o -name "LICENSE-*" \) \
    -exec mv -f {} "$NATIVE_DIR/" \; 2>/dev/null || true
  find "$NATIVE_DIR" -mindepth 1 -type d -exec rm -rf {} + 2>/dev/null || true
  # Drop llama.cpp's own tool implementations (cli, server, bench, ...): the binding
  # only calls the C ABI (libllama / libmtmd / libggml*), and these pull in extra
  # symbols the loader would otherwise try to resolve.
  find "$NATIVE_DIR" -maxdepth 1 \( -name "*-common*" -o -name "*-impl*" \) -delete
  echo "$LLAMA_CPP_VERSION" > "$STAMP"
  trap - EXIT
  rm -rf "$TMP_DIR"
  echo ">> Installed $(find "$NATIVE_DIR" -maxdepth 1 -name "*.$LIB_EXT*" | wc -l | tr -d ' ') libraries"
fi

# ── Build ────────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == false ]]; then
  echo ">> Building (default profile — natives come from $NATIVE_DIR at runtime)"
  mvn -f "$REPO_ROOT/pom.xml" clean install -DskipTests
fi
if [[ ! -x "$DIST/bin/gravitee.sh" ]]; then
  echo "Distribution not found at $DIST — run without --skip-build first." >&2
  exit 1
fi

# ── Workspace ────────────────────────────────────────────────────────────────
WORKSPACE_YAML="$REPO_ROOT/examples/llama/gpt-oss-20b.yaml"
[[ -f "$WORKSPACE_YAML" ]] || { echo "Missing $WORKSPACE_YAML" >&2; exit 1; }
echo ">> Workspace: $WORKSPACE_YAML"

echo
echo ">> Setup complete."
echo ">> Model files download on first start into $MODELS_DIR (survives rebuilds)."
if [[ -z "${HF_TOKEN:-}" ]]; then
  echo ">> Tip: exporting HF_TOKEN (free HuggingFace account, huggingface.co/settings/tokens)"
  echo ">>      avoids anonymous rate limits and can speed up the ~12 GB model download."
fi
echo
echo "Run the server:"
echo "  ./run-server.sh                                     # qwen3-0.6b (run-server.sh default)"
echo "  ./run-server.sh --workspace examples/llama/gpt-oss-20b.yaml  # what install.sh just ran"
echo "  ./run-server.sh --workspace examples/llama/glm-4-9b.yaml   # another model"
echo "  ./run-server.sh --debug                              # TRACE-log rendered prompts"
echo
BASE="http://localhost:$PORT"
cat <<CLIENTS

──────────────────────────────────────────────────────────────────────────────
Use it with your coding agent. Every example workspace exposes ONE pipeline
called 'agent', so the client config below is unchanged when you switch models.
Served: OpenAI /v1/chat/completions, /v1/responses, /v1/embeddings, /v1/models.

── pi ───────────────────────────────────────────────────────────────────────
  brew install pi-coding-agent          # macOS / Linuxbrew
  npm install -g @mariozechner/pi-coding-agent   # anywhere else
  Add to ~/.pi/agent/models.json (only "id" is required for a local model):
  {
    "providers": {
      "singularitee": {
        "baseUrl": "$BASE/v1",
        "api": "openai-completions",
        "apiKey": "local",
        "models": [
          { "id": "agent", "name": "Singularitee (local)" }
        ]
      }
    }
  }
  then: pi --model singularitee/agent
  (the file is re-read by /model inside pi — no restart after editing it)

── Anything speaking the OpenAI API (Cline, Continue, SDKs, curl…) ──────────
  base_url: $BASE/v1     api_key: local     model: agent
CLIENTS

if [[ "$NO_RUN" == false ]]; then
  echo
  echo ">> Starting server on port $PORT (Ctrl-C to stop)"
  mkdir -p "$MODELS_DIR"
  # Delegate to run-server.sh so there is a single launch path (and one place
  # that owns the logback --debug toggle).
  exec "$REPO_ROOT/run-server.sh" --port "$PORT" --workspace "$WORKSPACE_YAML"
fi
