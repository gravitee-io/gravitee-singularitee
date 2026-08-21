# Multimodal (vision and audio)

> Attach images and audio to chat messages with OpenAI content parts or gRPC `MediaContent`; a llama.cpp model decodes them through its mtmd projector (`mmproj_path`), a vLLM model through its own checkpoint.

## Overview

Media rides on the normal chat flow. A `ChatTurn` carries a list of `MediaAttachment`
(base64 payload plus type) next to its text. On the HTTP API a message `content` array with
`image_url` / `input_image` / `input_audio` parts becomes `ChatMessage.media` on the wire; gRPC
callers fill `media` directly. On llama.cpp, `mmproj_path` loads an mtmd context: the engine
injects the model's media marker once per attachment and `mtmd_tokenize` replaces each marker
with vision or audio tokens. Pipelines do not change; a workspace binds the logical `llm` id
to a multimodal model file.

What an entry accepts is detected, not declared, and published on `GET /v1/models` as
`input_modalities`. HTTP refuses media the target cannot read with `400 unsupported_modality`.

## Key types

| Type | Where | Purpose |
| --- | --- | --- |
| `MediaAttachment`, `MediaAttachmentType` | `engine` | `(type, base64 data)`; types `IMAGE_JPEG`, `IMAGE_PNG`, `IMAGE_GIF`, `IMAGE_BMP`, `AUDIO_WAV`, `APPLICATION_OCTET_STREAM`. |
| `MediaContent`, `MediaType` | `protocol` (`inference.proto`) | Wire form on `ChatMessage.media`. |
| `PipelineRequestBuilder.applyContent` | `http` | Parses OpenAI content-part arrays. |
| `HandlerSupport.requireSupportedModalities` | `http` | The `unsupported_modality` pre-flight on `/v1/chat/completions` and `/v1/responses`. |
| `LlamaCppTextGenEngine`, `CheckpointModalities` | `grpc` | Modality detection: the projector for llama.cpp, `config.json` for vLLM. |

## Usage

Serve a vision model (`examples/llama/qwen3-vl-2b.yaml`; `examples/vllm/qwen3-vl-2b.yaml` is
the vLLM twin and needs no projector):

```yaml
workspace:
  models:
    - id: llm
      name: Qwen/Qwen3-VL-2B-Instruct-GGUF
      type: llama_cpp
      memory_check: warn
      llama_cpp:
        path: Qwen3VL-2B-Instruct-Q8_0.gguf
        mmproj_path: mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf
        n_ctx: 16384          # images cost many tokens
        n_seq_max: 1
        n_batch: 2048
        n_ubatch: 2048
        n_gpu_layers: 999
```

Audio: `examples/llama/voxtral-3b.yaml`, same shape with the Voxtral weights and audio
projector.

```bash
./run-server.sh --workspace examples/llama/qwen3-vl-2b.yaml    # or: task run:vision
curl -s localhost:8080/v1/models | jq '.data[] | {id, input_modalities}'
```

Send an image (data URL or bare base64; remote `http(s)` URLs are rejected with 400):

```bash
IMG=$(base64 < photo.png | tr -d '\n')
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "llm",
  "messages": [{"role": "user", "content": [
    {"type": "text", "text": "What is in this image?"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,'"$IMG"'"}}
  ]}]
}' | jq -r '.choices[0].message.content'
```

Send audio (WAV):

```json
{"model":"llm","messages":[{"role":"user","content":[
  {"type":"text","text":"Answer the spoken question."},
  {"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}]}]}
```

Over gRPC, set `ChatMessage.media` with `MediaContent{media_type, data}` where `data` is the
base64 string as UTF-8 bytes.

Live demos (server in one shell, demo in another):

```bash
task run:vision && task vision     # webcam: SPACE asks, q quits
task run:audio  && task audio      # push-to-talk: ENTER starts/stops, q quits
```

Both scripts read `BASE_URL` (default `http://localhost:8080/v1`), `MODEL`, `API_KEY`,
`PROMPT`; `vision_live.py` also `CAMERA`, `NUM_FRAMES`, `INTERVAL`, `MAX_TOKENS`,
`IMG_WIDTH`; `audio_ptt.py` also `TEMPERATURE` (default `0.2`).

### Content parts

| Part `type` | Shape | Notes |
| --- | --- | --- |
| `text`, `input_text`, `output_text` | `{"type":"text","text":"..."}` | Several text parts are joined with newlines. |
| `image_url`, `input_image` | `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}` (`input_image` may carry a bare string) | Data URL or bare base64 only. |
| `input_audio` | `{"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}` | MIME is `audio/<format>`; only WAV maps to a decodable type. |

### What decodes

The engine branches on image versus audio and hands the bytes to the decoder; the format
support is the decoder's.

| | Decoder | Formats |
| --- | --- | --- |
| Images | llama.cpp `stb_image` | JPEG, PNG, GIF (first frame), BMP |
| Audio | `javax.sound.sampled` | WAV |

WebP, TIFF, MP3, OGG, FLAC, AAC and M4A are not decodable and map to
`APPLICATION_OCTET_STREAM`. Transcode before sending.

### Where `input_modalities` comes from

| Backend | Source |
| --- | --- |
| llama.cpp | The loaded projector (`mtmd_support_vision` / `mtmd_support_audio`). |
| vLLM | The checkpoint's `config.json`: `vision_config` means image, `audio_config` means audio. |
| `remote_llm` | The remote's own answer, read off the lazy `GetModel` probe. Text-only until it answers. |
| other `remote_*`, vLLM without a local directory | Text-only assumed, warning logged. Declare `modalities:` to correct it. |

A pipeline accepts the union of what its model-bound steps accept. Override on a model or a
pipeline with `modalities: [text, image]`; only `text`, `image`, `audio` are valid and
anything else fails the workspace at load.

## Options

Full tables: [`llama_cpp`](../../reference/models/llama_cpp.md),
[`vllm`](../../reference/models/vllm.md), [Workspaces](../../workspaces/README.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `llama_cpp.mmproj_path` | string | unset | Projector GGUF (vision or audio). Unset means text-only. |
| `llama_cpp.media_marker` | string | mtmd default (`<__media__>`) | Marker injected once per attachment. Leave unset unless the model expects another. |
| `llama_cpp.n_ctx`, `n_seq_max` | int | engine defaults | Media expands to many tokens; raise `n_ctx`, keep `n_seq_max` low. |
| `modalities` (model or pipeline) | list | detected | Override detection: subset of `text`, `image`, `audio`. |

## Notes

- No `mmproj_path` means the model reports `["text"]` and HTTP refuses media before the engine
  sees it. Past that gate (gRPC, or a pipeline step) attachments are dropped without error: if
  a VLM answers as though it saw nothing, check `mmproj_path`.
- Marker count must equal attachment count: the engine prepends one marker per attachment, so
  a prompt template that already contains the marker desynchronises tokenisation. Keep the
  model's own chat template for multimodal models.
- llama.cpp media failures are hard errors (`Failed to load media`); the request fails.
- vLLM forwards media through its `EngineAdapter` as multimodal data with no marker injection.
  Image tokens count against `max_model_len`. Verified end to end on llama.cpp only; the vLLM
  vision path needs CUDA to exercise.
- The audio sample rate comes from the projector (fallback 16 kHz); `audio_ptt.py` records
  16 kHz mono WAV to match.
- Base64 travels as UTF-8 bytes end to end and is decoded once, at load time, with a MIME
  decoder.

## See also

- [Text generation](../text-generation/README.md): the request flow attachments ride on.
- [HTTP API](../../api/http/README.md): content parts, `/v1/models`, error envelope.
- [gRPC API](../../api/grpc/README.md): `ChatMessage.media`.
- [Workspaces](../../workspaces/README.md): `task`, `visible`, `modalities`.
