# Observability

> OpenTelemetry spans per RPC, pipeline, step and model call, plus Micrometer metrics scraped by Prometheus at `/_node/metrics/prometheus`.

## Overview

Both signals are configured in `gravitee.yml`. Tracing (`services.opentelemetry`) is off by
default: when enabled, each gRPC call and each HTTP request opens a `SERVER` span that
continues any inbound W3C `traceparent`, under which the engine nests `singularitee.pipeline`,
`singularitee.step` and `singularitee.model.<op>` spans for pipeline runs and a single `singularitee.<op>` span for direct
RPCs. Spans export over OTLP. Span and attribute names carry the `singularitee` prefix by
default; `services.opentelemetry.name-prefix` changes it (Micrometer metric names are unaffected).
Every span also carries OpenInference semantic
attributes (`openinference.span.kind` LLM/CHAIN/GUARDRAIL/EMBEDDING, `session.id`, `llm.*`), so a
trace renders in an OTLP viewer such as Jaeger. `session.id` is the request's cache-affinity key
(`prompt_cache_key` / `user`) when supplied, so a conversation's turns share one session, and the
per-turn request id otherwise; `services.opentelemetry.openinference:
false` turns them off, and `verbose: true` adds the message/input/output content. Metrics (`services.metrics`) are on by default: a Micrometer
registry records request counters, latency timers, model-call timers, token and finish-reason
counters, plus `gpu_*` gauges when `services.monitoring.gpu` finds `nvidia-smi`, and exposes
them in Prometheus format on the management port. `examples/observability/` is a Prometheus
and Grafana stack with a provisioned dashboard.

## Key types

- `InferenceMetrics` (engine): the Micrometer recorder. Wraps a possibly-null `MeterRegistry`; every `record*` call is a no-op when metrics are off.
- `ServiceInstrumentation` (grpc): wraps the unary RPCs, opening the `singularitee.<op>` child span and recording `ai_<op>_requests_total` and `ai_<op>_latency_seconds`.
- `GrpcServerComponent` and `HttpApiServerComponent` (standalone): open the `SERVER` span per call, attributes `rpc.system` and `rpc.method` (gRPC) or `http.route` (HTTP).
- `PipelineExecutor`: opens `singularitee.pipeline` with `pipeline.id`.
- `StepDispatcher`: opens `singularitee.step` with `step.id` and `step.type`.
- `ModelBoundStepExecutor`: opens `singularitee.model.<op>` (`CLIENT` kind) with `model.id` and `op`, and times it into `ai_model_call_seconds`.
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

### View traces in Jaeger

Every span is OTLP + OpenInference, so a self-hosted OTLP viewer renders the whole trace. This is
config-only: the exporter default (`endpoint: http://localhost:4317`, `protocol: grpc`) already
targets the standard OTLP gRPC port. [Jaeger](https://www.jaegertracing.io) (Apache-2.0) shows the
full span tree and every attribute as tags.

```bash
# 1. run Jaeger (UI :16686, OTLP gRPC :4317)
docker compose -f examples/observability/jaeger/docker-compose.yml up -d

# 2. start the server with tracing on. Use the GRAVITEE_ env form: the -D system-property form
#    does not reach Singularitee's own config reader, so `verbose` would stay off.
GRAVITEE_SERVICES_OPENTELEMETRY_ENABLED=true GRAVITEE_SERVICES_OPENTELEMETRY_VERBOSE=true \
  ./run-server.sh --workspace <a workspace>

# 3. send some traffic, then open http://localhost:16686
```

A pipeline run is a `POST /v1/responses` root (`openinference.span.kind = AGENT`) over a `CHAIN`
(`singularitee.pipeline`) nesting `LLM` steps (`llm.model_name`, `llm.token_count.*`, tool calls,
the sampling parameters as `llm.invocation_parameters`, the engine `singularitee.infer.sequence_id`
and the client-side time-to-first-token `singularitee.infer.ttft_ms`) and, for a guard/gate step, a
`GUARDRAIL` span. When logprobs were captured, each `LLM` step also carries the confidence signals
`singularitee.infer.*` (`perplexity`, `min_margin`, `max_token_perplexity`, `mean_entropy`, and the
rest of the set; see [Confidence signals](#confidence-signals)). With `verbose` on, each `LLM` step also carries the
rendered prompt (`input.value`), the answer (`output.value` + `llm.output_messages.*`, reasoning
included), the input messages, and the to-do plan snapshot (`singularitee.todos`); step spans also
carry their decisions (`loop.*`, `break.*`, `route.*`, `classify.*`, `singularitee.monitor.verdict`).

If spans do not arrive over gRPC, switch to `protocol: http/protobuf` (port 4318).

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
| `singularitee.pipeline` | `INTERNAL` | `pipeline.id` | `PipelineExecutor`, per `InferPipeline`. |
| `singularitee.step` | `INTERNAL` | `step.id`, `step.type`, `model.id` when model-bound | `StepDispatcher`, per executed step. |
| `singularitee.model.<op>` | `CLIENT` | `model.id`, `op` | `ModelBoundStepExecutor`, per engine call. `<op>` is the executor class name lower-cased without its suffix: `infer`, `classify`, `embed`, `guard`, `llmguard`, `toolselect`. |
| `singularitee.embed`, `singularitee.embed.batch`, `singularitee.text_similarity`, `singularitee.text_rerank`, `singularitee.classify`, `singularitee.classify.batch` | `INTERNAL` | `model.id` | `ServiceInstrumentation`, per direct unary RPC. |

Streaming `Infer` is recorded in metrics but opens no child span of its own under the server span.

### Confidence signals

Every `infer` step whose request captured log-probabilities carries a family of `singularitee.infer.*`
attributes on its `singularitee.step` span (and the same values in the pipeline context as `<step>.*`,
see [Context fields](../../reference/context-fields.md)). They are free byproducts of the one
generation the model already ran: the sampler produces the per-token log-probabilities, and (at top-k
depth 2 or more) the runner-up candidates, so every summary below is computed at no extra inference
cost. They are raw signals, not correctness measures; a downstream step calibrates the one it wants
against resolved outcomes. Which summary predicts correctness, if any, depends on the model and on
the task, so measure before trusting one: a summary that separates right from wrong answers on short
questions can carry nothing on long reasoning.

| Attribute | Family | Meaning |
| --- | --- | --- |
| `perplexity`, `mean_logprob` | central | Mean chosen-token confidence over the whole generation. |
| `answer_perplexity`, `answer_mean_logprob` | central | The same, over the answer tokens alone (reasoning excluded). |
| `max_token_perplexity`, `answer_max_token_perplexity` | peak | Perplexity of the single least-confident token (whole generation / answer only). |
| `p95_perplexity`, `p90_perplexity` | dispersion | Robust peaks: the 95th / 90th-percentile token perplexity. |
| `uncertain_token_fraction` | dispersion | Share of tokens below an uncertain confidence threshold. |
| `logprob_stdev` | dispersion | Standard deviation of the chosen-token log-probabilities (confidence swing). |
| `tail_perplexity` | positional | Perplexity over the last few tokens (the conclusion). |
| `logprob_slope` | positional | Least-squares slope of log-probability against token position. |
| `min_margin`, `low_margin_fraction` | top-k | Smallest, and share of small, top-1 vs top-2 token margins (how contested the pick was). Only at top-k depth >= 2. |
| `mean_entropy`, `max_entropy` | top-k | Mean and max per-token entropy over the top-k candidates. Only at top-k depth >= 2. |

The set is defined by the `ConfidenceSignal` implementations registered in `ConfidenceSignals`
(`engine-api`); adding a signal is one class plus one registry line.

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
- [Pipelines](../../concepts/pipelines/README.md): the DAG whose steps become `singularitee.step` spans.
- [gRPC API](../../api/grpc/README.md): the RPCs the server spans wrap.
- [examples/observability/](../../../examples/observability/README.md): the Prometheus and Grafana stack.
