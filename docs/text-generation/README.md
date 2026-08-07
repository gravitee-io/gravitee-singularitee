# Text Generation

> Stream tokens from a published LLM via the `Infer` RPC or an `infer` pipeline step — sampling params, Jinja chat templates, stop strings, and reasoning-tag routing.

## Overview
Text generation runs through a `TextGenEngine` bound to a published model. There are two entry points: the direct `Infer` RPC (one model, one `TextGenRequest`) and the `infer` pipeline step executed by `InferStepExecutor`. Both build a `TextGenRequest` — either a pre-rendered `prompt` string or a structured `messages` list — and stream `ModelEngineToken`s back as `InferResponse` events (`CREATED → OUTPUT_TEXT_DELTA* → COMPLETED | FAILED`). Prompt rendering is Jinja2 everywhere: step message contents are resolved by `JinjaRenderer` against the pipeline context, then the whole conversation is rendered through the model's own chat template by `Jinja4jChatTemplateRenderer`. Reasoning spans (`<think>…</think>`) can be routed to a separate THINKING stream or stripped entirely.

## Key types
- `TextGenEngine` — the engine contract: `rxAddSequence(int seqId, TextGenRequest)` (a `Completable` that completes when the final token is delivered), `rxStream(int seqId)` (`Flowable<ModelEngineToken>`), `chatTemplateString()`, `contextSize()`, `bosToken()`/`eosToken()`, `cancelSequence(int)`.
- `TextGenRequest` — record: `prompt`, `messages` (`List<ChatTurn>`), `maxTokens`, `temperature`, `topP`, `presencePenalty`, `frequencyPenalty`, `stop`, `seed`, `reasoningTags`/`toolCallTags` (`TagConfig`), `loraName`/`loraPath`, `templateContext`.
- `ChatTurn` / `ChatRole` — one conversation turn: `(ChatRole role, String content, List<MediaAttachment> media)`; roles are `SYSTEM`, `USER`, `ASSISTANT`.
- `ModelEngineToken` — one streamed token: `seqId`, `token`, `index`, `isFinal`, `finishReason` (`"stop"`, `"length"`, `"tool_calls"`), per-section counts (`promptTokens`, `completionTokens`, `reasoningTokens`, `toolTokens`), and a final `ModelEnginePerformance`.
- `ModelEnginePerformance` — engine timings: `loadTimeMs`, `promptEvalTimeMs`, `evalTimeMs`, `promptTokensEvaluated`, `tokensGenerated`, `tokensReused`, `samplingTimeMs`, `sampleCount`; mapped to the `InferencePerformance` proto on `COMPLETED`.
- `InferStepExecutor` — the pipeline `infer` step: builds the Jinja context, resolves messages or `raw_template`, renders the chat template, streams via `TokenStreamWriter`/`TokenCaptureStream`, writes `{step_id}.output`, appends the assistant turn, accumulates usage.
- `Jinja4jChatTemplateRenderer` — `render(templateString, messages, tools, addGenerationPrompt, extraVariables)`; compiled templates cached per template string.
- `JinjaRenderer` — cached Jinja2 rendering for step-level templates (`MessageDef.content`, `raw_template`).
- `TokenCaptureStream` — per-step `WriteStream<InferResponse>` that accumulates the output, forwards deltas with the step's `StepRole`, and runs the thinking-tag state machine (`ThinkingMode.ROUTE`/`STRIP`/`NONE`).
- `LlamaCppTextGenEngine` / `VllmTextGenEngine` — the two backends (see below).

## Usage
Direct RPC against a published model:

```shell
grpcurl -plaintext -d '{
  "model_id": "llm",
  "messages": {"messages": [
    {"role": "ROLE_SYSTEM", "content": "You are terse."},
    {"role": "ROLE_USER",   "content": "What is the capital of France?"}
  ]},
  "sampling_params": {"max_tokens": 256, "temperature": 0.7},
  "stop": ["<|im_end|>"]
}' localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/Infer
```

The simplest pipeline (`examples/modular/pipelines/infer.yaml`) — no `prompt:` block, so the caller's messages pass through as-is:

```yaml
workspace:
  pipelines:
    - id: infer-pipeline
      name: Inference Pipeline
      entry: infer
      steps:
        - id: infer
          type: infer
          role: output
          config:
            model_id: llm
            output_field: infer.output
            sampling:
              max_tokens: 512
```

Separating reasoning from the answer (`examples/modular/pipelines/reasoning.yaml`): declaring `tags.reasoning_open/close` without `strip_thinking` ROUTEs the `<think>…</think>` span onto a `STEP_ROLE_THINKING` stream — surfaced as `reasoning_content` on the OpenAI HTTP API:

```yaml
        - id: infer
          type: infer
          role: output
          config:
            model_id: llm
            output_field: infer.output
            tags:
              reasoning_open: "<think>"
              reasoning_close: "</think>"
            sampling:
              max_tokens: 512
```

Templated prompts: a step can override messages with Jinja2 content, or bypass the chat template entirely with a raw template (`prompt.template`, `prompt.template_file`, or `prompt.template_id` referencing a workspace `templates:` entry — mutually exclusive):

```yaml
          config:
            model_id: llm
            prompt:
              messages:
                - role: system
                  content: "Answer using only: {{retrieve.output}}"
                - role: user
                  content: "{{prompt}}"
            context:
              enable_thinking: false   # extra chat-template variable
```

## Options

### `infer` step config (workspace YAML)
| Field | Default | Purpose |
| --- | --- | --- |
| `model_id` | required | Logical id of a published text-gen model. |
| `output_field` | `{step_id}.output` | Context key the full generated text is written to. |
| `prompt.messages` | caller's messages | Message list; each `content` is a Jinja2 template over the pipeline context. |
| `prompt.template` / `template_file` / `template_id` | unset | Raw Jinja2 prompt (mutually exclusive; maps to `raw_template`) — bypasses the model's chat template. |
| `sampling.max_tokens` | engine default | Generation cap; hitting it ends with `finishReason "length"`. |
| `sampling.temperature` / `top_p` / `presence_penalty` / `frequency_penalty` | engine defaults | Sampling controls (0 / unset = engine default). |
| `sampling.stop` | none | Stop strings — generation halts when one is emitted (`finishReason "stop"`). |
| `tags.reasoning_open` / `reasoning_close` | `<think>` / `</think>` (strip mode) | Reasoning-span markers; declaring them routes the span to the THINKING stream. Each accepts a **string or a list** — a channel can be entered and left more than one way. |
| `tags.tool_open` / `tool_close` | unset | Tool-call markers, string or list. The engine counts tool tokens and emits `finishReason "tool_calls"`. |
| `tags.reasoning_repeatable` | unset (engine default) | Whether the reasoning channel may be entered **again** after it closes, within one generation. Unset leaves the engine's rule — tool channels repeat, everything else occurs once. |
| `strip_thinking` | `false` (ROUTE) | `true` removes the reasoning span from both the stream and the step output. |
| `context` | empty | Extra typed Jinja variables (e.g. `enable_thinking: false`) merged into the rendering context and forwarded as `template_context`. |
| `inject_tools` | `true` | `false` hides caller tools from this step (`{{tools}}` undefined, nothing passed to the chat template). |
| `role` (step level) | `output` | `output` = streamed as OUTPUT; `thinking` = streamed tagged THINKING; `internal` = never streamed and not appended to the conversation. |

#### How markers are matched

Markers are **syntax, not content**: the engine suppresses them, so a client never
sees `<think>` or `<|call|>` in the text it is handed. Three rules follow from that,
and each one is a bug someone has already hit:

- **Matched at the start of a run, longest first.** A marker only matches where a
  run begins — after the previous marker resolved, or at the start of generation.
  A variant that is not configured therefore never matches at all: it refutes, and
  the whole header leaks into the channel it interrupted as visible text. Configure
  every form the model actually emits; a shortened marker is not a substitute,
  because the run boundary is the full header.
- **Split markers are buffered, never leaked.** Text that is a strict prefix of a
  candidate is withheld until the marker completes (suppressed) or the text diverges
  (released into the current channel, in order). A generation that stops mid-marker
  flushes the fragment rather than dropping it. The cost is one token of latency on
  text that merely *starts* like a marker.
- **A close marker reaching ANSWER is stray syntax and is suppressed there too.**
  It cannot be closing anything — no span is open — and models do emit them: after
  a tool call, the next turn starts fresh and dialects like Harmony still prefix the
  reply with their final-channel header. List that bare header among the closes or
  it reaches the client as `<|channel|>final<|message|>DONE`.

- **A channel occurs once unless it says otherwise.** That default comes from
  ChatML, where `<think>…</think>` really is one block per generation, and it is
  what stops a literal `<think>` typed into an answer from re-opening reasoning.
  Chained dialects break the assumption: Harmony can run analysis, return to the
  final channel, then open a commentary preamble — all in a **single** generation.
  A channel that cannot re-open simply stops matching, so the second header lands
  in the answer as raw text (`<|channel|>commentary<|message|>Now step 2…`) and
  its tokens are billed as answer rather than reasoning. Set
  `reasoning_repeatable: true` for those dialects. The tool channel has always
  repeated, for the same reason — models emit several calls.

A turn that **ends inside** the tool channel is reported as `finishReason
"tool_calls"`, not `"stop"`. That matters when the close marker is also an EOS token
(Harmony's `<|call|>`): generation halts on it, so no transition is ever observed
mid-stream, and without this the span is rendered to the user as prose instead of
being extracted and executed. An empty span never counts — a provisional entry that
resolves straight back out captured nothing to call.

Every generated token is also stamped with its channel (ANSWER / REASONING / TOOL)
by the engine, which is what downstream reads. With a `<think>`-prefilled prompt, or
after suppression, no literal marker survives in the text — the classification is the
only signal left.

### `SamplingParams` (proto, `Infer`/`InferPipeline` request override)
| Field | Default | Purpose |
| --- | --- | --- |
| `max_tokens` | 0 = engine default | Max tokens to generate. |
| `temperature` | 0 = engine default | Sampling temperature. |
| `top_p` | 0 = engine default | Nucleus threshold. |
| `presence_penalty` / `frequency_penalty` | 0 | Repetition penalties. |
| `seed` | 0 = random | RNG seed. |

Request-level `sampling_params` override the step's `sampling` block field-by-field (non-zero wins) — on pipelines they apply to the first infer step reached.

### Model config — `llama_cpp:` block (`LlamaCppTextGenEngine`)
Backed by the vendored llama.cpp `BatchEngine` (llamaj.cpp FFM bindings); parallel sequences decode through one shared context. Defaults applied in `LlamaCppEngineFactory`:

| Field | Default | Purpose |
| --- | --- | --- |
| `path` | required | GGUF file (resolved locally or via HuggingFace). |
| `n_ctx` | `4096` | Per-sequence context window. |
| `n_batch` / `n_ubatch` | `2048` / `512` | Logical / physical batch size. |
| `n_seq_max` | `8` | Max parallel sequences. |
| `n_gpu_layers` | `999` | Layers offloaded to GPU. |
| `pooling_type` / `attention_type` / `flash_attn_type` | unspecified / unspecified / — | llama.cpp enums (e.g. `flash_attn_type: AUTO`). |
| `offload_kqv` | `false` | Offload the KV cache to GPU. |
| `lora_path` | unset | GGUF LoRA adapter. |
| `eog_ramp_start` / `eog_ramp_max_bias` | disabled / `100` | Budget-aware soft landing — see below. |
| `mmproj_path` / `media_marker` | unset | Multimodal projector (see [Multimodal](../multimodal/README.md)). |

### Model config — `vllm:` block (`VllmTextGenEngine`)
Backed by vllm4j (Linux/CUDA only; requires `-Dvllm4j.venv=`): `dtype`, `quantization` (`awq`, `gptq`, ...), `max_model_len`, `max_num_seqs`, `max_num_batched_tokens`, `gpu_memory_utilization`, `enforce_eager`, `trust_remote_code`, `seed`, `enable_prefix_caching`, `enable_chunked_prefill`, `kv_cache_dtype`, `enable_lora`/`max_loras`/`max_lora_rank`, `enable_sleep_mode`, `tensor_parallel_size`/`pipeline_parallel_size`/`distributed_executor_backend` (multi-GPU; falls back to the server-wide `ai.vllm.*` settings when a model does not set them). See `examples/modular/models/vllm/llm-qwen3-awq.yaml`. vLLM additionally supports native function-calling via `tools_json` on `InferRequest`.

### Budget-aware EOG ramp (soft landing)

`max_tokens` normally severs the answer mid-word. Enable the ramp in the model's
`llama_cpp:` block to make the budget a pressure instead:

```yaml
llama_cpp:
  path: model.gguf
  eog_ramp_start: 0.75     # fraction of max_tokens where the ramp begins
  eog_ramp_max_bias: 100.0 # logit boost in nats at the cap
```

Past `eog_ramp_start * max_tokens`, end-of-generation logits climb a quadratic ramp —
but only where the text can stop: after sentence punctuation or a line break, widened
to clause punctuation near the cap. The answer then ends **short** of the budget on a
finished sentence. Below the threshold sampling is untouched, and a non-positive
`eog_ramp_start` (the default) disables the feature entirely.

Measured on the same prompt, disabled vs. `0.6 / 100`, on a 35B-A3B model with MTP
self-speculative decoding. That model has no reasoning phase, so `completion_tokens`
is exactly the answer:

| `max_tokens` | disabled | enabled |
| --- | --- | --- |
| 80 | 80 — `…transpiration, where plants release moisture` | **64** — `…vapor, rising silently into the atmosphere.` |
| 200 | 200 — `…the water follows several paths: some of` | **169** — `…overcomes the air's ability to hold them,` |
| 400 | 286, `stop` — finished on its own | 286, `length` — same text, the budget never bit |

And on a 20B reasoning model at `0.75 / 100`:

| `max_tokens` | disabled | enabled |
| --- | --- | --- |
| 150 | 166 tok — `…embrace of higher altitudes where the air th` | **153** — `…now no longer bound to the earth's surface,` |
| 250 | 266 tok — `…tiny particles of water clinging to dust and` | **234** — `…a silent note in an ever‑evolving symphony.` |

Every enabled row ends on punctuation; every disabled row is severed mid-word.
Two rows deserve a second look rather than a glance:

- `completion_tokens` exceeding `max_tokens` (166 for a 150 budget) is not a bug.
  The budget governs *answer* tokens; the usage field also counts reasoning. Read
  the first table when the number needs to mean what it looks like.
- The 400 row is deliberately a non-event: the model ended on its own, and the
  output is identical either way. It still reports `length`, because the ramp was
  active on the row that produced the EOG — accurate, but not the feature working.

Notes:

- `finish_reason` stays `"length"`, so agent loops still see that the answer was
  budget-limited and can decide to continue.
- The hard cap remains as a backstop for answers with no boundary in the ramp window.
- The bias sets how much margin the landing gets. EOG sits far below the running
  text in raw logits mid-answer, so a small boost is inert — and an inert ramp looks
  exactly like a disabled one. Measured at a 200-token budget: `12` never won, `24`
  landed at 191, `100` at 169.
- A request without `max_tokens` falls back to the remaining context, putting the
  threshold tens of thousands of tokens out — the model finishes naturally long
  before, so the ramp is inert in practice.
- It is a per-model serving policy, not a per-request knob; every request to that
  model uses the same ramp.

## Notes
- **Two prompt paths**: with messages, the conversation is rendered through the model's own chat template (from the GGUF / tokenizer config) via `Jinja4jChatTemplateRenderer` with `add_generation_prompt=true`, `bos_token`/`eos_token`, and OpenAI-shaped `tools`. With `raw_template`, that rendering is bypassed entirely — you own every special token (used for Llama-Guard-style prompts).
- **No chat template ≠ concatenation**: when `chatTemplateString()` is null (e.g. remote model metadata not yet fetched), the executor never degrades to `role: content` joining — it forwards the structured messages so the model server renders with its own template, passing `context:` variables through `template_context`.
- **`strip_thinking` vs `enable_thinking`** are independent: `context.enable_thinking` controls whether the model is *asked* to think (a chat-template variable); `strip_thinking` controls whether emitted reasoning is *shown*. Default thinking handling is ROUTE — reasoning is streamed as THINKING deltas while the step output keeps the raw text (tags included).
- **Context-limit safety**: `AbstractTextGenEngine` watches token counts and force-stops a sequence that would overflow the context, synthesizing a final token with `finishReason "length"` so the stream always completes.
- **Finish reasons**: engine strings map to proto `FinishReason` — `"stop"` → `FINISH_REASON_STOP`, `"length"` → `FINISH_REASON_LENGTH`, `"tool_calls"` → `FINISH_REASON_TOOL_CALLS` (emitted when the `tool_call_tags` close tag is seen).
- **Roles shape history**: `output`/`thinking` steps append their text as an assistant `ChatTurn`; `internal` steps (graders, routers emitting `YES`/`NO`) do not, keeping metadata out of the conversation downstream steps see. `{step_id}.output` is overwritten per loop iteration; `generated_messages` keeps every draft.
- **Usage is accumulated per pipeline**: each completed infer step folds its `TokenUsage` and `InferencePerformance` into `PipelineContext.accumulateUsage`; the terminal `ResponseCompleted` carries the totals (`prompt_tokens`, `completion_tokens`, `reasoning_tokens`, `tool_tokens`, eval times).
- **A pre-rendered `prompt` is never re-templated** — `template_context` on `InferRequest` is ignored when `prompt` is set instead of `messages`.
- **LoRA per request/step**: `lora.lora_name`/`lora_path` on the request or step select an adapter at generation time (llama.cpp; vLLM needs `enable_lora` in the model block).

## See also
- [Pipelines](../pipelines/README.md) — where the `infer` step lives: DAG walk, context scratchpad, finish reasons.
- [Multimodal](../multimodal/README.md) — attaching images/audio to chat turns.
- [OpenAI HTTP API](../openai-http-api/README.md) — `chat/completions` over these same engines, `reasoning_content` streaming.
- [Loops & Chain-of-Thought](../loops-and-cot/README.md) — multi-step generation with revision loops.
- [Workspaces](../workspaces/README.md) — declaring models, templates, and pipelines in YAML.
