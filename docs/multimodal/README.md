# Multimodal (Vision & Audio)

> Attach images and audio to chat messages — via OpenAI `image_url`/`input_audio` content parts or gRPC `MediaContent` — decoded by llama.cpp's mtmd projector (`mmproj_path`).

## Overview
Multimodality rides on the normal chat-message flow: a `ChatTurn` carries an optional list of `MediaAttachment`s alongside its text content. Media enters through either surface — the OpenAI-compatible HTTP API (content-part arrays on a message) or the gRPC `ChatMessage.media` field — and is forwarded base64-encoded to the engine. On the llama.cpp backend, setting `mmproj_path` in the model's `llama_cpp:` block loads an mtmd (multimodal projector) context: the engine injects the model's media marker once per attachment into the rendered prompt, and `mtmd_tokenize` replaces each marker with the model's vision or audio tokens. The same generation pipelines run unchanged — a workspace simply rebinds the logical `llm` id to a VLM (Qwen3-VL) or ALM (Voxtral) model file.

## Key types
- `MediaAttachment` — engine-side record `(MediaAttachmentType mediaType, String data)`; `data` is the base64 payload (`gravitee-singularitee-engine/.../engine/MediaAttachment.java`).
- `MediaAttachmentType` — enum: `IMAGE_JPEG`, `IMAGE_PNG`, `IMAGE_GIF`, `IMAGE_BMP`, `AUDIO_WAV`, `APPLICATION_OCTET_STREAM`. Limited on purpose — see [Supported formats](#supported-formats).
- `ChatTurn` — `(ChatRole role, String content, List<MediaAttachment> media)`.
- `MediaContent` / `MediaType` (proto, `inference.proto`) — the wire form on `ChatMessage.media`; `data` holds the base64 string as UTF-8 bytes.
- `PipelineRequestBuilder` (`gravitee-singularitee-http/.../translation/`) — parses OpenAI content-part arrays (`applyContent`) into `MediaContent`; shared by `InferRequestBuilder`.
- `GraviteeInferenceServiceImpl.toChatTurns` (`gravitee-singularitee-grpc/.../service/`) — converts proto `MediaContent` → `MediaAttachment`.
- `LlamaCppTextGenEngine` / `AbstractTextGenEngine` — expose `mediaMarker()` (non-null only when the model is multimodal) and inject one marker per attachment before rendering (`injectMediaMarkers`).

## Usage
Serve a vision model (`examples/llama/qwen3-vl-2b.yaml`):

```yaml
workspace:
  models:
    - id: llm
      name: Qwen/Qwen3-VL-2B-Instruct-GGUF
      type: llama_cpp
      memory_check: warn
      llama_cpp:
        path: Qwen3VL-2B-Instruct-Q8_0.gguf
        mmproj_path: mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf   # loads MtmdContext → VLM
        n_ctx: 16384        # images are token-hungry
        n_seq_max: 1        # keep the per-sequence context large
        n_batch: 2048
        n_ubatch: 2048
        n_gpu_layers: 999
        flash_attn_type: AUTO
```

For audio, `examples/llama/voxtral-3b.yaml` — identical shape with `path: Voxtral-Mini-3B-2507-Q4_K_M.gguf` and `mmproj_path: mmproj-Voxtral-Mini-3B-2507-Q8_0.gguf`.

Send an image over the OpenAI HTTP API (base64 data URL — remote `http(s)` URLs are rejected):

```shell
curl http://localhost:8090/v1/chat/completions -H 'Content-Type: application/json' -d '{
  "model": "llm",
  "messages": [{"role": "user", "content": [
    {"type": "text", "text": "What is in this image?"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,<...>"}}
  ]}]
}'
```

Send audio (wav only — see [Supported formats](#supported-formats)):

```json
{"model":"llm","messages":[{"role":"user","content":[
  {"type":"text","text":"Answer the spoken question."},
  {"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}]}]}
```

Try it live with the demo scripts (server running with the HTTP API on `:8090`):

```shell
# Webcam VLM demo — SPACE asks now, q/ESC quits
task run:vision           # shell 1: serve the VLM
task vision               # shell 2: webcam demo (or: uv run --with openai --with opencv-python examples/scripts/vision_live.py)

# Push-to-talk ALM demo — ENTER starts/stops recording, q quits
task run:audio            # shell 1: serve the ALM
task audio                # shell 2: push-to-talk (or: uv run --with openai --with sounddevice examples/scripts/audio_ptt.py)
```

## Options

### `llama_cpp:` multimodal fields
| Field | Default | Purpose |
| --- | --- | --- |
| `mmproj_path` | unset | Path to the projection GGUF (vision or audio). When set, the engine loads an `MtmdContext` and the model accepts media; when unset, media is ignored. |
| `media_marker` | mtmd library default (`<__media__>`) | Marker string injected once per attachment into the prompt; `mtmd_tokenize` replaces it with the model's media tokens. Leave unset unless the model expects a non-default marker. |
| `n_ctx` / `n_seq_max` | engine defaults | Media expands to many tokens — raise `n_ctx` (e.g. 16384) and keep `n_seq_max` low so each sequence gets a large window. |

### OpenAI content parts (`PipelineRequestBuilder.applyContent`)
| Part `type` | Shape | Notes |
| --- | --- | --- |
| `text` / `input_text` / `output_text` | `{"type":"text","text":"..."}` | Multiple text parts are concatenated (newline-separated). |
| `image_url` / `input_image` | `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}` (or a bare string url for `input_image`) | Data URL or bare base64 only. |
| `input_audio` | `{"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}` | MIME derived as `audio/<format>`; only wav maps to a decodable type, everything else falls back to octet-stream. |

### Demo script environment variables
| Variable | Default | Purpose |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8090/v1` | OpenAI-compatible endpoint. |
| `MODEL` | `llm` | Model id (vision_live falls back to the first of `/v1/models`). |
| `API_KEY` | `sk-noauth` | Bearer token. |
| `PROMPT` | script-specific | Question sent with each snapshot/clip. |
| `CAMERA` / `NUM_FRAMES` / `INTERVAL` | `0` / `1` / `0.6` | vision_live only: camera index, frames per ask, seconds between automatic asks (`0` = manual SPACE only). |
| `MAX_TOKENS` / `IMG_WIDTH` | `48` / `448` | vision_live only: reply cap and image size — the two knobs that decide whether a query fits inside `INTERVAL`. |
| `TEMPERATURE` | `0.2` | audio_ptt only. Audio understanding is a transcription-shaped task and wants a low temperature; the engine default (0.7) noticeably degrades answer quality on small quantized audio models. |
| `SKIP_PREFLIGHT` | unset | Bypass the multimodal capability probe (see below). |

## Supported formats

The media type is carried through the API but never reaches a format-specific
decoder: the engine branches only on image-vs-audio and hands the raw bytes over.
What decodes is therefore decided entirely by the underlying decoder.

| | Decoder | Supported |
| --- | --- | --- |
| Images | llama.cpp's vendored `stb_image` | **JPEG, PNG, GIF** (first frame), **BMP** (non-1bpp, non-RLE) |
| Audio | `javax.sound.sampled` | **WAV** |

Notably absent: **WebP** and **TIFF** (stb_image has no decoder for either), and
every compressed audio format — **MP3, OGG, FLAC, AAC, M4A** — for which
`javax.sound.sampled` ships no reader.

These once had their own `MediaType` constants. They were removed because
declaring them was worse than not supporting them: an undecodable payload is
**dropped without an error**, and the request completes normally with empty
content and zero tokens.

```
"finish_reason": "stop",
"message": { "content": "" },
"usage": { "prompt_tokens": 0, "completion_tokens": 0 }
```

Unknown MIME types now map to `APPLICATION_OCTET`, so nothing in the contract
claims a format the engine cannot read. Transcode to WAV (or JPEG/PNG) before
sending; the removed proto enum numbers are `reserved` and will not be recycled.

## Notes
- **Remote URLs are rejected**: `extractBase64Data` throws `IllegalArgumentException` ("Remote image URLs are not supported; provide the image as base64-encoded data or a data URL"), surfaced as HTTP 400 `invalid_request_error`. Bare base64 (no `data:` prefix) is accepted; invalid base64 is a 400 too.
- **No mmproj → media silently ignored** on llama.cpp: `mediaMarker()` returns `null`, no markers are injected, and attachments are dropped without error. If a VLM answers as if it saw no image, check `mmproj_path`.
- **vLLM** (`VllmTextGenEngine`) also forwards media: the vLLM `EngineAdapter` builds `MultiModalData` from image/audio attachments (base64 decode failures are logged and skipped, not thrown). It uses no marker injection — the model's own chat template handles placement. There is also **no `mmproj_path` to configure**: the vision tower ships inside the checkpoint, so binding the logical `llm` id to a VLM repo is the whole change. See `examples/vllm/qwen3-vl-2b.yaml`, and `gemma4-12b.yaml` / `gemma4-26b.yaml`, which are vision models despite their names. Note that image tokens are charged against `max_model_len` — a single 1024x1024 image is worth well over a thousand of them — and that the VRAM pre-flight widens its safety margin automatically when it sees a `vision_config` in the checkpoint.

  > The vLLM media path is **not yet verified end-to-end**: it is wired and reviewed, but the vision integration suite requires CUDA (vllm-metal does not forward image data), so it has only been exercised on the llama.cpp backend.
- **Marker count must match attachment count**: `AbstractTextGenEngine.injectMediaMarkers` prepends one `marker + "\n"` per attachment before rendering, so a prompt template that already contains the marker will desynchronize `mtmd_tokenize`.
- **Templating stays with the model**: on the direct-model path the prompt is rendered from the GGUF's own embedded chat template (via Jinja4j) — don't override it for VLM/ALM models or the media token scaffolding may break.
- **Base64 travels as UTF-8 bytes** end-to-end: HTTP stores `ByteString.copyFromUtf8(base64)` in `MediaContent.data`; the gRPC service passes it to `MediaAttachment.data` via `toStringUtf8()`; the engine decodes with `Base64.getMimeDecoder()` only at load time.
- **Audio sample rate** is taken from the projector (`mtmdContext.getAudioSampleRate()`, fallback 16000 Hz); the `audio_ptt.py` demo records 16 kHz mono WAV to match.
- **llama.cpp media failures are hard errors**: a corrupt image/clip throws `LlamaException("Failed to load media: ...")` and the request fails.

## See also
- [Text Generation](../text-generation/README.md) — the request flow these attachments ride on.
- [OpenAI HTTP API](../openai-http-api/README.md) — the HTTP surface, SSE streaming, and auth.
- [gRPC API & Client](../grpc-api-and-client/README.md) — the `ChatMessage.media` proto field.
- [Workspaces](../workspaces/README.md) — model YAML structure and includes.
