# Observability

> OpenTelemetry spans per RPC, pipeline, step and model call, plus Micrometer metrics scraped by Prometheus at `/_node/metrics/prometheus`.

## Overview

Both signals are configured in `gravitee.yml`. Tracing (`services.opentelemetry`) is off by
default: when enabled, each gRPC call and each HTTP request opens a `SERVER` span (HTTP probe paths such as `/health` are excluded, see `exclude-paths`) that
continues any inbound W3C `traceparent`, under which the engine nests a `pipeline <id>` span, a
`step <id> (<type>)` span per step and an `<operation> <model>` span per model call (for example
`pipeline guarded`, `step pii_guard (guard)`, `classify pii`, `infer llm`), and a single
`singularitee.<op>` span for direct RPCs. Calls to `remote_*` models and remote sub-pipelines carry
the trace context as gRPC metadata, so the serving node's spans join the caller's trace. Spans
export over OTLP. The engine's own attribute keys (and the direct-RPC span names) carry the
`singularitee` prefix by default; `services.opentelemetry.name-prefix` changes it (Micrometer
metric names are unaffected).
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
- `PipelineExecutor`: opens `pipeline <id>`.
- `TracingStepDecorator` (in the `StepDispatcher` chain): opens `step <id> (<type>)`.
- `ModelBoundStepExecutor`: opens `<operation> <model>` (`CLIENT` kind), binds its trace context to the engine call, and times it into `ai_model_call_seconds`.
- `TraceContext` (engine-api): the W3C headers of a span, bound to the calls into a `PropagatesTraceContext` engine (the `remote_*` engines) and to a remote `sub_pipeline` call; the remote engine sends them through `SingulariteeClient.withMetadata`.
- `SpanStatus` (engine-api): marks a finished span `OK`, or ends it `ERROR` with the exception as attributes.
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

A pipeline run is a `POST` root (`openinference.span.kind = AGENT`) over a `CHAIN`
(`pipeline <id>`) nesting `LLM` steps (`llm.model_name`, `llm.token_count.*`, tool calls,
the sampling parameters as `llm.invocation_parameters`, the engine `singularitee.infer.sequence_id`
and the client-side time-to-first-token `singularitee.infer.ttft_ms`) and, for a guard/gate step, a
`GUARDRAIL` span. When logprobs were captured, each `LLM` step also carries the confidence signals
`singularitee.infer.*` (`perplexity`, `min_margin`, `max_token_perplexity`, `mean_entropy`, and the
rest of the set; see [Confidence signals](#confidence-signals)). With `verbose` on, each `LLM` step also carries the
rendered prompt (`input.value`), the answer (`output.value` + `llm.output_messages.*`, reasoning
included), the input messages, and the to-do plan snapshot (`singularitee.todos`); the pipeline span
carries a preview of the user turn (`singularitee.turn.input`) and each `infer` step one of its answer
(`singularitee.turn.output`); step spans also carry their decisions (`loop.*`, `break.*`, `route.*`, `classify.*`, `singularitee.monitor.verdict`).

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
| `services.opentelemetry.exclude-paths` | list | `/health`, `/healthz`, `/ready`, `/readyz`, `/readiness`, `/live`, `/livez`, `/liveness`, `/metrics`, `/_node/*` | HTTP API paths that open no span. Comma-separated or a YAML list; a trailing `*` is a prefix; a configured value replaces the default, blank traces every path. |
| `services.opentelemetry.exporter.endpoint` | string | `http://localhost:4317` | OTLP collector. |
| `services.opentelemetry.exporter.protocol` | string | `grpc` | `grpc` or `http/protobuf`. |
| `services.opentelemetry.exporter.compression` / `.timeout` / `.headers` | | unset | `gzip`; ms; `name=value` list. |
| `services.opentelemetry.exporter.ssl.trustAll` / `.verifyHost` | bool | `false` / `true` | Collector TLS. |
| `services.core.http.port` | int | `18092` | Where `/_node/metrics/prometheus` lives. |

### Spans

Attribute keys use the default prefix `singularitee`.

| Span | Kind | Attributes | Opened by |
| --- | --- | --- | --- |
| `POST` (server span) | `SERVER` | `rpc.system=grpc`, `rpc.method=<path>`; or `rpc.system=http`, `http.route=<path>` | `GrpcServerComponent`, `HttpApiServerComponent`; continues an inbound `traceparent`. |
| `pipeline <id>` | `INTERNAL` | `singularitee.pipeline.id`, `pipeline.id`, `singularitee.session`, `singularitee.turn.index`, `singularitee.finish_reason`; `singularitee.turn.input` with `verbose` | `PipelineExecutor`, per `InferPipeline`. |
| `step <id> (<type>)` | `INTERNAL` | `singularitee.step.id`, `singularitee.step.type`, `singularitee.pipeline.id`, `step.id`, `step.type`, `model.id` when model-bound, `singularitee.step.halted=true` on the step that halted the run | `TracingStepDecorator`, per executed step. |
| `<operation> <model>` | `CLIENT` | `singularitee.model.id`, `singularitee.model.type`, `singularitee.model.engine_type`, `singularitee.model.engine`, `model.id`, `op`; for generation and embeddings `gen_ai.operation.name` (`chat`, `embeddings`) and `gen_ai.request.model`; `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens` when the engine reports usage | `ModelBoundStepExecutor`, per engine call. `<operation>` follows the engine type: `infer`, `classify`, `embed`, `rerank`, `decide`; `op` is the step operation (the executor class name lower-cased without its suffix: `infer`, `guard`, `llmguard`, `toolselect`, ...), the same `op` tag as `ai_model_call_seconds`. |
| `singularitee.embed`, `singularitee.embed.batch`, `singularitee.text_similarity`, `singularitee.text_rerank`, `singularitee.classify`, `singularitee.classify.batch`, `singularitee.decide` | `INTERNAL` | `model.id` | `ServiceInstrumentation`, per direct unary RPC. |

Streaming `Infer` is recorded in metrics but opens no child span of its own under the server span.

### Status

Engine spans end `OK` on success and `ERROR` on failure, with the exception recorded as the
`exception.type` and `exception.message` attributes and the message as the status description. A
step that halts the run on purpose,
such as a guard rejecting the input, is `OK` with `singularitee.step.halted=true`, and its pipeline
span is `OK`: a deliberate block is not an error. A call the client cancelled ends with no status.
The HTTP API's server span ends `OK` for a 2xx or 3xx answer and `ERROR` for a 5xx (status
description `HTTP <code>`), so a trace list shows a status for every row; a 4xx stays unset, as the
HTTP semantic conventions have it for a server span, because the caller's mistake is not the
server's failure. A transport failure ends it `ERROR` with the exception as attributes. gRPC server
spans stay unset on success.

Each span ends before the run moves on: a model span inside its step, a step before the next
step starts. Facts, exceptions included, are attributes, never span events: the Elasticsearch
exporter stores each span event as a document of its own, carrying the span's id but no name, and a
trace view reading that index draws it as an extra unnamed span.

### Propagation

A `remote_*` model call sends the W3C `traceparent` (and `tracestate`) of its model span as gRPC
metadata; a remote `sub_pipeline` call sends its step span's. The serving node's gRPC `SERVER`
span continues it, so a trace reads, for a guarded pipeline whose `llm` is a `remote_llm`:

```
POST                                 node A, SERVER
  pipeline guarded
    step pii_guard (guard)
      classify pii                   CLIENT, local classifier
    step generate (infer)
      infer llm                      CLIENT
        POST                         node B, SERVER: the remote Infer RPC
```

The trace context is bound to each call into the remote engine rather than to a thread, so a
step that calls its model several times (a guard over every turn, a batched `tool_select`)
propagates it on every call. A remote sub-pipeline's own `pipeline`, `step` and model spans nest
under node B's server span the same way.

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
(`engine-api`); adding a signal is one class plus one registry line. A signal that also reads
as a confidence in `[0, 1]` (`ConfidenceSignal.confidence`) can score the spans of an
[`llm_token_classifier`](../../reference/models/llm_token_classifier.md) (`score:`).

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
| `ai_finish_reasons_total` | counter | `model`, `reason` | Infer completions by finish reason (`stop`, `length`, `tool_calls`, `stalled`, `start_failed`, `cancelled`, ...). Makes silent truncation graphable. |
| `ai_failure_signals_total` | counter | `source`, `kind`, `signal` | `kind=model`: `tool_parse_failed`, `thinking_unclosed`; `kind=step`: `loop_max_iterations`; `kind=pipeline`: `guard_blocked`. `source` is the matching id. |
| `gpu_utilization_percent`, `gpu_memory_utilization_percent`, `gpu_memory_total_bytes`, `gpu_memory_used_bytes`, `gpu_temperature_celsius`, `gpu_power_watts` | gauge | `gpu`, `name`, `uuid` | From `services.monitoring.gpu`. |

JVM and Vert.x meters from gravitee-node are exposed alongside.

## Notes

- Meter names are dotted in code (`ai.infer.requests`); the Prometheus registry turns `.` into `_` and appends `_total` or `_seconds` by meter type. Use the names above in PromQL.
- Metrics live on the management port (`services.core.http`, default `localhost:18092`, basic auth), not on the gRPC or HTTP API ports. Change the default management credentials before exposing it; a non-loopback bind with the default password logs a warning at startup.
- An inbound `traceparent` is honoured, so a gateway in front of the server sees one trace across processes; calls to `remote_*` models and remote sub-pipelines pass it on.
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
