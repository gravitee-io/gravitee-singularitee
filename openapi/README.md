# OpenAPI specifications

> One OpenAPI 3.1 document per HTTP API type, each self-contained, describing the HTTP API of `gravitee-singularitee-http`.

## Overview
The HTTP API is opt-in (`http.enabled: true`), listens on port `8080`, and every path is served
both under `/v1` and bare. The specs document the `/v1` form. Semantics shared by every spec
(model resolution, error envelope, readiness, authentication) are written out in
[docs/api/http](../docs/api/http/README.md); the specs are the machine-readable schemas.

| Spec | Paths |
| --- | --- |
| [text-generation.openapi.yaml](./text-generation.openapi.yaml) | `/v1/chat/completions`, `/v1/completions`, `/v1/responses` |
| [embeddings.openapi.yaml](./embeddings.openapi.yaml) | `/v1/embeddings`, `/v1/rerank`, `/v1/similarity` |
| [classification.openapi.yaml](./classification.openapi.yaml) | `/v1/classify` |
| [discovery.openapi.yaml](./discovery.openapi.yaml) | `/v1/models`, `/v1/models/{model}`, `/health` |

## Usage

Each document is standard OpenAPI 3.1 and self-contained: load it in any OpenAPI
tool. To check that a document parses after editing it:

```bash
for f in openapi/*.openapi.yaml; do python3 -c "import sys, yaml; yaml.safe_load(open(sys.argv[1]))" "$f" && echo "ok $f"; done
```

Feed a spec to a client generator or an API gateway as-is; each one carries its own
`components`, `servers` and `securitySchemes`.

## Notes
- The source of truth is the code: handlers under
  `gravitee-singularitee-http/src/main/java/io/gravitee/singularitee/http/handler`, the
  request schemas in `gravitee-singularitee-http/src/main/resources/llm-schemas.json`, and the
  proto messages the handlers translate to. When a spec and the code disagree, fix the spec.
- Request schemas use `additionalProperties: true` on purpose: the server accepts unknown
  fields so SDK-added parameters do not break requests.

## See also
- [HTTP API reference](../docs/api/http/README.md)
- [API overview](../docs/api/README.md)
