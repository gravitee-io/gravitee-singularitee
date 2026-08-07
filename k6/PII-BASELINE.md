# PII (pii-ner) — performance baseline (corpus-driven, 256-token paragraphs)

**Run:** 2026-07-21 · **Status:** ceiling measured at **~115 req/s**; at the ceiling itself
the model still serves with sub-second p95.

## What was tested

| | |
|---|---|
| Model | `pii-ner` — `gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi`, `onnx_fp16` |
| Config | `threshold: 0.4`, `token_cap: 512`, full 42-entity PII schema |
| Endpoint | `POST /v1/classify` (HTTP module, bearer auth) |
| Target | Remote deployment, single instance (L40S), isolated single-model workspace (`workspace-pii-ner.yaml`) |
| Batcher | `EXECUTION_PROVIDER=cuda`, `BUCKET_TOKENS=256`, `LINGER_MS=25`, `BATCH_MAX=32`, `MAX_TOKENS=4096`, `ALLOW_SPINNING=0` |
| Harness | `pii-corpus-load-test.js` + `data/pii-corpus.json` (see k6/README) |

Payloads are **real paragraphs of 256–288 model tokens** (counted with the model's own
tokenizer, not words), built by `tools/build_pii_corpus.py` from
`gravitee-io/pii-detection-dataset`: 200 clean + 200 PII-bearing paragraphs (1–3
span-bearing rows buried in span-free filler), seed-deterministic so runs are A/B-comparable
payload-for-payload. Load-only: ground-truth spans ship in the corpus (paragraph
coordinates) but detection quality is left to a separate offline eval.

## Load profile & results

Open-model ramp (`ramping-arrival-rate`), two probes with the same setup. Per-30s forensics
from the raw stream (run-summary averages undersell — read the curve):

### Run 1 — saturation probe (plateaus 40 → 200 req/s)

| Offered | Completed/s | Drops/s | Median | p95 |
|---|---:|---:|---:|---:|
| 40/s | 40 | 0 | 170 ms | ~0.3 s |
| 80/s | 80 | 0 | 230 ms | ~0.3 s |
| 120/s | ~115 | ~3 | 0.8 → 4 s | climbing |
| 160/s | ~115 | ~45 | 4.1 s | 4.3 s |
| 200/s | ~114 | ~86 | 4.1 s | 4.3 s |

**Hard ceiling ~115 req/s** — completions stay rock-flat at 114–115/s under 120, 160, and
200/s offered. Above the knee the 400-VU pool pins (Little's law: ~115/s × ~3.5 s ≈ 400) and
the 4-second latencies are pure queue wait. 0% errors even fully saturated (2 requests hit
the 60 s client timeout).

### Run 2 — behavior at and below the ceiling (plateaus 23 → 115 req/s)

| Offered | Completed/s | Drops/s | Median | p95 |
|---|---:|---:|---:|---:|
| 23/s | 23 | 0 | 160 ms | 190 ms |
| 46/s | 46 | 0 | 171 ms | 210 ms |
| 69/s | 69 | 0 | 200 ms | 260 ms |
| 92/s | 92 | 0 | ~275 ms | ~385 ms |
| 115/s | 114.5 | ~0 | ~470 ms | **650 ms** |

**Run at (not past) the ceiling, service stays healthy: 115 req/s with sub-second p95 and
essentially zero drops** (26 total, one transient blip; VUs peaked at 65/400). Run 1's
4-second latencies were entirely a product of offering *more* than 115/s — queue backlog,
not service degradation. Latency grows smoothly with rate (160 → 470 ms median: the
micro-batcher packing larger batches), though the median roughly doubling from 92 → 115/s
says the knee is near. Clean vs PII-bearing paragraphs are indistinguishable (~5 ms apart) —
decode cost is not entity-dominated at this size.

## Numbers to beat

| Metric | Current best |
|---|---:|
| Ceiling (single instance) | **~115 req/s (measured, hard plateau)** |
| Median / p95 @ 80 req/s | 230 ms / ~310 ms |
| Median / p95 @ 115 req/s (at ceiling) | ~470 ms / 650 ms |
| Errors (fully saturated) | 0% |

Next: the encoder-trim A/B (names-only schema — the PII prompt is ~95% schema tokens, so
this could push well past the ceiling on short inputs) and the offline accuracy eval against
the corpus ground truth. See also `GUARDRAILS-BASELINE.md` — GliGuard on the same machinery
ceilings at ~190 req/s (11 labels vs 42 entities, encoder-bound).

## Reproduce

```bash
cd k6
docker compose run --rm corpus-builder build_pii_corpus.py --per-lang 200
BASE_URL=https://<deployment> API_KEY=<key> \
SCENARIO=pii-corpus SCRIPT=pii-corpus-load-test.js \
RATE=115 RAMP_STEPS=5 STAGE_TIME=2m30s docker compose run --rm k6
```

Outputs: `reports/pii-corpus-summary.txt|json`, `reports/pii-corpus-raw-metrics.json`.
Per-plateau curve: bucket `http_reqs` / `dropped_iterations` / `pii_latency` points from the
raw stream into 30s windows.
