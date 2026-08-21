# Observability

> OpenTelemetry spans per RPC, pipeline, step and model call, plus Micrometer metrics scraped by Prometheus at `/_node/metrics/prometheus`.

## Overview

Both signals are configured in `gravitee.yml`. Tracing (`services.opentelemetry`) is off by
default: when enabled, each gRPC call and each HTTP request opens a `SERVER` span that
continues any inbound W3C `traceparent`, under which the engine nests `ai.pipeline`,
`ai.step` and `ai.model.<op>` spans for pipeline runs and a single `ai.<op>` span for direct
RPCs. Spans export over OTLP. Metrics (`services.metrics`) are on by default: a Micrometer
registry records request counters, latency timers, model-call timers, token and finish-reason
counters, plus `gpu_*` gauges when `services.monitoring.gpu` finds `nvidia-smi`, and exposes
them in Prometheus format on the management port. `examples/observability/` is a Prometheus
and Grafana stack with a provisioned dashboard.

## Key types

- `InferenceMetrics` (engine): the Micrometer recorder. Wraps a possibly-null `MeterRegistry`; every `record*` call is a no-op when metrics are off.
- `ServiceInstrumentation` (grpc): wraps the unary RPCs, opening the `ai.<op>` child span and recording `ai_<op>_requests_total` and `ai_<op>_latency_seconds`.
- `GrpcServerComponent` and `HttpApiServerComponent` (standalone): open the `SERVER` span per call, attributes `rpc.system` and `rpc.method` (gRPC) or `http.route` (HTTP).
- `PipelineExecutor`: opens `ai.pipeline` with `pipeline.id`.
- `StepDispatcher`: opens `ai.step` with `step.id` and `step.type`.
- `ModelBoundStepExecutor`: opens `ai.model.<op>` (`CLIENT` kind) with `model.id` and `op`, and times it into `ai_model_call_seconds`.
- `io.gravitee.node.api.opentelemetry.Tracer`: the gravitee-node tracer; a no-op instance when tracing is disabled.

## Usage

```yaml
services:
  metrics:
    enabled: true
    prometheus:
      enabled: true

  monitoring:
    gpu:
      enabled: true        # gpu_* gauges; needs nvidia-smi on PATH
      delay: 5000
      unit: MILLISECONDS

  opentelemetry:
    enabled: true
    verbose: false         # add request/response attributes to spans
    exporter:
      endpoint: http://localhost:4317
      protocol: grpc       # grpc (4317) | http/protobuf (4318)
```

Every key has an environment form, e.g. `GRAVITEE_SERVICES_OPENTELEMETRY_ENABLED=true`.

Scrape the management port (basic auth, default `admin:adminadmin`):

```bash
curl -s -u admin:adminadmin http://localhost:18092/_node/metrics/prometheus | grep '^ai_'
```

Run the bundled stack:

```bash
docker compose -f examples/observability/docker-compose.yml up -d
# Grafana    http://localhost:3000  (admin / admin), dashboard "Singularitee / GPU & Inference"
# Prometheus http://localhost:9091
```

The bundled Prometheus scrapes `host.docker.internal:18092`; edit
`examples/observability/prometheus/prometheus.yml` for another target.

## Options

The full key tables are in [Configuration](../../reference/configuration.md#servicesmetrics-and-servicesmonitoring).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `services.metrics.enabled` | bool | `true` | Bind the registry. |
| `services.metrics.prometheus.enabled` | bool | `true` | Expose it on the management port. Both must be on. |
| `services.monitoring.gpu.enabled` | bool | `true` | Poll `nvidia-smi`; no-op without the driver. |
| `services.monitoring.gpu.delay` / `.unit` | int / string | `5000` / `MILLISECONDS` | Poll interval. |
| `services.opentelemetry.enabled` | bool | `false` | Emit spans. |
| `services.opentelemetry.verbose` | bool | `false` | Request and response attributes on spans. |
| `services.opentelemetry.exporter.endpoint` | string | `http://localhost:4317` | OTLP collector. |
| `services.opentelemetry.exporter.protocol` | string | `grpc` | `grpc` or `http/protobuf`. |
| `services.opentelemetry.exporter.compression` / `.timeout` / `.headers` | | unset | `gzip`; ms; `name=value` list. |
| `services.opentelemetry.exporter.ssl.trustAll` / `.verifyHost` | bool | `false` / `true` | Collector TLS. |
| `services.core.http.port` | int | `18092` | Where `/_node/metrics/prometheus` lives. |

### Spans

| Span | Kind | Attributes | Opened by |
| --- | --- | --- | --- |
| (server span) | `SERVER` | `rpc.system=grpc`, `rpc.method=<path>`; or `rpc.system=http`, `http.route=<path>` | `GrpcServerComponent`, `HttpApiServerComponent`; continues an inbound `traceparent`. |
| `ai.pipeline` | `INTERNAL` | `pipeline.id` | `PipelineExecutor`, per `InferPipeline`. |
| `ai.step` | `INTERNAL` | `step.id`, `step.type`, `model.id` when model-bound | `StepDispatcher`, per executed step. |
| `ai.model.<op>` | `CLIENT` | `model.id`, `op` | `ModelBoundStepExecutor`, per engine call. `<op>` is the executor class name lower-cased without its suffix: `infer`, `classify`, `embed`, `guard`, `llmguard`, `toolselect`. |
| `ai.embed`, `ai.embed.batch`, `ai.text_similarity`, `ai.text_rerank`, `ai.classify`, `ai.classify.batch` | `INTERNAL` | `model.id` | `ServiceInstrumentation`, per direct unary RPC. |

Streaming `Infer` is recorded in metrics but opens no child span of its own under the server span.

### Metrics (Prometheus names)

| Metric | Type | Labels | Purpose |
| --- | --- | --- | --- |
| `ai_infer_requests_total` | counter | `model`, `status` | Direct `Infer` RPCs. `status`: `success`, `error`, `not_found`, `cancelled`. |
| `ai_infer_latency_seconds` | timer | `model` | End-to-end `Infer` latency. |
| `ai_classify_requests_total` / `ai_classify_latency_seconds` | counter / timer | `model` (`status`) | `Classify` and `ClassifyBatch`. |
| `ai_embed_requests_total` / `ai_embed_latency_seconds` | counter / timer | `model` (`status`) | `Embed`, `EmbedBatch`, `TextSimilarity`. |
| `ai_rerank_requests_total` / `ai_rerank_latency_seconds` | counter / timer | `model` (`status`) | `TextRerank`. |
| `ai_pipeline_requests_total` | counter | `pipeline`, `status` | `InferPipeline` RPCs. The `pipeline` label is kept apart from `model`. |
| `ai_pipeline_latency_seconds` | timer | `pipeline` | End-to-end pipeline latency. |
| `ai_model_call_seconds` | timer | `model`, `op` | Each engine call inside a pipeline. |
| `ai_tokens_total` | counter | `model`, `kind` | `prompt`, `completion`, `reasoning`, `tool`. Non-positive counts are skipped. |
| `ai_finish_reasons_total` | counter | `model`, `reason` | Infer completions by finish reason (`stop`, `length`, `tool_calls`, `stalled`, `cancelled`, ...). Makes silent truncation graphable. |
| `ai_failure_signals_total` | counter | `source`, `kind`, `signal` | `kind=model`: `tool_parse_failed`, `thinking_unclosed`; `kind=step`: `loop_max_iterations`; `kind=pipeline`: `guard_blocked`. `source` is the matching id. |
| `gpu_utilization_percent`, `gpu_memory_utilization_percent`, `gpu_memory_total_bytes`, `gpu_memory_used_bytes`, `gpu_temperature_celsius`, `gpu_power_watts` | gauge | `gpu`, `name`, `uuid` | From `services.monitoring.gpu`. |

JVM and Vert.x meters from gravitee-node are exposed alongside.

## Notes

- Meter names are dotted in code (`ai.infer.requests`); the Prometheus registry turns `.` into `_` and appends `_total` or `_seconds` by meter type. Use the names above in PromQL.
- Metrics live on the management port (`services.core.http`, default `localhost:18092`, basic auth), not on the gRPC or HTTP API ports. Change the default management credentials before exposing it.
- An inbound `traceparent` is honoured, so a gateway in front of the server sees one trace across processes.
- Client-side pipeline execution (`ClientPipelineExecutor`) runs with the no-op tracer and emits no spans.
- Without `nvidia-smi` the GPU collector logs once and stays idle; no `gpu_*` series appear and nothing else is affected.
- `services.tracing.otel.*` is accepted as an alias for `services.opentelemetry.*`.
- Kubernetes: scrape `/_node/metrics/prometheus` on port 18092 with a `PodMonitor` or `ServiceMonitor`; the dashboard JSON in `examples/observability/grafana/dashboards/` imports as-is.

## See also

- [Configuration](../../reference/configuration.md): every `services.*` key and its environment variable.
- [Deployment](../deployment/README.md): the images and the management port in production.
- [Pipelines](../../concepts/pipelines/README.md): the DAG whose steps become `ai.step` spans.
- [gRPC API](../../api/grpc/README.md): the RPCs the server spans wrap.
- [examples/observability/](../../../examples/observability/README.md): the Prometheus and Grafana stack.
