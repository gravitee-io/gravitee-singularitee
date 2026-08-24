# Examples

Every file here is a complete, runnable workspace. Pick one and start the server:

```bash
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml
./run-server.sh --list          # everything that can be run, by folder
```

Model weights download from HuggingFace on first start into
`~/.cache/gravitee-singularitee/models`, so the first run of a new workspace is slow and
every run after it is not.

With [go-task](https://taskfile.dev) (`brew install go-task`) there is a named target for
most examples; `task` on its own lists them all:

```bash
task run:qwen        # the reference model          task run:guard       # PII + toxicity pipeline
task run:vision      # vision model                 task run:tool-router # tool shortlisting
task run:pii         # PII classifier               task list            # every workspace
```

Server targets run in the foreground; the demo targets (`task vision`, `task audio`,
`task chat`, `task classify`) talk to an already-running server from a second shell.

```
examples/
├── llama/         llama.cpp GGUF models: the default backend, runs anywhere
├── vllm/          vLLM models, same families as llama/; Linux/CUDA first
├── classifier/    PII, toxicity, guardrails, intent: BERT (fine-tuned) and GLiNER (zero-shot)
├── embedding/     embedding models for retrieval and KNN routing
├── reranker/      cross-encoder rerankers
├── pipelines/     multi-step examples: guards, routers, chain-of-thought, repair loops, todos
├── modular/       the includes mechanism: compose one server from shared fragments
├── scripts/       Python demos that drive a running server
└── observability/ Prometheus + Grafana docker-compose stack
```

One backend per server. llama.cpp, vLLM and ONNX Runtime each load their own native
libraries; co-locating them in one JVM invites library conflicts and GPU memory contention.
Give each backend its own process and compose them over gRPC, which is what `modular/`
demonstrates.

---

## Models

Every generation workspace publishes one model under the logical id `llm` and one pipeline
named `agent`, so client config and `curl` commands carry over unchanged when you switch
files.

### `llama/`: llama.cpp (cross-platform)

| File | Model | Notes |
| --- | --- | --- |
| `qwen3-0.6b.yaml` | Qwen/Qwen3-0.6B-GGUF | The reference model. Every pipeline example uses it: seconds to download, runs on a laptop CPU. |
| `qwen2.5-0.5b.yaml` | Qwen2.5-0.5B-Instruct | Background tasks (titles, summaries). Cannot emit reasoning by construction. Not for tool loops. |
| `mistral-7b.yaml` | Mistral-7B-Instruct-v0.3 | No reasoning channel; answers directly. |
| `glm-4-9b.yaml` | GLM-4-9B | Markerless tool dialect (`glm-name-json`). |
| `gemma4-12b.yaml` / `gemma4-26b.yaml` | Gemma 4 | 26B is MoE (4B active). |
| `moonlight-16b.yaml` | Moonlight-16B | MoE. |
| `gpt-oss-20b.yaml` | gpt-oss-20b | MoE (3.6B active), native MXFP4, about 12 GB. Harmony channel dialect. |
| `qwen3-30b.yaml` / `qwen3.6-35b.yaml` | Qwen3 MoE | Larger hardware. |
| `qwen3-vl-2b.yaml` | Qwen3-VL-2B | Vision: accepts `image_url` content parts. |
| `voxtral-3b.yaml` | Voxtral-Mini-3B | Audio: accepts `input_audio` content parts. |
| `shieldstral-3b.yaml` | Shieldstral-1.0-3B | Safety classifier: answers one yes/no policy question with a single token; drive it with `max_tokens: 1`. Backs `pipelines/llm-guard.yaml`. |

The multimodal files differ from the text-only ones by exactly one key: `mmproj_path`, the
projection GGUF. Set it and the engine loads the multimodal context.

`n_ctx` is per sequence: total KV allocated is `n_ctx * n_seq_max`.

### `vllm/`: vLLM (Linux + CUDA)

| File | Model | Notes |
| --- | --- | --- |
| `qwen3-0.6b.yaml` | Qwen/Qwen3-0.6B | Dense 0.6B; the one to smoke-test a deployment with. |
| `mistral-7b.yaml` | mistralai/Mistral-7B-Instruct-v0.3 | Dense 7.2B, fits one 24 GB card at bf16. |
| `glm-4-9b.yaml` | THUDM/GLM-4-9B-0414 | Dense 9.4B. |
| `moonlight-16b.yaml` | moonshotai/Moonlight-16B-A3B-Instruct | MoE 16B on DeepSeek-V3; needs `trust_remote_code`, 8k window. |
| `gpt-oss-20b.yaml` | openai/gpt-oss-20b | MoE 21B, ships pre-quantized (MXFP4). Conservative sizing that loads on most cards. |
| `gpt-oss-20b-80gb.yaml` | openai/gpt-oss-20b | The same model sized for an 80 GB card: higher concurrency. |
| `gpt-oss-20b-mac.yaml` | openai/gpt-oss-20b | Apple Silicon (Metal): shorter window, chunked prefill, smaller memory share. |
| `gemma4-12b.yaml` | google/gemma-4-12B-it | Dense 12B with a vision tower. |
| `qwen3-vl-2b.yaml` | Qwen/Qwen3-VL-2B-Instruct | Vision; no mmproj to configure, unlike llama.cpp. |
| `qwen3.8-27b.yaml` | Qwen/Qwen3.8-27B-FP8 | Dense 27.8B, official FP8 checkpoint; fits one 40 GB card. |

A representative subset of the `llama/` families; quantised (AWQ/GPTQ) variants live as
fragments under `modular/models/vllm/`. Parameter counts and context windows in each file
were read from the checkpoints.

Two things differ from the llama.cpp equivalents:

- No file paths. vLLM takes a HuggingFace repo id and fetches the weights itself, so there is no GGUF filename and no `mmproj_path` for vision models.
- Multi-GPU is explicit. Models above roughly 40 GB of weights carry `tensor_parallel_size`. Set it once for a whole deployment with `GRAVITEE_AI_VLLM_TENSORPARALLELSIZE` instead and leave it out of the workspace.

The vLLM backend is far less exercised than llama.cpp. Run end to end so far: the two
quantized Qwen3 files and `gpt-oss-20b-mac.yaml` on Apple Silicon. The rest are configured
from checkpoint metadata and verified to parse, not to serve. They need a vLLM virtualenv on
the JVM command line (`-Dvllm4j.venv=...`; `run-server.sh` sets it, see
`scripts/setup-venv.sh`) unless you use the `vllm-cuda` image, which bundles one.

### `classifier/`

The axis here is fine-tuned versus zero-shot:

| File | id | Type | How you change what it detects |
| --- | --- | --- | --- |
| `pii-bert.yaml` | `pii` | `onnx_classifier` (TOKEN) | Retrain. Fast and cheap; detects only what it was trained on. |
| `pii-gliner.yaml` | `pii` | `gliner_ner` | Edit the entity list. Zero-shot; heavier per call. |
| `toxicity-bert.yaml` | `toxicity` | `onnx_classifier` (SEQUENCE) | Retrain. Multilingual, `{non-toxic, toxic}`. |
| `guardrails-gliner.yaml` | `gliguard` | `gliner_classifier` | Edit the safety labels. A policy change is a YAML edit, not a training run. |
| `intent-gliner.yaml` | `router` | `gliner_classifier` | Edit the intent labels. Backs `pipelines/gliner-router.yaml`. |

TOKEN mode labels individual spans (which is what makes redaction possible); SEQUENCE mode
gives the whole input one label.

### `embedding/` and `reranker/`

| File | Model | Notes |
| --- | --- | --- |
| `embedding/bge-m3.yaml` | BAAI/bge-m3 | Multilingual (100+ languages), 1024-dim. |
| `embedding/bge-small-en.yaml` | BAAI/bge-small-en-v1.5 | English, 384-dim, about 130 MB. |
| `reranker/bge-reranker-base.yaml` | BAAI/bge-reranker-base | Cross-encoder. |

Embeddings and rerankers are complements: embed to retrieve the top candidates cheaply, then
rerank those to the final few. A cross-encoder reads `(query, document)` together, which ranks
better but cannot be precomputed into an index. `pooling_mode` and `normalize` must match the
model card (BGE wants `CLS` plus normalize; most sentence-transformers want `MEAN`).

---

## Pipelines

Multi-step examples. Unless noted they bind `llm` to Qwen3-0.6B so they download in seconds
and run on a laptop; swap in any file from `llama/` for better output.

| File | DAG | What it demonstrates |
| --- | --- | --- |
| `guard.yaml` | `toxicity_guard(reject) -> pii_guard(redact) -> generate` | Two guard actions, deliberately ordered: reject first so toxic input never reaches the model, redact second so the LLM only ever sees masked text. |
| `llm-guard.yaml` | `input_guard(Shieldstral, reject) -> generate(gpt-oss-20b)` | An LLM-as-judge guard: the policy is a natural-language question in the file, answered with one token. |
| `tool-router.yaml` | `select_tools -> agent` | Shortlisting the caller's tools with a zero-shot classifier before injecting their schemas. |
| `gliner-router.yaml` | `route -> respond_{code,cooking,finance,general}` | `strategy: classifier`: the top label picks the branch. |
| `embedding-router.yaml` | `route -> respond_{support,sales,general}` | `strategy: embedding_knn`: the nearest reference sentence picks the branch. |
| `cot.yaml` | `reason -> evaluate -> loop_gate -> answer / fallback` | A quality gate as a graph edge: loop back and refine until an evaluator step is satisfied. |
| `tool-repair.yaml` | `generate -> repair_gate -> done / fallback` | Tool-call self-repair on gpt-oss-20b: loop back with the parse error when `generate.tool_parse_failed` is true instead of shipping a malformed call as prose. |
| `tool-repair-escalate.yaml` | `generate(Qwen3-0.6B) -> repair_gate -> done / escalate(gpt-oss-20b)` | The fallback is a bigger model: two repair attempts on the small one, then the same conversation, corrective turns included, goes to gpt-oss-20b. |
| `todo-agent.yaml` | `plan -> apply_plan -> work -> track -> work_gate -> summarize` | Plan-and-execute: the model decomposes the task through the server-executed `set_todos` / `complete_todo` tools, a loop works through items until `todos.remaining` is 0, and progress streams as `gravitee.progress` events. |

### Tool router

An agent with 60 tools pays for 60 JSON schemas in every prompt before the user's question is
read, and a small model's accuracy degrades as that list grows.

The `tool_select` step classifies the last user message against the tools the caller sent on
this request. Nothing is configured ahead of time: each tool's name and description become
zero-shot labels at request time, so the same pipeline works for any client with any tool
set. Tools are scored in batches, each batch carrying a synthetic `none_of_these` label; a
tool survives when it clears `threshold` and outscores `none_of_these`. The infer step then
injects only the survivors.

- Every batch electing `none_of_these` (a plain conversational turn) leaves the shortlist empty and injects no tools at all. `always_include` is unioned in only when the shortlist is non-empty.
- A failed classify call fails open: that batch's tools are all included.

### Choosing a router

| | `gliner-router` (classifier) | `embedding-router` (KNN) |
| --- | --- | --- |
| You write | Label plus description | Example sentences per route |
| Matching | Exact string equality against the top label | Cosine similarity, single nearest sentence wins |
| Best when | Routes are a small, named, stable set | Routes are fuzzy, overlapping, or grown from real user phrasings |

With `strategy: classifier`, every `rules[].label` must appear verbatim in the model's
`labels[].name`; matching is string equality. A mismatch is the usual reason a router always
takes `default_step`.

With `embedding_knn`, reference sentences are embedded once at load time and cached; the
single nearest sentence wins, not a per-rule average. One sharp example beats five vague ones,
and adding weak examples to a rule cannot dilute it.

---

## `modular/`: composing servers from shared fragments

Everything above is self-contained. `modular/` shows the other way: small fragments that
several servers include, so one model definition is written once and reused.

```
modular/
├── models/
│   ├── llama/      llm-qwen3-0.6b.yaml, llm-mistral-7b.yaml
│   ├── vllm/       llm-qwen3-0.6b.yaml, llm-qwen3-awq.yaml, llm-qwen3-gptq.yaml, ... (9)
│   └── classifier/ pii-bert.yaml, pii-gliner.yaml, toxicity-bert.yaml, router-gliner.yaml
├── pipelines/      infer, tool-calling, pii-redact, toxicity-guard, routing, cot, reasoning
├── templates/      tool-system.yaml, glm-4-9b-compact.jinja
├── server-*.yaml   one backend per server: includes a model subset and its pipelines
└── client-*.yaml   remote model proxies plus the pipelines to run locally
```

`includes:` paths resolve against the `models/`, `pipelines/` and `templates/` subdirectories
of the including file's own folder, which is why the servers and clients sit at the top of
`modular/` and why the model folders nest inside `models/`:

```yaml
workspace:
  name: server-llamacpp
  includes:
    models:
      - llama/llm-qwen3-0.6b.yaml     # modular/models/llama/llm-qwen3-0.6b.yaml
    pipelines:
      - infer.yaml                    # modular/pipelines/infer.yaml
```

Globs work too (`llama/*.yaml`), expanded alphabetically. Fragments are not runnable on their
own; `run-server.sh` refuses them.

### The logical-id convention

Fragments never name a concrete model in a pipeline; they agree on stable logical ids, so the
same pipeline runs unchanged on any backend:

| id | Role | Fragments providing it |
| --- | --- | --- |
| `llm` | Text generation | `llama/llm-qwen3-0.6b`, `llama/llm-mistral-7b`, and 13 `vllm/llm-*` fragments covering the same families as `examples/vllm/` |
| `pii` | PII detection | `classifier/pii-bert`, `classifier/pii-gliner` |
| `toxicity` | Toxicity | `classifier/toxicity-bert` |
| `router` | Intent routing | `classifier/router-gliner` |

Most fragments are included by no server or client: they are a swap-in menu, kept loading
(`ExamplesWorkspaceTest`) so any of them can replace the one a server names.
Several fragments share the id `llm` (and `pii`). Servers list model files explicitly; a glob
such as `models: ["*.yaml"]` would load duplicate ids into one workspace. Include exactly one
`llm-*` and one `pii-*`.

### Servers

| Config | Port | Backend | Hosts | Notes |
| --- | --- | --- | --- | --- |
| `server-llamacpp.yaml` | 9090 | llama.cpp | `llm` | Cross-platform default; serves infer, tool-calling, cot. |
| `server-vllm-gptq.yaml` | 9091 | vLLM GPTQ | `llm` | Linux/CUDA, needs the vLLM venv. |
| `server-vllm-awq.yaml` | 9093 | vLLM AWQ | `llm` | Linux/CUDA, needs the vLLM venv. |
| `server-safety.yaml` | 9092 | ONNX classifiers | `pii`, `toxicity`, `router` | No LLM, no pipelines: leaf models a client stitches in. |

```bash
./run-server.sh --workspace examples/modular/server-llamacpp.yaml
GRAVITEE_GRPC_PORT=9092 ./run-server.sh --port 8092 --workspace examples/modular/server-safety.yaml
```

### Clients

A client declares `remote:` endpoints and `remote_*` models, then walks the pipeline DAG
locally, routing each model call over gRPC to whichever server hosts it.

| Config | `llm` | Classifiers | Pipelines |
| --- | --- | --- | --- |
| `client-llamacpp.yaml` | :9090 | none | infer, tool-calling |
| `client-cot.yaml` | :9090 | none | cot |
| `client-vllm-gptq.yaml` / `client-vllm-awq.yaml` | :9091 / :9093 | none | infer |
| `client-safety-llamacpp.yaml` | :9090 | `pii` / `toxicity` :9092 | pii-redact, toxicity-guard |
| `client-safety-vllm.yaml` | :9091 | `pii` / `toxicity` :9092 | pii-redact, toxicity-guard |
| `client-routing.yaml` | :9090 | `router` :9092 | routing |

The last three are the point: one DAG, models on different servers.

```bash
# Start server-safety.yaml (:9092) and server-vllm-gptq.yaml (:9091) first, then run the
# client workspace as a server of its own, on ports the servers are not using:
GRAVITEE_GRPC_PORT=9190 ./run-server.sh --port 8180 \
     --workspace examples/modular/client-safety-vllm.yaml
```

The DAG runs in the client process while every model call travels over gRPC. An application
can also embed `ClientPipelineExecutor` (module `engine-remote`) instead of running a server.
A client fails fast at startup when its endpoints are not up.

### Mutual TLS between servers

`server-safety-mtls.yaml` and `server-llm-mtls.yaml` take the servers table one step further:
server-to-server composition (the caller is itself a full server, not a `client-*.yaml`),
with the classifier hop protected by mutual TLS. The workspace only says which endpoint is
secured (`ssl: true`); the certificates come from `gravitee.yml` / `GRAVITEE_*` variables,
which the task targets supply:

```bash
task certs             # once: throwaway CA plus server/client certificates (certs/, gitignored)
task run:mtls-safety   # shell 1: callee, gRPC 9092, clientAuth REQUIRED
task run:mtls-llm      # shell 2: caller, presents the client certificate
task mtls-check        # shell 3: classify through the channel, then show an anonymous caller refused
```

See [Remote and multi-server](../docs/guides/remote-and-multi-server/README.md) and the
`grpc.client.ssl.*` block in [Configuration](../docs/reference/configuration.md).

### Swapping backends

- Bigger llama.cpp model: in `server-llamacpp.yaml`, replace the `llama/llm-qwen3-0.6b.yaml` include with `llama/llm-mistral-7b.yaml`.
- Zero-shot PII: in `server-safety.yaml`, replace `classifier/pii-bert.yaml` with `classifier/pii-gliner.yaml` and swap the `pii-redact` pipeline's single `PII` trigger for that model's entity labels.
- AWQ instead of GPTQ: run `server-vllm-awq.yaml` (:9093) and point a client's `llm` server at :9093.

---

## `scripts/`: driving a running server

Python demos, run with [uv](https://docs.astral.sh/uv/) so there is no venv to manage. Each
needs a server already up in another shell.

| Script | Task | Needs | What it does |
| --- | --- | --- | --- |
| `openai_test.py` | `task chat` | any generation model | Chat completions and the Responses API, streaming and not, including `reasoning_content`. |
| `classify_test.py` | `task classify` | any `classifier/` example | `/v1/classify` over real prose; skips models the server does not publish. |
| `vision_live.py` | `task vision` | `task run:vision` | Webcam to VLM, answer overlaid on the live feed. SPACE asks now, `q` quits. |
| `audio_ptt.py` | `task audio` | `task run:audio` | Push-to-talk: ENTER records, ENTER sends, reply streams back. |
| `guard_live.py`, `image_guard.py` | | the Shieldstral safety model | Webcam frames, or image files, judged against a yes/no policy question (`QUERY`, `INSTRUCT`); `image_guard.py` exits 1 when any image is flagged. |

```bash
task run:vision     # shell 1: serves examples/llama/qwen3-vl-2b.yaml
task vision         # shell 2: the demo
```

### Why the multimodal demos preflight

The server advertises what each entry accepts (`/v1/models` reports `input_modalities`) and
refuses media the target cannot read with `400 unsupported_modality`. Detection comes from
the loaded projector, so a model that loads without one is text-only as far as the server is
concerned. Both scripts probe before touching the camera or microphone anyway:
`vision_live.py` renders a number into an image and asks the model to read it back;
`audio_ptt.py` synthesizes speech (`say` on macOS, `espeak` on Linux) and asks for the
digits. If the answer does not come back they exit and say which server to start. Bypass with
`SKIP_PREFLIGHT=1` when your model is multimodal and merely failed the probe.

## See also

- [Getting started](../docs/getting-started/README.md): build and run for the first time.
- [Workspaces](../docs/workspaces/README.md): the YAML format behind every file here.
- [Model types](../docs/reference/models/README.md) and [step types](../docs/reference/steps/README.md): every key.
- [Guides](../docs/README.md#guides): guards, routing, loops, tool calling, todos, multimodal, multi-server.
- [`observability/`](observability/README.md): Prometheus and Grafana stack.
