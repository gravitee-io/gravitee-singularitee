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
 * Corpus-driven load + quality test for PII detection (/v1/classify).
 *
 * Replays real ~256-token paragraphs built from gravitee-io/pii-detection-dataset
 * by tools/build_pii_corpus.py — realistic payloads instead of synthetic filler.
 * Latency is tagged {expected: pii | clean} so entity-bearing and entity-free
 * paragraphs get separate summary cells (span decoding is per-entity work, so the
 * two can behave differently under load). Detection quality is NOT evaluated here:
 * it is a property of model + corpus, not of load, and belongs in an offline eval
 * (the corpus keeps its ground-truth spans for that purpose).
 *
 *   docker compose run --rm k6      # with SCRIPT=pii-corpus-load-test.js
 *
 * Env (same load knobs as classify-load-test.js):
 *   BASE_URL, API_KEY, RATE, RAMP_STEPS, STAGE_TIME, RAMP_TIME, MAX_VUS
 *   CORPUS  default /data/pii-corpus.json
 *   MODEL   default pii-detector (regex ∪ NER; also works with pii-ner / pii-regex)
 */

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8090';
const API_KEY = __ENV.API_KEY || '';
const RATE = Number(__ENV.RATE || 100);
const RAMP_STEPS = Number(__ENV.RAMP_STEPS || 4);
const STAGE_TIME = __ENV.STAGE_TIME || '3m';
const RAMP_TIME = __ENV.RAMP_TIME || '30s';
const MAX_VUS = Number(__ENV.MAX_VUS || 400);
const CORPUS = __ENV.CORPUS || '/data/pii-corpus.json';
const MODEL = __ENV.MODEL || 'pii-ner';

const corpus = new SharedArray('corpus', () => JSON.parse(open(CORPUS)).items);

// ── metrics ─────────────────────────────────────────────────────────────────

const latency = new Trend('pii_latency', true);
const errors = new Rate('pii_errors');

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
    pii_corpus: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: rampStages(),
      preAllocatedVUs: Math.min(50, MAX_VUS),
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    'pii_latency{expected:pii}': ['p(95)<5000'],
    'pii_latency{expected:clean}': ['p(95)<5000'],
    pii_errors: ['rate<0.01'],
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
  const tags = { expected: item.expected, model: MODEL };

  const headers = { 'Content-Type': 'application/json' };
  if (API_KEY) {
    headers['Authorization'] = `Bearer ${API_KEY}`;
  }
  const res = http.post(
    `${BASE_URL}/v1/classify`,
    JSON.stringify({ model: MODEL, input: item.text }),
    { headers, tags, timeout: '60s' }
  );

  let result = null;
  try {
    result = res.json('results.0');
  } catch (_) {
    /* not JSON */
  }

  const ok = check(
    res,
    {
      'status is 200': r => r.status === 200,
      'has result': () => result !== null && result !== undefined,
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
    ['/reports/pii-corpus-summary.json']: JSON.stringify(data, null, 2),
    ['/reports/pii-corpus-summary.txt']: textSummary(data, { indent: ' ', enableColors: false }),
  };
}
