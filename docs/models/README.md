# Validated Models

Models validated end-to-end on this server — reasoning routing, structured tool
calls, prefix caching — with the exact workspace configuration used. Each model
speaks its own output dialect; on this server a dialect is **configuration**
(channel tag strings + a tool-extraction template), never code.

## Ground rules for any model

1. **Attention architecture decides caching.** Plain softmax attention (or
   sliding-window with the full-size SWA cache) supports cross-request prefix
   reuse. Hybrid/linear-attention models (Qwen3-Next/3.6, Kimi-Linear,
   Granite-4.0-H, Falcon-H1, LFM2, Nemotron-Nano-v2) **cannot rewind their
   state**: the cache reports reuse but every turn silently re-prefills.
   Verify on the log: a warm turn's `ttft` must agree with its
   `reused_prefix`.
2. **MoE decides decode speed** on memory-bandwidth-bound hardware (Apple
   Silicon): few active parameters → fast decode. MoE and attention type are
   independent axes — check both.
3. **Dialect onboarding checklist**: chat template parses (jinja4j) → channel
   tag strings (`tags:`) → extraction template (built-in or inline
   `tool_extraction_template`) → validate with a tool-call request.

---

## Qwen3-30B-A3B-Instruct-2507 · ChatML

MoE (3B active), plain attention, Apache-2.0. The reference agent model:
built-in dialect, nothing to configure. Measured on an M4 Max: ~95 ms warm
TTFT, ~84 tok/s decode (drifting to ~34 tok/s at 30k context — full-attention
depth tax).

```yaml
models:
  - id: llm
    name: unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF
    type: llama_cpp
    memory_check: warn
    llama_cpp:
      path: Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf
      n_ctx: 131072
      n_seq_max: 2
      n_gpu_layers: 999
      flash_attn_type: ENABLED      # required for quantized V cache
      cache_type_k: q8_0
      cache_type_v: q8_0
      mtp: false                    # no MTP head on this model

pipelines:
  - id: agent
    entry: agent
    steps:
      - id: agent
        type: infer
        role: output
        config:
          model_id: llm
          output_field: agent.output
          context:
            enable_thinking: false
          tags:
            reasoning_open: "<think>"
            reasoning_close: "</think>"
            tool_open: "<tool_call>"
            tool_close: "</tool_call>"
          # extraction: chatml-json built-in (default order) — nothing to set
```

## Gemma 4 26B-A4B (QAT) · channel markers

MoE (4B active), sliding-window attention, Gemma license. Measured: ~23 ms warm
TTFT with full prefix reuse — SWA decode cost is capped at the window, so
speed holds at depth. Two caveats: the **full-size SWA cache is memory-hungry**
(131k ctx OOMs a 36 GB Mac next to the weights — 32k fits), and the thinking
channel's generation-time grammar is unstable — run it **thinking-off**.

```yaml
models:
  - id: llm
    name: google/gemma-4-26B-A4B-it-qat-q4_0-gguf
    type: llama_cpp
    memory_check: warn
    llama_cpp:
      path: gemma-4-26B_q4_0-it.gguf
      n_ctx: 32768                  # full-size SWA cache: keep the window modest
      n_seq_max: 1
      n_gpu_layers: 999
      flash_attn_type: ENABLED
      cache_type_k: q8_0
      cache_type_v: q8_0
      mtp: false                    # Gemma 4 MTP uses separate draft GGUFs

pipelines:
  - id: agent
    entry: agent
    steps:
      - id: agent
        type: infer
        role: output
        config:
          model_id: llm
          output_field: agent.output
          context:
            enable_thinking: false  # generation-time channel grammar is unstable
          tags:
            reasoning_open: "<|channel>thought"
            reasoning_close: "<channel|>"
            tool_open: "<|tool_call>"
            tool_close: "<tool_call|>"
          tool_extraction_template: gemma-call   # built-in
```

## gpt-oss-20b · Harmony (chained channels)

MoE (3.6B active), full+SWA attention, Apache-2.0, native MXFP4 (~12 GB, no
quantization loss). Harmony **chains** channels instead of nesting them — an
analysis span ends either into the final answer or directly into a tool call —
expressed below by folding each continuation header into the corresponding
marker.

Every marker field takes a **list**, and Harmony needs all of them.

*Opening a call* happens in both header orders — `<|start|>assistant<|channel|>commentary
to=functions.NAME` and the form without the channel segment — and on both the commentary
and analysis channels ("calls must go to commentary" is a system-prompt instruction, not
grammar, and the model does not always obey it). A run that leaves the analysis channel
for a tool call also *starts at the terminator*, `<|end|>` or `<|call|>`, because nothing
else consumes it — so a marker anchored at `<|channel|>` can never match there. Configure
all of them; an unmatched variant leaks its whole header into the answer as text.

*Leaving the analysis channel* happens two ways: after `<|end|>` when the model answers
directly, after `<|call|>` when a tool call intervened. And the turn that **follows** a
tool call starts fresh with no span open, yet still prefixes its reply with the bare
`<|channel|>final<|message|>` — list it among the closes or the reply reads
`<|channel|>final<|message|>DONE`.

*Closing a call* is `<|call|>`, which is also an EOS token: generation stops on it, and
the longer `<|call|><|start|>assistant<|channel|>final<|message|>` form covers the turn
that continues past the call. Longest match wins, so listing both is safe.

Reasoning effort is set by system-prompt text (e.g. `Reasoning: low`), not a template
flag; `low` is fast but writes malformed JSON for tool arguments often enough to break
agent turns, so `medium` is the default here. This configuration is identical for
**gpt-oss-120b** on larger hardware.

```yaml
models:
  - id: llm
    name: ggml-org/gpt-oss-20b-GGUF
    type: llama_cpp
    memory_check: warn
    llama_cpp:
      path: gpt-oss-20b-MXFP4.gguf
      n_ctx: 32768
      n_seq_max: 1
      n_gpu_layers: 999
      flash_attn_type: ENABLED
      cache_type_k: q8_0
      cache_type_v: q8_0
      mtp: false

pipelines:
  - id: agent
    entry: agent
    steps:
      - id: agent
        type: infer
        role: output
        config:
          model_id: llm
          output_field: agent.output
          tags:
            reasoning_open: "<|channel|>analysis<|message|>"
            # folded closes: reasoning ends into the final-answer header, whichever
            # terminator got it there — plus the bare header for the turn AFTER a
            # tool call, which opens no span of its own
            reasoning_close:
              - "<|end|><|start|>assistant<|channel|>final<|message|>"
              - "<|call|><|start|>assistant<|channel|>final<|message|>"
              - "<|channel|>final<|message|>"
            # every header order and every run anchor — see note above
            tool_open:
              - "<|end|><|start|>assistant<|channel|>commentary to=functions."
              - "<|end|><|start|>assistant<|channel|>analysis to=functions."
              - "<|call|><|start|>assistant<|channel|>commentary to=functions."
              - "<|end|><|start|>assistant to=functions."
              - "<|start|>assistant<|channel|>commentary to=functions."
              - "<|start|>assistant to=functions."
              - "<|channel|>commentary to=functions."
              - "<|channel|>analysis to=functions."
            # longest first: the call may continue into the final channel, or end on
            # <|call|> as EOS
            tool_close:
              - "<|call|><|start|>assistant<|channel|>final<|message|>"
              - "<|call|>"
          # captured span: "NAME <|constrain|>json<|message|>{json args}"
          tool_extraction_template: harmony   # built-in
```

## Qwen2.5-0.5B-Instruct · background tasks

Co-hosted small model for lightweight work (title generation, summaries) so
background jobs never touch the main model's KV slot. Pre-thinking model
family: it cannot emit reasoning, by construction — preferable here to a small
Qwen3, which ignores `enable_thinking: false` unreliably.

```yaml
models:
  - id: qwen-small
    name: Qwen/Qwen2.5-0.5B-Instruct-GGUF
    type: llama_cpp
    memory_check: warn
    llama_cpp:
      path: qwen2.5-0.5b-instruct-q8_0.gguf
      n_ctx: 8192
      n_seq_max: 1
      n_gpu_layers: 999
      flash_attn_type: AUTO
      mtp: false

pipelines:
  - id: small
    entry: gen
    steps:
      - id: gen
        type: infer
        role: output
        config:
          model_id: qwen-small
          context:
            enable_thinking: false
          tags:
            reasoning_open: "<think>"
            reasoning_close: "</think>"
```

## GLM-4-9B-0414 · markerless

Dense 9B, MIT. Its tool dialect has **no markers**: a call is the whole
message — `function_name` on the first line, JSON arguments after. The
`glm-name-json` built-in extraction template handles it (first-line
tool-name matching keeps false positives near zero; extraction runs on plain
completions when a template is explicitly configured). Validated end-to-end
with the `lmstudio-community` conversion.

Notes: GLM's non-ASCII template text exposed a tokenizer byte-length bug
(fixed in llamaj.cpp — UTF-16 char count passed as UTF-8 byte length
silently truncated any multi-byte prompt); both tested GGUF conversions have
incomplete EOG lists, so the model may role-play past its tool call — the
extraction template cuts at the first turn marker. Keep sampling modest.

```yaml
models:
  - id: glm
    name: lmstudio-community/GLM-4-9B-0414-GGUF
    type: llama_cpp
    memory_check: warn
    llama_cpp:
      path: GLM-4-9B-0414-Q4_K_M.gguf
      n_ctx: 8192
      n_seq_max: 1
      n_gpu_layers: 999
      flash_attn_type: AUTO
      mtp: false

pipelines:
  - id: glm-fast
    entry: agent
    steps:
      - id: agent
        type: infer
        role: output
        config:
          model_id: glm
          output_field: agent.output
          tool_extraction_template: glm-name-json
          sampling:
            temperature: 0.6
            top_p: 0.85
          # optional: compact tool schemas (~half the tool-block tokens) via a
          # workspace template override of the official chat template:
          # chat_template: glm-compact
```

The same template and dialect apply to **GLM-4-32B-0414**; **GLM-4.5-Air**
(larger hardware) is a newer generation — `<think>` reasoning plus an
`<arg_key>`/`<arg_value>` tool grammar needing its own small extraction
template (its chat template parses on jinja4j).
---
