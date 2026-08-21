# llama_cpp

> GGUF text generation through llama.cpp: the default engine, with multimodal projectors, KV-cache quantisation, prompt caching and three speculative-decoding flavours.

## Overview

`llama_cpp` loads one GGUF file with llamaj.cpp and serves it as a `TextGenEngine`
(`text-generation`). The file comes from the HuggingFace repository in `name` (or a local
path), picked by `llama_cpp.path`. Vision and audio models add an `mmproj_path` projector;
the engine then reports `image` and/or `audio` among its modalities by asking the projector.
`LlamaCppEngineFactory` builds the engine; `GgufModelResolver` downloads the files.

## Usage

`examples/llama/qwen3-0.6b.yaml`:

```yaml
workspace:
  name: qwen3-0.6b
  models:
    - id: llm
      name: Qwen/Qwen3-0.6B-GGUF
      type: llama_cpp
      memory_check: warn
      llama_cpp:
        path: Qwen3-0.6B-Q8_0.gguf
        n_ctx: 8192
        n_seq_max: 4
        n_gpu_layers: 999
        flash_attn_type: AUTO
  pipelines:
    - id: agent
      entry: generate
      steps:
        - id: generate
          type: infer
          role: output
          config:
            model_id: llm
            output_field: generate.output
```

A vision model (`examples/llama/qwen3-vl-2b.yaml`):

```yaml
    - id: llm
      name: Qwen/Qwen3-VL-2B-Instruct-GGUF
      type: llama_cpp
      llama_cpp:
        path: Qwen3VL-2B-Instruct-Q8_0.gguf
        mmproj_path: mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf
        n_ctx: 16384
        n_seq_max: 1
        n_batch: 2048
        n_ubatch: 2048
```

A large MoE model with quantised KV cache (`examples/llama/qwen3.6-35b.yaml`):

```yaml
      llama_cpp:
        path: Qwen3.6-35B-A3B-UD-Q4_K_S.gguf
        n_ctx: 131072
        n_seq_max: 1
        flash_attn_type: ENABLED      # required for a quantised V cache
        cache_type_k: q8_0
        cache_type_v: q8_0
        mtp: false                    # true enables self-speculative decoding on models with a NextN head
```

## Options

`llama_cpp:` block (`WorkspaceDefinition.LlamaCppDef`). Defaults are what
`LlamaCppEngineFactory` applies when a key is absent.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `path` | string | unset | GGUF file name inside the repository, or a local file path. Required for download: without it the engine is pointed at `name` as a local path. |
| `n_ctx` | int | `4096` | Context window per sequence. |
| `n_batch` | int | `2048` | Logical batch size (tokens submitted per decode call). |
| `n_ubatch` | int | `512` | Physical micro-batch size. |
| `n_seq_max` | int | `8` | Parallel sequences (slots). KV memory is `n_ctx x n_seq_max`. |
| `n_gpu_layers` | int | `999` | Layers offloaded to the GPU; `999` means all. |
| `pooling_type` | string | `UNSPECIFIED` | `NONE`, `MEAN`, `CLS`, `LAST`, `RANK`. Leave unset for generation. Unknown values fall back to `UNSPECIFIED`. |
| `attention_type` | string | `UNSPECIFIED` | `CAUSAL` or `NON_CAUSAL`. |
| `flash_attn_type` | string | `AUTO` | `AUTO`, `ENABLED`, `DISABLED`. Quantised V cache needs `ENABLED`. |
| `offload_kqv` | boolean | `true` | Keep the KV cache and attention on the GPU. `false` costs about 3x decode throughput on Metal. |
| `use_mlock` | boolean | `true` | Pin the memory-mapped weights so they are not evicted under pressure. |
| `lora_path` | string | unset | LoRA adapter file in the same repository, downloaded alongside the weights. |
| `mmproj_path` | string | unset | Multimodal projector GGUF in the same repository. Enables `image` / `audio` input. |
| `media_marker` | string | mtmd default (`<__media__>`) | Marker injected once per attachment into the prompt. Set only for a model expecting a different marker. |
| `cache_type_k` | string | unset (`F16`) | KV cache key type: `F32`, `F16`, `BF16`, `Q8_0`, `Q5_1`, `Q5_0`, `Q4_1`, `Q4_0`, `IQ4_NL`. |
| `cache_type_v` | string | unset (`F16`) | KV cache value type, same values; quantised values need `flash_attn_type: ENABLED`. |
| `prompt_cache` | boolean | `true` | Cross-request KV prefix reuse. |
| `prompt_cache_min_tokens` | int | `64` | Minimum shared prefix length before a cached prefix is reused. |
| `eog_ramp_start` | float | unset (disabled) | Fraction of `max_tokens` after which the end-of-generation token is progressively favoured so a length-limited answer ends on a sentence. `<= 0` disables it; the unbiased path is bit-identical. |
| `eog_ramp_max_bias` | float | `100` | Maximum logit bias added to the end-of-generation token at the end of the ramp. |
| `mtp` | boolean | `false` | Self-speculative decoding with the model's own multi-token-prediction (NextN) head. Only for GGUFs that carry one. |
| `draft_model` | string | unset (`name`) | Repository of a separate draft model for speculative decoding. |
| `draft_path` | string | unset | Draft GGUF file name; resolved in `draft_model`, or in `name` when unset. |
| `eagle3_model` | string | unset (`name`) | Repository of an EAGLE3 head. |
| `eagle3_path` | string | unset | EAGLE3 head GGUF; resolved in `eagle3_model`, or in `name` when unset. |
| `speculative` | block | unset | Draft sampling parameters, below. Applied only when `mtp: true`. |

`speculative:` sub-block (`SpeculativeDef`):

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `n_draft` | int | `2` | Draft tokens proposed per step. |
| `draft_min` | int | `n_draft` | Minimum draft length. |
| `p_min` | float | `0` | Minimum draft token probability below which drafting stops. |
| `temperature` | float | unset | Draft sampling temperature. |
| `top_k` | int | unset | Draft top-k. |
| `top_p` | float | `1.0` | Draft top-p. |
| `seed` | long | unset | Draft sampler seed. |

## Notes

- **Memory is `n_ctx x n_seq_max`, plus the weights.** `n_ctx` is per sequence. A 30B MoE at
  `131072 x 2` does not fit a 36 GB machine where the same model at `131072 x 1` does. When
  a model will not load, lower `n_seq_max` before blaming the quantisation. `memory_check`
  runs this arithmetic before the load: `warn` (default) logs, `fail` refuses.
- **Quantisation.** `Q8_0` is close to lossless and right for small models; `Q4_K_M` /
  `UD-Q4_K_S` are the usual choice for anything large. `cache_type_k/v: q8_0` halves the KV
  cache at long context; both require `flash_attn_type: ENABLED`.
- **What gets downloaded.** `GgufModelResolver` fetches exactly `path`, then `mmproj_path`
  and `lora_path` from the same repository, then `draft_path` / `eagle3_path` from their own
  repositories (falling back to `name`). Nothing else is listed or downloaded, so
  `download.exclude` has no effect on this type. Files land in `<cache>/<org>/<model>/`.
- **Only one speculative flavour.** `mtp`, `draft_path` and `eagle3_path` are three ways to
  produce draft tokens; configuring more than one fails the load with an
  `IllegalArgumentException`. The `speculative:` block is forwarded only with `mtp: true`.
- **Prefix caching needs rewindable attention.** Hybrid or linear-attention models
  (Qwen3-Next / Qwen3.6, Kimi-Linear, Granite-4.0-H, Falcon-H1, LFM2, Nemotron-Nano-v2)
  report reuse but re-prefill every turn. Check that a warm turn's `ttft` agrees with its
  `reused_prefix` in the log.
- **Sliding-window models allocate the full-size window cache.** Gemma 4 at `n_ctx: 131072`
  exhausts a 36 GB machine; `32768` fits.
- **Modalities are detected from the projector.** With `mmproj_path` set, the engine reports
  `image` and/or `audio` according to what the mtmd context supports. `modalities:` on the
  entry overrides it.
- **Enum strings are forgiving.** An unknown `pooling_type`, `attention_type`,
  `flash_attn_type`, `cache_type_k/v` silently falls back to the default rather than failing.
- **Platforms.** llamaj.cpp ships natives for macOS/Apple Silicon (Metal) and Linux/x86_64
  (CPU and CUDA), installed by `install.sh` into `~/.llama.cpp`. Keep `LLAMACPP_VERSION` in
  step with the llamaj.cpp version (`b10276` with `2.7.0`); a mismatch is a
  `NoSuchMethodError` at runtime.
- **The raw model returns the raw stream.** Channel markers and tool-call tags are parsed by
  the `infer` step's `tags:`; call a pipeline id for client-facing traffic.

## See also

- [Text generation](../../guides/text-generation/README.md), sampling, streaming, stop strings.
- [Multimodal](../../guides/multimodal/README.md), image and audio content parts with `mmproj_path`.
- [Validated models](../../operations/models/README.md), measured configurations per model.
- [llama_cpp_embedding](./llama_cpp_embedding.md), [llama_cpp_reranker](./llama_cpp_reranker.md), the same engine for encoders.
- [vllm](./vllm.md), the alternative text-generation engine.
