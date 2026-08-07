# k6 perf tests — /v1/classify (PII · Guardrails), corpus-driven

## Files

- `workspace-pii-ner.yaml` / `workspace-gliguard.yaml` — single-model workspaces (`onnx_fp16` variant, `token_cap: 512`) isolating the model under test.
- `guardrails-corpus-load-test.js` / `pii-corpus-load-test.js` — corpus-driven load tests replaying real 256-token paragraphs (see below).
- `tools/build_toxicity_corpus.py` / `tools/build_pii_corpus.py` — corpus builders (`uv run`, PEP 723 inline deps).
- `data/` — generated corpora (`guardrails-corpus.json`, `pii-corpus.json`), mounted read-only at `/data` in the k6 container (git-ignored; regenerate with the corpus-builder).
- `docker-compose.yml` — runs k6 in Docker with reports mounted to `./reports/`; select the script with `SCRIPT=` (default `pii-corpus-load-test.js`).
- `PII-BASELINE.md` / `GUARDRAILS-BASELINE.md` — measured baselines & repro commands.

## Start the server

```bash
GRAVITEE_AI_WORKSPACE_PATH=$(pwd)/k6/workspace-pii-ner.yaml \   # or workspace-gliguard.yaml
GRAVITEE_HTTP_ENABLED=true GRAVITEE_HTTP_PORT=8090 \
<start Singularitee>
```

### Reference deployment env (remote perf target)

The env used on the deployed perf target (Koyeb-style; `{{ secret.* }}` are platform
secrets — never commit real values):

```bash
GRAVITEE_AI_HUGGINGFACE_TOKEN={{ secret.HF_TOKEN }}
GRAVITEE_AI_WORKSPACE_PATH=/opt/graviteeio-singularitee/examples/classifier/guardrails-gliner.yaml   # model under test

# GLiNER micro-batcher — perf-tuned
GRAVITEE_GLINER_EXECUTION_PROVIDER=cuda
GRAVITEE_GLINER_ALLOW_SPINNING=0
GRAVITEE_GLINER_BATCH_BUCKET_TOKENS=256    # matches the ~256-token corpus paragraphs (one bucket)
GRAVITEE_GLINER_BATCH_LINGER_MS=25
GRAVITEE_GLINER_BATCH_MAX=32
GRAVITEE_GLINER_BATCH_MAX_TOKENS=4096

# HTTP API (the k6 target) — API_KEY on the k6 side = this bearer token
GRAVITEE_HTTP_ENABLED=true
GRAVITEE_HTTP_PORT=8090
GRAVITEE_HTTP_AUTH_ENABLED=true
GRAVITEE_HTTP_AUTH_TYPE=bearer
GRAVITEE_HTTP_AUTH_TOKENS_0={{ secret.singularitee-api-key }}

# gRPC API (not exercised by these tests)
GRAVITEE_GRPC_PORT=9090
GRAVITEE_GRPC_AUTH_ENABLED=true
GRAVITEE_GRPC_AUTH_TYPE=basic
GRAVITEE_GRPC_AUTH_USERS_ADMIN={{ secret.singularitee-api-key }}

JAVA_OPTS=-Djava.net.preferIPv4Stack=true
```

To capture a JFR profile during a run, swap in (as `JAVA_OPTS`; the `BCK_` prefix parks it):

```bash
JAVA_OPTS=-XX:StartFlightRecording=delay=3m,duration=15m,filename=/tmp/pii30.jfr,settings=profile,dumponexit=true -XX:FlightRecorderOptions=stackdepth=256 -Djava.net.preferIPv4Stack=true
```

`delay=3m` skips warmup/ramp; `duration=15m` covers a full default profile; the file lands in
`/tmp` on the instance (`dumponexit` also flushes it on shutdown).

## Build the corpora

Real dataset paragraphs of **~256 model tokens** (counted with each model's own
`tokenizer.json`, not words). Deterministic for a given `--seed`, so runs are
A/B-comparable payload-for-payload:

```bash
cd k6
docker compose run --rm corpus-builder build_toxicity_corpus.py --split train   # → data/guardrails-corpus.json
docker compose run --rm corpus-builder build_pii_corpus.py --per-lang 200       # → data/pii-corpus.json
```

(No local Python needed — the `corpus-builder` service is the official uv image with the
HF/uv caches in named volumes; `cd k6/tools && uv run build_….py` works too if you have uv.)

- **Guardrails** — `gravitee-io/textdetox-multilingual-toxicity-dataset` sentences packed
  into monolingual paragraphs (15 languages × 40 clean + 40 toxic = 1200 items; toxic
  paragraphs bury 1–3 toxic sentences in non-toxic filler). Latency metric
  `guard_latency{expected,lang}`.
- **PII** — `gravitee-io/pii-detection-dataset` (English) rows packed the same way
  (200 clean + 200 pii paragraphs, ground-truth spans offset-shifted into paragraph
  coordinates). Latency metric `pii_latency{expected}`.

The tests measure latency/throughput only — detection quality is a property of
model + corpus, not of load, and is left to a separate offline eval (the corpora keep
their ground truth — toxic/clean labels and PII spans — for that purpose).

## Run the load tests (one scenario per run)

```bash
cd k6
mkdir -p reports
SCENARIO=pii-corpus        SCRIPT=pii-corpus-load-test.js        docker compose run --rm k6
SCENARIO=guardrails-corpus SCRIPT=guardrails-corpus-load-test.js docker compose run --rm k6
```

Reference remote run (the profile used for the baselines):

```bash
SCENARIO=pii-corpus SCRIPT=pii-corpus-load-test.js \
RATE=115 RAMP_STEPS=5 STAGE_TIME=2m30s \
BASE_URL=https://<server-host> API_KEY=<key> \
docker compose run --rm k6
```

Tunables (env or `.env`): `SCENARIO` (names the report files), `BASE_URL` (default
`http://host.docker.internal:8090`), `API_KEY` (sent as `Authorization: Bearer …` when set),
`RATE` (default 100 req/s), `RAMP_STEPS` (default 4), `STAGE_TIME` (default `3m`),
`RAMP_TIME` (default `30s`), `MAX_VUS` (default 400 — VU pool backing the arrival rate),
`MODEL` (default `pii-ner` / `gliguard`), `CORPUS` (path inside the container, default
`/data/<name>-corpus.json`).

## Load profile

Open-model `ramping-arrival-rate`: requests are started at a target rate independent of server latency, ramping 0 → `RATE/RAMP_STEPS` → … → `RATE` req/s, holding `STAGE_TIME` at each plateau with `RAMP_TIME` ramps in between, then back to 0. If you see `dropped_iterations` in the summary, the `MAX_VUS` pool was exhausted — the server can't sustain the rate at its current latency, and latencies at that plateau are queue-dominated (read the raw per-second curve, not the run average).

## Reports (in `./reports/`)

- `<name>-summary.txt` / `<name>-summary.json` — end-of-run summary incl. per-tag thresholds.
- `<scenario>-raw-metrics.json` — full k6 JSON metric stream. Per-plateau forensics: bucket
  `http_reqs` / `dropped_iterations` / `pii_latency`|`guard_latency` points into 30s windows;
  filter by tags, e.g.:

```bash
jq -r 'select(.type=="Point" and .metric=="guard_latency" and .data.tags.lang=="hi") | .data.value' reports/guardrails-corpus-raw-metrics.json
```
