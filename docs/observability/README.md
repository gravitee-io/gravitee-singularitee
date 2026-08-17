# Observability

> OpenTelemetry spans per RPC/pipeline/step/model-call, plus Micrometer metrics scraped by Prometheus at `/_node/metrics/prometheus`.

## Overview
Observability is opt-in and configured in `config/gravitee.yml`. **Tracing**
(`services.opentelemetry`) makes the gRPC server open a `SERVER`-kind span per RPC —
continuing any inbound W3C `traceparent` — under which the engine nests
`ai.pipeline → ai.step → ai.model.<op>` spans for pipeline runs, and `ai.infer` /
`ai.classify` / `ai.embed` spans for direct RPCs; everything is exported via OTLP to a
collector (Jaeger, Tempo, …). **Metrics** (`services.metrics`) bind a Micrometer registry
whose meters — request counters, latency timers, per-model-call timers, token counters, and
optional `gpu_*` gauges — are exposed in Prometheus format on the management (core) HTTP
port. `examples/observability/` is a turnkey Prometheus + Grafana stack with an
auto-provisioned **GPU & Inference** dashboard.

## Key types
- `InferenceMetrics` (`io.gravitee.singularitee.metrics`) — Micrometer recorder wrapping a possibly-`null` `MeterRegistry`; every `record*` call is a no-op when metrics are disabled (CLI, tests).
- `ServiceInstrumentation` (`io.gravitee.singularitee.service`) — wraps unary gRPC methods: opens the `ai.<op>` child span on the request context and records `ai_<op>_requests_total` + `ai_<op>_latency_seconds`.
- `GrpcServerComponent` — opens the `SERVER` span per gRPC call, continuing an inbound `traceparent` (the HTTP API's `HttpApiServerComponent` does the same per HTTP request).
- `PipelineExecutor` — opens the `ai.pipeline` span as a child of the server span.
- `StepDispatcher` / `ModelBoundStepExecutor` — open the `ai.step` span per step and the `ai.model.<op>` span (timed via `InferenceMetrics.recordModelCall`) per engine call.
- `io.gravitee.node.api.opentelemetry.Tracer` — gravitee-node tracer; a no-op instance when tracing is disabled, so instrumentation code never null-checks.

## Usage
Enable both services in `config/gravitee.yml` (metrics are on by default in the shipped
distribution; tracing is off):

```yaml
services:
  metrics:
    enabled: true
    prometheus:
      enabled: true

  monitoring:
    gpu:
      enabled: true        # gpu_* gauges — needs nvidia-smi on PATH (CUDA image)
      delay: 5000
      unit: MILLISECONDS

  opentelemetry:
    enabled: true
    verbose: false         # add request/response attributes to spans
    exporter:
      endpoint: http://localhost:4317
      protocol: grpc       # grpc (4317) | http/protobuf (4318)
```

Every key is overridable with env vars, e.g. `GRAVITEE_SERVICES_MONITORING_GPU_ENABLED=true`.

Scrape the metrics endpoint on the management port (basic auth, default `admin:adminadmin`):

```bash
curl -s -u admin:adminadmin http://localhost:18092/_node/metrics/prometheus | grep '^ai_'
```

Run the example Prometheus + Grafana stack:

```bash
docker compose -f examples/observability/docker-compose.yml up -d
# Grafana    → http://localhost:3000  (admin / admin) — dashboard "Singularitee → GPU & Inference"
# Prometheus → http://localhost:9091
```

The bundled Prometheus scrapes `host.docker.internal:18092` (the server on the Docker host);
edit `examples/observability/prometheus/prometheus.yml` to scrape another target.

## Options

### `services.opentelemetry`
| Key | Default | Purpose |
| --- | --- | --- |
| `enabled` | `false` | Emit spans (SERVER span per RPC + nested `ai.*` spans). |
| `verbose` | `false` | Add request/response attributes to spans. |
| `exporter.endpoint` | `http://localhost:4317` | OTLP collector endpoint. |
| `exporter.protocol` | `grpc` | `grpc` (4317) or `http/protobuf` (4318). |
| `exporter.compression` | none | e.g. `gzip`. |
| `exporter.timeout` | — | Export timeout in ms. |
| `exporter.headers` | — | Extra OTLP headers (list of `name=value`, e.g. auth). |
| `exporter.ssl.trustAll` / `exporter.ssl.verifyHost` | `false` / `true` | TLS settings for the exporter. |

### Metrics (Prometheus exposition names)
| Metric | Type | Labels | Purpose |
| --- | --- | --- | --- |
| `ai_infer_requests_total` | counter | `model`,`status` | Infer RPCs (`status` = `success` \| `error` \| `not_found` \| `cancelled`). |
| `ai_infer_latency_seconds` | timer | `model` | End-to-end Infer latency. |
| `ai_classify_requests_total` / `ai_classify_latency_seconds` | counter / timer | `model`(,`status`) | Same pair for Classify. |
| `ai_embed_requests_total` / `ai_embed_latency_seconds` | counter / timer | `model`(,`status`) | Same pair for Embed. |
| `ai_pipeline_requests_total` | counter | `pipeline`,`status` | InferPipeline RPCs — `pipeline` label kept distinct from `model`. |
| `ai_pipeline_latency_seconds` | timer | `pipeline` | End-to-end pipeline latency. |
| `ai_model_call_seconds` | timer | `model`,`op` | Duration of each individual model-engine call. |
| `ai_tokens_total` | counter | `model`,`kind` | Tokens by kind (`prompt` \| `completion` \| `reasoning` \| `tool`); non-positive counts skipped. |
| `ai_finish_reasons_total` | counter | `model`,`reason` | Infer-step completions by finish reason (`stop` \| `length` \| `tool_calls` \| `stalled` \| `cancelled` \| …) — makes silent truncations graphable. |
| `ai_failure_signals_total` | counter | `source`,`signal` | Detected failure signals: `tool_parse_failed` \| `thinking_unclosed` (source = model id), `loop_max_iterations` (source = step id), `guard_blocked` (source = pipeline id). |
| `gpu_utilization_percent`, `gpu_memory_utilization_percent`, `gpu_memory_total_bytes`, `gpu_memory_used_bytes`, `gpu_temperature_celsius`, `gpu_power_watts` | gauges | `gpu`,`name`,`uuid` | From `services.monitoring.gpu` (nvidia-smi). |

## Notes
- **Span hierarchy**: `SERVER` (one per gRPC call) → `ai.pipeline` → `ai.step` (one per DAG step) → `ai.model.<op>` (one per engine call). Direct RPCs skip the pipeline levels and get a single `ai.infer` / `ai.classify` / `ai.embed` child span carrying a `model.id` attribute.
- **Trace continuation**: an inbound W3C `traceparent` header is honored, so an API gateway calling Singularitee sees one continuous trace across processes.
- **Client-side pipeline execution is not traced**: only the server-side path emits spans; `ClientPipelineExecutor` runs with the no-op tracer.
- **Meter names are dotted in code** (`ai.infer.requests`) — the Prometheus registry converts `.`→`_` and appends `_total`/`_seconds` per meter type; use the exposition names above in PromQL.
- **`InferenceMetrics` is null-safe by design**: when no registry is bound (metrics disabled, CLI, tests) recording is a cheap no-op — never guard call sites.
- **Metrics live on the management port, not the gRPC port**: `services.core.http` (default `localhost:18092`, basic auth) serves `/_node/metrics/prometheus`.
- **GPU gauges degrade gracefully**: without `nvidia-smi` the collector logs once and stays idle — no `gpu_*` series appear, everything else is unaffected.
- **Legacy tracing keys** under `services.tracing.otel.*` are still accepted as aliases for `services.opentelemetry.*`.
- **Kubernetes**: scrape `/_node/metrics/prometheus` on port 18092 via a `PodMonitor`/`ServiceMonitor` and import `examples/observability/grafana/dashboards/` as-is.

## See also
- [Deployment](../deployment/README.md) — the distribution's `gravitee.yml` and the management API.
- [Pipelines](../pipelines/README.md) — the DAG whose steps become `ai.step` spans.
- [gRPC API & Client](../grpc-api-and-client/README.md) — the RPCs the SERVER spans wrap.
- [Remote Models & Multi-Server Composition](../remote-and-multi-server/README.md) — why client-side runs show no spans.
