# Observability: GPU and inference metrics in Grafana

Singularitee publishes Micrometer metrics in Prometheus format on its management (core) HTTP
port. This folder is a Prometheus plus Grafana stack that scrapes those metrics and renders
a "GPU & Inference" dashboard.

## 1. Enable metrics on the server

In `config/gravitee.yml` (already the default in the shipped distribution):

```yaml
services:
  metrics:
    enabled: true
    prometheus:
      enabled: true
  monitoring:
    gpu:
      enabled: true          # gpu_* gauges (needs nvidia-smi on PATH, e.g. the CUDA images)
      delay: 5000
      unit: MILLISECONDS
```

Every key has an environment form, e.g. `GRAVITEE_SERVICES_MONITORING_GPU_ENABLED=true`.

Verify the endpoint (basic auth, default `admin:adminadmin`):

```bash
curl -s -u admin:adminadmin http://localhost:18092/_node/metrics/prometheus | grep '^gpu_'
```

GPU monitoring shells out to `nvidia-smi`. On a host or container without the NVIDIA runtime
the collector logs once and stays idle: no `gpu_*` series appear and the other metrics are
unaffected.

## 2. Run Prometheus and Grafana

```bash
docker compose -f examples/observability/docker-compose.yml up -d
```

- Grafana: http://localhost:3000 (admin / admin). Dashboard "Singularitee / GPU & Inference", auto-provisioned.
- Prometheus: http://localhost:9091

The bundled Prometheus scrapes `host.docker.internal:18092`, i.e. the server running on the
Docker host. To scrape a different target, edit `prometheus/prometheus.yml`.

## Exposed metrics

| Metric (Prometheus) | Type | Unit | Labels |
| --- | --- | --- | --- |
| `gpu_utilization_percent` | gauge | % | `gpu`, `name`, `uuid` |
| `gpu_memory_utilization_percent` | gauge | % | `gpu`, `name`, `uuid` |
| `gpu_memory_total_bytes` | gauge | bytes | `gpu`, `name`, `uuid` |
| `gpu_memory_used_bytes` | gauge | bytes | `gpu`, `name`, `uuid` |
| `gpu_temperature_celsius` | gauge | degrees C | `gpu`, `name`, `uuid` |
| `gpu_power_watts` | gauge | W | `gpu`, `name`, `uuid` |
| `ai_infer_requests_total` | counter | count | `model`, `status` |
| `ai_infer_latency_seconds` | timer | s | `model` |
| `ai_model_call_seconds` | timer | s | `model`, `op` |
| `ai_tokens_total` | counter | tokens | `model`, `kind` |
| `ai_finish_reasons_total` | counter | count | `model`, `reason` |
| `ai_failure_signals_total` | counter | count | `source`, `kind`, `signal` |
| `ai_pipeline_requests_total` | counter | count | `pipeline`, `status` |
| `ai_pipeline_latency_seconds` | timer | s | `pipeline` |

`ai_<op>_requests_total` and `ai_<op>_latency_seconds` also exist for `op = classify | embed | rerank`.
The full list, with label values, is in
[docs/operations/observability](../../docs/operations/observability/README.md).

## Kubernetes

Enable the same config wherever you render `gravitee.yml`:

```yaml
services:
  metrics:
    enabled: true
    prometheus:
      enabled: true
  monitoring:
    gpu:
      enabled: true
```

Scrape `/_node/metrics/prometheus` on the management port (18092), e.g. with a `PodMonitor`
or `ServiceMonitor`, or Prometheus pod annotations. The dashboard JSON in
`grafana/dashboards/` can be imported as-is.

## See also

- [Observability](../../docs/operations/observability/README.md): spans, every metric, every label.
- [Configuration](../../docs/reference/configuration.md): the `services.*` keys.
