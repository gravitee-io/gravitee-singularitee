"""Smoke-test the OpenAI-compatible API with the official `openai` Python client.

Exercises chat completions (stream + non-stream) and the Responses API
(stream + non-stream), printing content, reasoning (reasoning_content /
reasoning items), and the streamed event types.

Works against either:
  * Singularitee's native HTTP API   — BASE_URL=http://localhost:8080/v1
  * the Gravitee gateway (llm-proxy)  — BASE_URL=http://localhost:8082/<api>/v1

Setup:
    python3 -m venv .venv && .venv/bin/pip install openai

Env:
    BASE_URL  default http://localhost:8080/v1
    MODEL     default: auto-pick "llm" or the first model from /v1/models
              (gateway model ids look like "<group>:<pipeline>", e.g. gamma:reasoning-pipeline)
    API_KEY   default sk-noauth (ignored unless Bearer auth is enabled)

Run with uv (https://docs.astral.sh/uv/):
    BASE_URL=http://localhost:8080/v1 MODEL=reasoning-pipeline uv run --with openai examples/scripts/openai_test.py
"""
import os
import time
import traceback

from openai import OpenAI

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/v1")
API_KEY = os.environ.get("API_KEY", "sk-noauth")
client = OpenAI(base_url=BASE_URL, api_key=API_KEY)


def wait_for_server(timeout=120):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            return [m.id for m in client.models.list().data]
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2)
    raise SystemExit(f"Server at {BASE_URL} not reachable within {timeout}s: {last}")


def section(title):
    print("\n" + "=" * 70 + f"\n{title}\n" + "=" * 70)


print(f"base_url = {BASE_URL}")
ids = wait_for_server()
print("models   =", ids)
MODEL = os.environ.get("MODEL") or ("llm" if "llm" in ids else (ids[0] if ids else "llm"))
print("model    =", MODEL)

# ── 1. chat.completions — non-streaming ──────────────────────────────────
section("chat.completions (non-stream)")
try:
    r = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Say hello in exactly three words."}],
    )
    msg = r.choices[0].message
    print("content          :", msg.content)
    print("reasoning_content:", getattr(msg, "reasoning_content", None))
    print("finish_reason    :", r.choices[0].finish_reason)
    print("usage            :", r.usage)
except Exception:  # noqa: BLE001
    traceback.print_exc()

# ── 2. chat.completions — streaming ──────────────────────────────────────
section("chat.completions (stream)")
try:
    acc = ""
    for chunk in client.chat.completions.create(
        model=MODEL,
        stream=True,
        messages=[{"role": "user", "content": "Count from 1 to 5, comma separated."}],
    ):
        if not chunk.choices:  # trailing usage chunk carries no choices
            continue
        delta = chunk.choices[0].delta
        if getattr(delta, "content", None):
            acc += delta.content
            print(delta.content, end="", flush=True)
    print(f"\n[accumulated] {acc!r}")
except Exception:  # noqa: BLE001
    traceback.print_exc()

# ── 3. responses — non-streaming ─────────────────────────────────────────
section("responses (non-stream)")
try:
    resp = client.responses.create(model=MODEL, input="Name two primary colors.")
    print("status      :", resp.status)
    reasoning_items = [it for it in resp.output if getattr(it, "type", None) == "reasoning"]
    for it in reasoning_items:
        summary = " ".join(s.text for s in (it.summary or []))
        print("reasoning   :", (summary[:300] + "…") if len(summary) > 300 else summary)
    print("output_text :", resp.output_text)
    print("usage       :", resp.usage)
except Exception:  # noqa: BLE001
    traceback.print_exc()

# ── 4. responses — streaming ─────────────────────────────────────────────
section("responses (stream)")
try:
    acc = ""
    reasoning_acc = ""
    seen = []
    for event in client.responses.create(
        model=MODEL, input="Count from 1 to 3.", stream=True
    ):
        seen.append(event.type)
        if event.type == "response.reasoning_summary_text.delta":
            reasoning_acc += event.delta
        elif event.type == "response.output_text.delta":
            acc += event.delta
            print(event.delta, end="", flush=True)
    print(f"\n[distinct event types] {sorted(set(seen))}")
    print(f"[reasoning] {reasoning_acc[:300]!r}")
    print(f"[accumulated] {acc!r}")
except Exception:  # noqa: BLE001
    traceback.print_exc()

print("\nDONE")
