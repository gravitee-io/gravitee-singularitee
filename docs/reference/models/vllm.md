# vllm

> HuggingFace Transformers checkpoints served by vLLM from an embedded CPython interpreter: opt-in, Linux/CUDA-first, with deployment-wide GPU topology defaults.

## Overview

`vllm` loads a safetensors (or `.bin`) checkpoint named by `name` and serves it as a
`TextGenEngine` (`text-generation`). The JVM loads CPython out of a virtualenv that vLLM4j
reads from `-Dvllm4j.venv`; `run-server.sh` finds `~/.venv-gravitee-ai/.venv` and passes it.
`VllmModelResolver` downloads the checkpoint in Java so all engines share one cache;
`VllmEngineFactory` builds the engine and reads `image` / `audio` support from the
checkpoint's `config.json`.

## Usage

`examples/vllm/qwen3-0.6b.yaml`:

```yaml
workspace:
  name: qwen3-0.6b
  models:
    - id: llm
      name: Qwen/Qwen3-0.6B
      type: vllm
      memory_check: warn
      vllm:
        dtype: auto
        max_model_len: 40960
        max_num_seqs: 16
        gpu_memory_utilization: 0.45
        enforce_eager: false
        enable_prefix_caching: true
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

A quantised checkpoint (`examples/modular/models/vllm/llm-qwen3-awq.yaml`) adds `quantization: awq` and
`trust_remote_code: true`. A repository carrying duplicate weights
(`examples/vllm/gpt-oss-20b-mac.yaml`) trims the transfer:

```yaml
      download:
        exclude:
          - "original/*"
      vllm:
        max_model_len: 16384
        max_num_seqs: 2
        max_num_batched_tokens: 2048
        enable_chunked_prefill: true
        gpu_memory_utilization: 0.5
        enforce_eager: true
```

## Options

`vllm:` block (`WorkspaceDefinition.VllmDef`). Defaults are what `VllmEngineFactory` applies.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `dtype` | string | `auto` | Weight/activation dtype (`auto`, `float16`, `bfloat16`, ...). |
| `max_model_len` | int | unset (model's own) | Context length. |
| `max_num_seqs` | int | `1` | Concurrent sequences. |
| `gpu_memory_utilization` | double | `0.5` | Fraction of total GPU memory vLLM may claim, weights and KV cache included. |
| `max_num_batched_tokens` | int | unset (vLLM default) | Tokens per scheduler step. |
| `enforce_eager` | boolean | `false` | Skip CUDA graph capture. |
| `trust_remote_code` | boolean | `false` | Allow the checkpoint's custom modelling code. |
| `quantization` | string | unset | `awq`, `gptq`, `fp8`, ... |
| `seed` | int | unset | Sampler seed, forwarded when `> 0`. |
| `enable_prefix_caching` | boolean | unset (vLLM default) | Three-valued: unset leaves vLLM's default, `false` disables. |
| `prompt_cache` | boolean | unset | Alias of `enable_prefix_caching`; either set to `true` enables it. |
| `enable_chunked_prefill` | boolean | `false` | Split long prompts across scheduler steps. |
| `kv_cache_dtype` | string | unset | KV cache dtype (`fp8`, ...). |
| `enable_lora` | boolean | `false` | Enable LoRA adapters. |
| `max_loras` | int | unset | Concurrent LoRA adapters, forwarded when `> 0`. |
| `max_lora_rank` | int | unset | Maximum adapter rank, forwarded when `> 0`. |
| `enable_sleep_mode` | boolean | unset | Nullable; forwarded only when present. |
| `tensor_parallel_size` | int | server default, then vLLM's | GPUs each layer is sharded across. |
| `pipeline_parallel_size` | int | server default, then vLLM's | Pipeline stages. |
| `distributed_executor_backend` | string | server default, then vLLM's | `mp` or `ray`. |

### Deployment-wide `ai.vllm.*` defaults

The GPU topology describes the machine, not the model, so it can be set once per
deployment in `gravitee.yml` (or the matching `GRAVITEE_AI_VLLM_*` environment variables) and
is applied to every `vllm` model that leaves its own key unset:

| Property | Fills in |
| --- | --- |
| `ai.vllm.tensor-parallel-size` | `tensor_parallel_size` |
| `ai.vllm.pipeline-parallel-size` | `pipeline_parallel_size` |
| `ai.vllm.distributed-executor-backend` | `distributed_executor_backend` |

Precedence: the model's own value, then the deployment default, then vLLM's default. Unset or
non-positive properties count as unset.

## Notes

- **Opt-in.** The build skips the venv (`vllm.setupVenv.skip=true`). Create it with
  `./scripts/setup-venv.sh -b cuda` (or `metal`, `cpu`) and run any `examples/vllm/*.yaml`;
  `--venv` or `$VLLM_VENV` point elsewhere. vLLM4j is compiled against one vLLM Python API:
  keep the venv's vLLM at the pinned version (`0.26.0`, matching `Dockerfile.vllm-cuda`).
- **`gpu_memory_utilization` is a fraction of total memory.** On Apple Silicon it applies to
  total unified memory, so use the `*-mac.yaml` examples there; the other files are sized
  for datacenter cards (40 to 80 GB). When the budget cannot hold the weights the load is refused up front with
  the numbers, before vLLM's own cache-block error.
- **Memory check reads the checkpoint.** `memory_check` sizes the model from `config.json`
  (layers, KV heads, head dimension, context) and the safetensors index, honouring
  quantisation (AWQ is sized at 4 bits per weight). If the shape cannot be read the check is
  skipped with a warning.
- **What gets downloaded.** `VllmModelResolver` lists the repository and takes the metadata
  (`.json`, `.txt`, `.model`, `.jinja`) plus one weight format, safetensors preferred,
  `.bin` only when the repository has no safetensors. GGUF, ONNX and other runtimes' copies
  are never fetched. `download.exclude` narrows this further and is applied before the weight
  format is chosen. A `.complete` marker is written once every selected file is on disk, and
  only that marker makes the next start skip the download: an interrupted download (a missing
  shard, say) is finished instead of failing at load.
- **Pre-Ampere GPUs are adapted.** On compute capability below 8.0 `dtype: auto` resolves to
  `float16` and the attention backend is pinned to `TRITON_ATTN`; an explicit `dtype:` or
  `VLLM4J_ATTENTION_BACKEND` wins.
- **Modalities.** `vision_config` / `audio_config` in `config.json` make the model report
  `image` / `audio`. A model never resolved to a local directory reports text-only; declare
  `modalities:` there.
- **Any of the three topology keys above its default switches vLLM to the V1 engine with
  subprocess workers.**
- **One backend per process.** Do not co-locate `vllm` with llama.cpp or ONNX models; the
  native libraries and GPU memory conflict. Use `examples/modular/server-vllm-*.yaml` with a
  client workspace.

## See also

- [Text generation](../../guides/text-generation/README.md).
- [Deployment](../../operations/deployment/README.md), the CUDA image and its build arguments.
- [Configuration](../configuration.md), `ai.vllm.*` and `vllm4j.venv`.
- [llama_cpp](./llama_cpp.md), the default engine.
