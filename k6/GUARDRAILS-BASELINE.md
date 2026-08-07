# GliGuard — performance baseline (corpus-driven, 256-token paragraphs)

**Run:** 2026-07-21 · **Status:** ceiling measured at **~190 req/s**; healthy through
160 req/s; one model-coverage finding (language-dependent abstention) logged for offline
follow-up.

## What was tested

| | |
|---|---|
| Model | `gliguard` — `gravitee-io/gliner4j-gliguard-LLMGuardrails-300M`, `onnx_fp16` |
| Config | `threshold: 0.4`, `token_cap: 512`, 11 safety labels (benign … jailbreak_attempt) |
| Endpoint | `POST /v1/classify` (HTTP module, bearer auth) |
| Target | Remote deployment, single instance (L40S), `examples/classifier/guardrails-gliner.yaml` |
| Batcher | `EXECUTION_PROVIDER=cuda`, `BUCKET_TOKENS=256`, `LINGER_MS=25`, `BATCH_MAX=32`, `MAX_TOKENS=4096`, `ALLOW_SPINNING=0` |
| Harness | `guardrails-corpus-load-test.js` + `data/guardrails-corpus.json` (see k6/README) |

Payloads are **real multilingual paragraphs of 256–288 model tokens** (counted with
GliGuard's own tokenizer), built by `tools/build_toxicity_corpus.py` from
`gravitee-io/textdetox-multilingual-toxicity-dataset`: 15 languages × 40 clean + 40 toxic
(1–3 toxic sentences buried in non-toxic filler) = 1200 paragraphs, seed-deterministic.
Load-only: ground truth ships in the corpus but quality is left to a separate offline eval.

## Load profile & results

Open-model ramp, plateaus **40 / 80 / 120 / 160 / 200 req/s**. Per-30s forensics from the
raw stream (run-summary averages undersell — read the curve):

| Offered | Completed/s | Drops/s | Median | p95 |
|---|---:|---:|---:|---:|
| 40/s | 40 | 0 | 153 ms | 175 ms |
| 80/s | 80 | 0 | 164 ms | 192 ms |
| 120/s | 120 | 0 | 198 ms | 245 ms |
| 160/s | 160 | ~0 | 290 ms | **356 ms** |
| 200/s | **~190** | ~5–6 | 1.0 → 1.8 s | 2.1 s |

- **Healthy through 160 req/s** — completions track the offered rate exactly, p95 356 ms,
  essentially zero drops. Latency grows smoothly with rate (153 → 290 ms median: the
  micro-batcher packing larger batches), no queue spiral.
- **Ceiling ~190 req/s** — at 200/s offered, completions plateau at ~190/s and the queue
  builds (median climbs 1.0 → 1.8 s across the plateau; brief 400-VU pool exhaustion at
  ~7.5 min). 0% HTTP failures even saturated.
- Clean vs toxic paragraphs are indistinguishable on latency (~1 ms apart at every
  percentile) — classification cost does not depend on content class.
- vs `pii-ner` on the same corpus machinery (see PII-BASELINE.md): GliGuard's ceiling is
  ~1.65× higher (~190 vs ~115 req/s) — consistent with its far smaller prompt (11 labels vs
  a 42-entity schema) on the same encoder-bound workload.

## Numbers to beat

| Metric | Current best |
|---|---:|
| Ceiling (single instance) | **~190 req/s** |
| Median / p95 @ 120 req/s | 198 ms / 245 ms |
| Median / p95 @ 160 req/s | 290 ms / 356 ms |
| Errors (fully saturated) | 0% |

## Model-coverage finding — language-dependent abstention (not a perf issue)

14.4% of responses in the probe run returned HTTP 200 with an **empty `top_label`**: no
label — including `benign` — scored above the 0.4 threshold. The rate is strongly
language-dependent and ~0% for Latin-script European languages:

| Abstention rate | Languages |
|---|---|
| 0% | en, es, fr, it |
| 2–13% | ja, ar, de, zh, hin |
| 19–41% | am, uk, he, tt, ru, **hi (41% of toxic!)** |

The headline risk: **a third to nearly half of toxic Hindi/Russian/Hebrew paragraphs get no
classification at all** and would pass a guardrail unflagged. This is deterministic model
behavior (same paragraphs abstain every run), unrelated to load. Follow-ups: offline eval
against the corpus ground truth, threshold sweep for non-Latin scripts, and raising the gap
against the GliGuard model. The k6 script deliberately counts abstention as a *valid*
response (only missing/malformed bodies are errors), so perf numbers stay clean.

## Reproduce

```bash
cd k6
docker compose run --rm corpus-builder build_toxicity_corpus.py --split train
BASE_URL=https://<deployment> API_KEY=<key> \
SCENARIO=guardrails-corpus SCRIPT=guardrails-corpus-load-test.js \
RATE=200 RAMP_STEPS=5 STAGE_TIME=2m docker compose run --rm k6
```

Outputs: `reports/guardrails-corpus-summary.txt|json`, `reports/guardrails-corpus-raw-metrics.json`.
Per-plateau curve: bucket `http_reqs` / `dropped_iterations` / `guard_latency` points from the
raw stream into 30s windows. Abstention forensics: `checks` points with
`check == "has result"` (formerly `has top_label`), grouped by the `lang` tag.
