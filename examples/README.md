# Examples

Every file here is a **complete, runnable workspace**. Pick one and start the server:

```bash
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml
./run-server.sh --list          # everything that can be run, by folder
```

Model weights download from HuggingFace on first start into
`~/.cache/gravitee-singularitee/models`, so the first run of a new workspace is slow
and every run after it is not.

With [go-task](https://taskfile.dev) (`brew install go-task`) there is a named target for
every example — `task` on its own lists them all:

```bash
task run:qwen        # the reference model          task run:guard       # PII + toxicity pipeline
task run:vision      # vision model                 task run:tool-router # tool shortlisting
task run:pii         # PII classifier               task list            # every workspace
```

Server targets run in the foreground; the demo targets below (`task vision`, `task audio`,
`task chat`, `task classify`) talk to an already-running server from a second shell.

```
examples/
├── llama/        llama.cpp GGUF models — the default backend, runs anywhere
├── vllm/         vLLM models — same families as llama/; Linux/CUDA only (work in progress)
├── classifier/   PII, toxicity, guardrails, intent — bert (fine-tuned) and GLiNER (zero-shot)
├── embedding/    embedding models for retrieval and KNN routing
├── reranker/     cross-encoder rerankers
├── pipelines/    multi-step examples: guards, routers, chain-of-thought
├── modular/      the includes mechanism: compose one server from shared fragments
├── scripts/      Python demos that drive a running server
└── observability/ Prometheus + Grafana docker-compose stack
```

**One backend per server.** llama.cpp, vLLM and ONNX Runtime each load their own
native libraries; co-locating them in one JVM invites library conflicts and GPU
memory contention. Give each backend its own process and compose them over gRPC —
that is what `modular/` demonstrates.

---

## Models

Every generation workspace publishes one model under the logical id **`llm`** and one
pipeline named **`agent`**, so client config and `curl` commands carry over unchanged
when you switch files.

### `llama/` — llama.cpp (cross-platform)

| File | Model | Notes |
| --- | --- | --- |
| `qwen3-0.6b.yaml` | Qwen/Qwen3-0.6B-GGUF | **The reference model.** Every pipeline example uses it: seconds to download, runs on a laptop CPU. |
| `qwen2.5-0.5b.yaml` | Qwen2.5-0.5B-Instruct | Background tasks (titles, summaries). Cannot emit reasoning by construction. Not for tool loops. |
| `mistral-7b.yaml` | Mistral-7B-Instruct-v0.3 | No reasoning channel — answers directly. |
| `glm-4-9b.yaml` | GLM-4-9B | |
| `gemma4-12b.yaml` / `gemma4-26b.yaml` | Gemma 4 | 26B is MoE (A4B active). |
| `moonlight-16b.yaml` | Moonlight-16B | MoE. |
| `gpt-oss-20b.yaml` | gpt-oss-20b | MoE (3.6B active), native MXFP4 ~12 GB. Harmony channel dialect. |
| `qwen3-30b.yaml` / `qwen3.6-35b.yaml` | Qwen3 MoE | Larger hardware. |
| `qwen3-vl-2b.yaml` | Qwen3-VL-2B | **Vision** — accepts `image_url` content parts. |
| `voxtral-3b.yaml` | Voxtral-Mini-3B | **Audio** — accepts `input_audio` content parts. |
| `shieldstral-3b.yaml` | Shieldstral-1.0-3B | **Safety classifier** — answers one yes/no policy question with a single token; drive it with `max_tokens: 1`. |

The multimodal files differ from the text-only ones by exactly one key: `mmproj_path`,
the projection GGUF. Set it and the engine loads an `MtmdContext`.

> `n_ctx` is **per sequence**: total KV allocated is `n_ctx * n_seq_max`.

### `vllm/` — vLLM (Linux + CUDA)

| File | Model | Notes |
| --- | --- | --- |
| `qwen3-0.6b.yaml` | Qwen/Qwen3-0.6B | Dense 0.6B — the one to smoke-test a deployment with |
| `qwen2.5-0.5b.yaml` | Qwen/Qwen2.5-0.5B-Instruct | Dense 0.5B, no thinking mode |
| `mistral-7b.yaml` | mistralai/Mistral-7B-Instruct-v0.3 | Dense 7.2B, fits one 24GB card at bf16 |
| `glm-4-9b.yaml` | THUDM/GLM-4-9B-0414 | Dense 9.4B |
| `moonlight-16b.yaml` | moonshotai/Moonlight-16B-A3B-Instruct | MoE 16B on DeepSeek-V3; needs `trust_remote_code`, 8k window |
| `gpt-oss-20b.yaml` | openai/gpt-oss-20b | MoE 21B, ships pre-quantized (MXFP4) |
| `gemma4-12b.yaml` | google/gemma-4-12B-it | Dense 12B **with a vision tower** |
| `gemma4-26b.yaml` | google/gemma-4-26B-A4B-it | MoE 26.5B + vision, `tensor_parallel_size: 2` |
| `qwen3-30b.yaml` | Qwen/Qwen3-30B-A3B-Instruct-2507 | MoE 30.5B, `tensor_parallel_size: 2` |
| `qwen3.6-35b.yaml` | Qwen/Qwen3.6-35B-A3B | MoE 36B, `tensor_parallel_size: 2` |
| `qwen3-vl-2b.yaml` | Qwen/Qwen3-VL-2B-Instruct | Vision — no mmproj to configure, unlike llama.cpp |
| `qwen3-gptq.yaml` | Qwen/Qwen3-0.6B-GPTQ-Int8 | 8-bit GPTQ, ~2x memory reduction, near-lossless |
| `qwen3-awq.yaml` | Qwen/Qwen3-4B-AWQ | 4-bit AWQ, ~4x reduction, 10-20% faster than GPTQ |

These mirror `llama/` family for family (minus `voxtral-3b`, which is audio). Every
architecture above is in vLLM 0.23's registry, and the parameter counts and context
windows in each file were read from the checkpoints rather than guessed.

Two things differ from the llama.cpp equivalents:

- **No file paths.** vLLM takes a HuggingFace repo id and fetches the weights itself, so
  there is no GGUF filename — and no `mmproj_path` for vision models, since the vision
  tower lives in the same checkpoint.
- **Multi-GPU is explicit.** Models above ~40GB of weights carry `tensor_parallel_size`.
  Set it once for a whole deployment with `GRAVITEE_AI_VLLM_TENSORPARALLELSIZE` instead,
  and leave it out of the workspace.

⚠️ **Work in progress.** The vLLM backend is far less exercised than llama.cpp — treat
these as a starting point, not a tuned production config. Run end-to-end so far: the two
Qwen3 quantized files, and `gpt-oss-20b.yaml` (on Apple Silicon/Metal, not CUDA). The rest
are configured from checkpoint metadata and verified to parse, not to serve. They need a
vLLM virtualenv on the JVM command line (`-Dvllm4j.venv=...`, see `scripts/setup-venv.sh`)
unless you use the `-vllm-cuda` image, which bundles it.

### `classifier/`

The interesting axis here is **fine-tuned vs zero-shot**:

| File | id | Type | How you change what it detects |
| --- | --- | --- | --- |
| `pii-bert.yaml` | `pii` | `onnx_classifier` (TOKEN) | Retrain. Fast and cheap; detects only what it was trained on. |
| `pii-gliner.yaml` | `pii` | `gliner_ner` | **Edit the 42-entity list.** Zero-shot; heavier per call. |
| `toxicity-bert.yaml` | `toxicity` | `onnx_classifier` (SEQUENCE) | Retrain. Multilingual, `{non-toxic, toxic}`. |
| `guardrails-gliner.yaml` | `gliguard` | `gliner_classifier` | **Edit the 11 safety labels.** Policy change = YAML edit, not a training run. |
| `intent-gliner.yaml` | `router` | `gliner_classifier` | **Edit the intent labels.** Backs `pipelines/gliner-router.yaml`. |

TOKEN mode labels individual spans (which is what makes redaction possible); SEQUENCE
mode gives the whole input one label.

### `embedding/` and `reranker/`

| File | Model | Notes |
| --- | --- | --- |
| `embedding/bge-m3.yaml` | BAAI/bge-m3 | Multilingual (100+ languages), 1024-dim. |
| `embedding/bge-small-en.yaml` | BAAI/bge-small-en-v1.5 | English, 384-dim, ~130 MB — a tenth of bge-m3. |
| `reranker/bge-reranker-base.yaml` | BAAI/bge-reranker-base | Cross-encoder. |

Embeddings and rerankers are complements, not alternatives: embed to retrieve the top
~100 candidates cheaply, then rerank those to the final ~10. A cross-encoder reads
`(query, document)` together — better ranking, but it cannot be precomputed into an
index. `pooling_mode`/`normalize` must match the model card (BGE wants `CLS` + normalize;
most sentence-transformers want `MEAN`).

---

## Pipelines

Multi-step examples. All of them bind `llm` to **Qwen3-0.6B** so they download in
seconds and run on a laptop — swap in any file from `llama/` for better output.

| File | DAG | What it demonstrates |
| --- | --- | --- |
| `guard.yaml` | `toxicity_guard(reject) → pii_guard(redact) → generate` | Two guard actions, deliberately ordered: **reject** first so toxic input never reaches the model, **redact** second so the LLM only ever sees masked text. |
| `tool-router.yaml` | `select_tools → agent` | Shortlisting the caller's tools with a zero-shot classifier before injecting their schemas. |
| `gliner-router.yaml` | `route → respond_{code,cooking,finance,general}` | `strategy: classifier` — the top label picks the branch. |
| `embedding-router.yaml` | `route → respond_{support,sales,general}` | `strategy: embedding_knn` — nearest reference sentence picks the branch. |
| `cot.yaml` | `reason → evaluate → loop_gate → answer / fallback` | A quality gate as a graph edge: loop back and refine until an evaluator step is satisfied. |
| `tool-repair.yaml` | `generate → repair_gate → done / fallback` | Tool-call self-repair on gpt-oss-20b (Harmony): loop back with the parse error when `generate.tool_parse_failed` is true (a broken attempt, not a prose answer), instead of shipping a malformed call as prose. |
| `tool-repair-escalate.yaml` | `generate(Qwen3-0.6B) → repair_gate → done / escalate(gpt-oss-20b)` | The fallback is a bigger model, not an apology: two repair attempts on the small model (retries at temp 0.2), then the same conversation — corrective turns included — goes to gpt-oss-20b, Harmony tags and all. |
| `todo-agent.yaml` | `plan → apply_plan → work → track → work_gate → summarize` | Plan-and-execute: the model decomposes the task via the server-executed `set_todos`/`complete_todo` tools, a loop works through items until `todos.remaining` is 0, and progress streams as `gravitee.progress` events. |

### Tool router

An agent with 60 tools pays for 60 JSON schemas in every prompt — thousands of tokens
before the user's question is even read — and a small model's accuracy degrades as that
list grows.

The `tool_select` step classifies the **last user message** against the tools **the
caller sent on this request**. Nothing is configured ahead of time: each tool's name and
description become zero-shot labels at request time, so the same pipeline works for any
client with any tool set. Tools are scored in batches, each batch carrying a synthetic
`none_of_these` label; a tool survives when it clears `threshold` **and** outscores
`none_of_these`. The infer step then injects only the survivors.

Two behaviours worth knowing:

- Every batch electing `none_of_these` (a plain conversational turn) leaves the shortlist
  **empty** and injects no tools at all. `always_include` is unioned in only when the
  shortlist is non-empty, so chit-chat stays clean.
- A failed classify call **fails open**: that batch's tools are all included. Degraded
  routing beats a silently tool-less agent.

### Choosing a router

| | `gliner-router` (classifier) | `embedding-router` (KNN) |
| --- | --- | --- |
| You write | Label + description | Example sentences per route |
| Matching | Exact string equality against the top label | Cosine similarity, single nearest sentence wins |
| Best when | Routes are a small, named, stable set | Routes are fuzzy, overlapping, or grown from real user phrasings |

⚠️ With `strategy: classifier`, every `rules[].label` must appear **verbatim** in the
model's `labels[].name` — matching is string equality. A mismatch is the usual reason a
router silently always takes `default_step`.

With `embedding_knn`, reference sentences are embedded once at load time and cached; the
**single** nearest sentence wins, not a per-rule average. One sharp example beats five
vague ones, and adding weak examples to a rule cannot dilute it.

---

## `modular/` — composing servers from shared fragments

Everything above is self-contained. `modular/` shows the other way: small **fragments**
that several servers include, so one model definition is written once and reused.

```
modular/
├── models/
│   ├── llama/      llm-qwen3-0.6b.yaml, llm-mistral-7b.yaml
│   ├── vllm/       llm-qwen3-0.6b.yaml, llm-mistral-7b.yaml, llm-qwen3-30b.yaml, … (13)
│   └── classifier/ pii-bert.yaml, pii-gliner.yaml, toxicity-bert.yaml, router-gliner.yaml
├── pipelines/      infer, tool-calling, pii-redact, toxicity-guard, routing, cot
├── templates/      tool-system.yaml, glm-4-9b-compact.jinja
├── server-*.yaml   one backend per server — includes a model subset + its pipelines
└── client-*.yaml   remote model proxies + the pipelines to run locally
```

`includes:` paths resolve against the **`models/`, `pipelines/` and `templates/`
subdirectories of the including file's own folder** — which is why the servers and
clients sit at the top of `modular/` and why the model folders nest *inside* `models/`:

```yaml
workspace:
  name: server-llamacpp
  includes:
    models:
      - llama/llm-qwen3-0.6b.yaml     # → modular/models/llama/llm-qwen3-0.6b.yaml
    pipelines:
      - infer.yaml                    # → modular/pipelines/infer.yaml
```

Globs work too (`llama/*.yaml`), expanded alphabetically.

### The logical-id convention

Fragments never name a concrete model in a pipeline — they agree on **stable logical
ids**, so the same pipeline runs unchanged on any backend:

| id | Role | Fragments providing it |
| --- | --- | --- |
| `llm` | Text generation | `llama/llm-qwen3-0.6b`, `llama/llm-mistral-7b`, and 13 `vllm/llm-*` fragments covering the same families as `examples/vllm/` |
| `pii` | PII detection | `classifier/pii-bert`, `classifier/pii-gliner` |
| `toxicity` | Toxicity | `classifier/toxicity-bert` |
| `router` | Intent routing | `classifier/router-gliner` |

> ⚠️ Several fragments share the id `llm` (and `pii`). Servers must list model files
> **explicitly** — never glob `models: ["*.yaml"]`, or duplicate ids load into one
> workspace and resolve ambiguously. Include exactly one `llm-*` and one `pii-*`.

### Servers

| Config | Port | Backend | Hosts | Notes |
| --- | --- | --- | --- | --- |
| `server-llamacpp.yaml` | 9090 | llama.cpp | `llm` | Cross-platform default; serves infer, tool-calling, cot. |
| `server-vllm-gptq.yaml` | 9091 | vLLM GPTQ | `llm` | Linux/CUDA, `-Dvllm4j.venv`. |
| `server-vllm-awq.yaml` | 9093 | vLLM AWQ | `llm` | Linux/CUDA, `-Dvllm4j.venv`. |
| `server-safety.yaml` | 9092 | ONNX classifiers | `pii`, `toxicity`, `router` | No LLM, no pipelines — leaf models a client stitches in. |

```bash
java -Dgrpc.port=9090 -Dai.workspace.path=examples/modular/server-llamacpp.yaml \
     io.gravitee.singularitee.standalone.SingulariteeContainer
```

### Clients

A client declares `remote:` endpoints and `remote_*` models, then walks the pipeline DAG
**locally**, routing each model call over gRPC to whichever server hosts it.

| Config | `llm` | Classifiers | Pipelines |
| --- | --- | --- | --- |
| `client-llamacpp.yaml` | :9090 | — | infer, tool-calling |
| `client-cot.yaml` | :9090 | — | cot |
| `client-vllm-gptq.yaml` / `client-vllm-awq.yaml` | :9091 / :9093 | — | infer |
| `client-safety-llamacpp.yaml` | :9090 | `pii`/`toxicity` :9092 | pii-redact, toxicity-guard |
| `client-safety-vllm.yaml` | :9091 | `pii`/`toxicity` :9092 | pii-redact, toxicity-guard |
| `client-routing.yaml` | :9090 | `router` :9092 | routing |

The last three are the point: **one DAG, models on different servers**.

```bash
# Start server-safety.yaml (:9092) and server-vllm-gptq.yaml (:9091) first, then:
java io.gravitee.singularitee.cli.Main \
     --workspace examples/modular/client-safety-vllm.yaml \
     --pipeline pii-redact-pipeline
```

### Mutual TLS between servers

`server-safety-mtls.yaml` and `server-llm-mtls.yaml` are the servers table taken one step
further: **server-to-server** composition (the caller is itself a full server, not a
`client-*.yaml`), with the classifier hop protected by mutual TLS. The workspace only says
which endpoint is secured (`ssl: true`); the certificates come from `gravitee.yml` /
`GRAVITEE_*` variables, which the task targets supply:

```bash
task certs             # once — throwaway CA + server/client certificates (certs/, gitignored)
task run:mtls-safety   # shell 1 — callee, gRPC 9092, clientAuth REQUIRED
task run:mtls-llm      # shell 2 — caller, presents the client certificate
task mtls-check        # shell 3 — classify through the channel, then show an anonymous caller refused
```

See [Remote & Multi-Server](../docs/remote-and-multi-server/README.md) for the
`grpc.client.ssl.*` reference.

### Swapping backends

- **Bigger llama.cpp model** — in `server-llamacpp.yaml`, replace the
  `llama/llm-qwen3-0.6b.yaml` include with `llama/llm-mistral-7b.yaml`.
- **Zero-shot PII** — in `server-safety.yaml`, replace `classifier/pii-bert.yaml` with
  `classifier/pii-gliner.yaml` (publish its HF repo first), and swap the `pii-redact`
  pipeline's single `PII` trigger for that model's entity labels.
- **AWQ instead of GPTQ** — run `server-vllm-awq.yaml` (:9093) and point a client's `llm`
  server at :9093.

---

## `scripts/` — driving a running server

Python demos, run with [uv](https://docs.astral.sh/uv/) so there is no venv to manage.
Each needs a server already up in another shell.

| Script | Task | Needs | What it does |
| --- | --- | --- | --- |
| `openai_test.py` | `task chat` | any generation model | Chat completions + Responses API, streaming and not, incl. `reasoning_content`. |
| `classify_test.py` | `task classify` | any `classifier/` example | `/v1/classify` over real prose; **skips** models the server doesn't publish. |
| `vision_live.py` | `task vision` | `task run:vision` | Webcam → VLM, answer overlaid on the live feed. SPACE asks now, `q` quits. |
| `audio_ptt.py` | `task audio` | `task run:audio` | Push-to-talk: ENTER records, ENTER sends, reply streams back. |

```bash
task run:vision     # shell 1 — serves examples/llama/qwen3-vl-2b.yaml
task vision         # shell 2 — the demo
```

### Why the multimodal demos preflight

**A text-only model does not reject images or audio** — the server drops the media parts
silently and the model answers from the text alone, producing replies like *"I'm not able
to view the video stream myself"*. That reads like a broken demo when it is really the
wrong server, and `/v1/models` reports only `type: text-generation`, so nothing in the API
distinguishes a VLM from a plain LLM.

So both scripts probe before touching the camera or microphone: `vision_live.py` renders a
number into an image and asks the model to read it back; `audio_ptt.py` synthesizes speech
(`say` on macOS, `espeak` on Linux) and asks for the digits. If the answer doesn't come
back, they exit and tell you to start the right server. Bypass with `SKIP_PREFLIGHT=1` if
your model really is multimodal and just failed the probe.

## See also

- [Getting Started](../docs/getting-started/README.md) — build and run for the first time.
- [Workspaces](../docs/workspaces/README.md) — the full YAML reference behind every file here.
- [Pipelines](../docs/pipelines/README.md) — every step type and its config.
- [`observability/`](observability/README.md) — Prometheus + Grafana stack.
