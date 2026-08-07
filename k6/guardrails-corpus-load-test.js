/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

/*
 * Corpus-driven load + quality test for GliGuard (/v1/classify, model "gliguard").
 *
 * Unlike classify-load-test.js (synthetic filler text sized in words), this replays
 * real 256-token paragraphs built from the textdetox multilingual toxicity dataset
 * by tools/build_toxicity_corpus.py — realistic multilingual payloads instead of
 * synthetic filler. Latency is tagged {expected: toxic | clean, lang} for per-cell
 * summary breakdowns. Detection quality is NOT evaluated here: it is a property of
 * model + corpus, not of load, and belongs in an offline eval (the corpus keeps its
 * ground-truth labels for that purpose).
 *
 *   docker compose run --rm k6      # with SCRIPT=guardrails-corpus-load-test.js
 *
 * Env (same load knobs as classify-load-test.js):
 *   BASE_URL, API_KEY, RATE, RAMP_STEPS, STAGE_TIME, RAMP_TIME, MAX_VUS
 *   CORPUS  default /data/guardrails-corpus.json
 *   MODEL   default gliguard
 */

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8090';
const API_KEY = __ENV.API_KEY || '';
const RATE = Number(__ENV.RATE || 100);
const RAMP_STEPS = Number(__ENV.RAMP_STEPS || 4);
const STAGE_TIME = __ENV.STAGE_TIME || '3m';
const RAMP_TIME = __ENV.RAMP_TIME || '30s';
const MAX_VUS = Number(__ENV.MAX_VUS || 400);
const CORPUS = __ENV.CORPUS || '/data/guardrails-corpus.json';
const MODEL = __ENV.MODEL || 'gliguard';

const corpus = new SharedArray('corpus', () => JSON.parse(open(CORPUS)).items);

// ── metrics ─────────────────────────────────────────────────────────────────

const latency = new Trend('guard_latency', true);
const errors = new Rate('guard_errors');

// ── load profile (open model, arrival-rate driven) ──────────────────────────

function rampStages() {
  const stages = [];
  for (let step = 1; step <= RAMP_STEPS; step++) {
    const target = Math.max(1, Math.round((RATE * step) / RAMP_STEPS));
    stages.push({ duration: RAMP_TIME, target });
    stages.push({ duration: STAGE_TIME, target });
  }
  stages.push({ duration: RAMP_TIME, target: 0 });
  return stages;
}

export const options = {
  scenarios: {
    guardrails_corpus: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: rampStages(),
      preAllocatedVUs: Math.min(50, MAX_VUS),
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    'guard_latency{expected:toxic}': ['p(95)<5000'],
    'guard_latency{expected:clean}': ['p(95)<5000'],
    guard_errors: ['rate<0.01'],
  },
};

// Deterministic per-iteration hash (Knuth multiplicative) so replays are
// reproducible and corpus order is decorrelated from arrival order.
function itemFor(iter) {
  const hash = ((iter + 1) * 2654435761) >>> 0;
  return corpus[hash % corpus.length];
}

export default function () {
  const item = itemFor(__ITER * MAX_VUS + __VU);
  const tags = { expected: item.expected, lang: item.lang, model: MODEL };

  const headers = { 'Content-Type': 'application/json' };
  if (API_KEY) {
    headers['Authorization'] = `Bearer ${API_KEY}`;
  }
  const res = http.post(
    `${BASE_URL}/v1/classify`,
    JSON.stringify({ model: MODEL, input: item.text }),
    { headers, tags, timeout: '60s' }
  );

  let topLabel = null;
  try {
    topLabel = res.json('results.0.top_label');
  } catch (_) {
    /* not JSON */
  }

  const ok = check(
    res,
    {
      'status is 200': r => r.status === 200,
      // An empty top_label is a valid response (model abstained — no label above
      // threshold); only a missing/malformed body counts as an error.
      'has result': () => typeof topLabel === 'string',
    },
    tags
  );

  latency.add(res.timings.duration, tags);
  errors.add(!ok, tags);
}

// ── reports (written to the /reports volume) ───────────────────────────────

export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    ['/reports/guardrails-corpus-summary.json']: JSON.stringify(data, null, 2),
    ['/reports/guardrails-corpus-summary.txt']: textSummary(data, { indent: ' ', enableColors: false }),
  };
}
