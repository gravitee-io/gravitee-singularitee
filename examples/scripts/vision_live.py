"""LIVE vision demo: stream the webcam into the model and overlay its answer.

A live window shows the camera feed. Every INTERVAL seconds (default 0.6) it grabs the
NEWEST frame and sends it to the vision model, overlaying the reply on the video. The
model call runs on a worker thread so the video stays smooth.

There is no native video input — "live video" = one recent still per query, which is what
a VLM (e.g. Qwen3-VL) expects. Point this at examples/llama/qwen3-vl-2b.yaml.

**Only one request is ever in flight.** 600ms is a target, not a guarantee: if the model
takes longer than that, ticks are DROPPED rather than queued — queueing would make the
overlay drift further behind the live feed with every tick. The HUD shows the achieved
rate and how many ticks were dropped, so you can see what the model actually sustains.
If you are dropping most ticks, raise INTERVAL, lower MAX_TOKENS or IMG_WIDTH, or use a
smaller VLM.

Keys:  SPACE = ask now    q / ESC = quit

Requires: openai, opencv-python (numpy comes with opencv).
macOS will prompt for Camera permission for your terminal/IDE.

Setup:
    python3 -m venv .venv && .venv/bin/pip install openai opencv-python

Env:
    BASE_URL    default http://localhost:8080/v1
    MODEL       default: "llm" (or first model from /v1/models)
    API_KEY     default sk-noauth
    CAMERA      camera device index                 (default 0)
    NUM_FRAMES  frames per query                     (default 1 — the newest)
    INTERVAL    auto-ask every N seconds; 0 = manual (default 0.6)
    MAX_TOKENS  cap on the reply; keeps latency inside the interval (default 48)
    IMG_WIDTH   longest edge sent to the model       (default 448; lower = faster)
    PROMPT      question asked about the live capture
    FONT_SCALE  overlay text size                    (default 1.1; try 1.5 for bigger)

Run with uv (https://docs.astral.sh/uv/):
    uv run --with openai --with opencv-python examples/scripts/vision_live.py
"""
import base64
import os
import threading
import time
from collections import deque

import cv2  # opencv-python
import numpy as np
from openai import OpenAI

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/v1")
API_KEY = os.environ.get("API_KEY", "sk-noauth")
CAMERA = int(os.environ.get("CAMERA", "0"))
NUM_FRAMES = int(os.environ.get("NUM_FRAMES", "1"))
INTERVAL = float(os.environ.get("INTERVAL", "0.6"))
MAX_TOKENS = int(os.environ.get("MAX_TOKENS", "48"))
IMG_WIDTH = int(os.environ.get("IMG_WIDTH", "448"))
PROMPT = os.environ.get(
    # Terse on purpose: at a 600ms cadence the reply has to come back inside the
    # interval, and generation time is dominated by how many tokens you ask for.
    "PROMPT", "Describe what you see in one short sentence."
)
FONT_SCALE = float(os.environ.get("FONT_SCALE", "1.1"))  # overlay text size

client = OpenAI(base_url=BASE_URL, api_key=API_KEY)

# Shared state between the UI (main) thread and the model-query worker thread.
_lock = threading.Lock()
_recent = deque(maxlen=64)  # rolling buffer of recent BGR frames
_answer = "(starting…)"
_status = "idle"
_busy = False
_latency = 0.0   # seconds the last model call took
_rate = 0.0      # achieved queries/sec, smoothed
_dropped = 0     # ticks skipped because the model was still busy


def pick_frames(n):
    """Snapshot the n most recent frames as image parts, newest last.

    For the live case (n=1) this MUST be the newest frame: the buffer holds a
    couple of seconds of history, so indexing from the front would send a stale
    still and the overlay would describe what the camera saw seconds ago.
    """
    with _lock:
        frames = list(_recent)
    if not frames:
        return []
    if n <= 1:
        chosen = [frames[-1]]
    elif len(frames) <= n:
        chosen = frames
    else:
        # Evenly spaced across the buffer, always ENDING on the newest frame.
        step = (len(frames) - 1) / (n - 1)
        chosen = [frames[round(i * step)] for i in range(n)]
    parts = []
    for frame in chosen:
        small = cv2.resize(frame, (IMG_WIDTH, int(IMG_WIDTH * frame.shape[0] / frame.shape[1])))
        ok, buf = cv2.imencode(".jpg", small)
        if ok:
            b64 = base64.b64encode(buf.tobytes()).decode()
            parts.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}})
    return parts


def query_model():
    """Runs on a worker thread: build the message, call the model, store the reply."""
    global _answer, _status, _busy, _latency, _rate
    started = time.monotonic()
    try:
        content = [{"type": "text", "text": PROMPT}]
        content += pick_frames(NUM_FRAMES)
        with _lock:
            _status = "thinking…"
        r = client.chat.completions.create(
            model=MODEL,
            max_tokens=MAX_TOKENS,
            messages=[{"role": "user", "content": content}],
        )
        reply = (r.choices[0].message.content or "(empty)").strip()
    except Exception as e:  # noqa: BLE001
        reply = f"error: {e}"
    elapsed = time.monotonic() - started
    with _lock:
        _answer = reply
        _status = "idle"
        _busy = False
        _latency = elapsed
        # Exponential smoothing — a raw per-query rate jitters too much to read.
        inst = 1.0 / elapsed if elapsed > 0 else 0.0
        _rate = inst if _rate == 0.0 else (0.7 * _rate + 0.3 * inst)


def maybe_query(auto=False):
    """Start a query unless one is already in flight.

    Dropping the tick (rather than queueing) is what keeps the overlay tied to
    the live feed: a queue would grow without bound whenever the model is slower
    than INTERVAL, and every answer would describe an older and older frame.
    """
    global _busy, _dropped
    with _lock:
        if _busy:
            if auto:
                _dropped += 1
            return
        _busy = True
    threading.Thread(target=query_model, daemon=True).start()


def wrap(text, width):
    words, lines, line = text.split(), [], ""
    for w in words:
        if len(line) + len(w) + 1 > width:
            lines.append(line)
            line = w
        else:
            line = (line + " " + w).strip()
    if line:
        lines.append(line)
    return lines[-6:]  # keep the overlay to the last few lines


FONT = cv2.FONT_HERSHEY_SIMPLEX


def overlay(frame):
    h, w = frame.shape[:2]
    with _lock:
        answer, status, busy = _answer, _status, _busy
        latency, rate, dropped = _latency, _rate, _dropped
    fs = FONT_SCALE
    thick = max(1, round(2 * fs))
    char_w = max(1, int(19 * fs))  # approx glyph width for wrapping
    line_h = int(34 * fs)
    lines = wrap(answer, max(12, w // char_w))
    box_h = int(14 * fs) + line_h * (len(lines) + 1)  # +1 for the status line
    panel = frame.copy()
    cv2.rectangle(panel, (0, h - box_h), (w, h), (0, 0, 0), -1)
    frame = cv2.addWeighted(panel, 0.55, frame, 0.45, 0)
    y = h - box_h + line_h
    tag = f"[{status}]" + ("  ●" if busy else "")
    if latency:
        tag += f"   {latency * 1000:.0f}ms/query   {rate:.1f}/s   target {1 / INTERVAL:.1f}/s" if INTERVAL > 0 else f"   {latency * 1000:.0f}ms/query"
        if dropped:
            tag += f"   dropped {dropped}"
    cv2.putText(frame, tag, (12, y), FONT, fs * 0.9,
                (0, 215, 255) if busy else (0, 255, 128), thick, cv2.LINE_AA)
    for i, ln in enumerate(lines):
        cv2.putText(frame, ln, (12, y + line_h * (i + 1)), FONT, fs,
                    (255, 255, 255), thick, cv2.LINE_AA)
    cv2.putText(frame, "SPACE=ask  q=quit", (12, int(30 * fs)), FONT, fs * 0.7,
                (200, 200, 200), max(1, thick - 1), cv2.LINE_AA)
    return frame


ids = [m.id for m in client.models.list().data]
MODEL = os.environ.get("MODEL") or ("llm" if "llm" in ids else (ids[0] if ids else "llm"))
print(
    f"base_url = {BASE_URL}\nmodel    = {MODEL}\n"
    f"camera   = {CAMERA}  interval={INTERVAL}s ({1 / INTERVAL:.1f} queries/s target)"
    if INTERVAL > 0
    else f"base_url = {BASE_URL}\nmodel    = {MODEL}\ncamera   = {CAMERA}  manual (SPACE to ask)"
)
print(f"frames   = {NUM_FRAMES} @ {IMG_WIDTH}px   max_tokens={MAX_TOKENS}")


PREFLIGHT_CODE = "4827"


def preflight():
    """Prove the model can actually SEE before opening the camera.

    A text-only model does NOT reject image parts — the server drops them
    silently and the model answers from the text alone ("I'm not able to view
    the video stream myself"). That reads like a broken demo when it is really
    the wrong server, so probe with an image the answer depends on: render a
    number and ask the model to read it back.

    Set SKIP_PREFLIGHT=1 to bypass (e.g. a VLM that is genuinely bad at OCR).
    """
    if os.environ.get("SKIP_PREFLIGHT"):
        return
    img = np.zeros((160, 420, 3), dtype=np.uint8)
    cv2.putText(img, PREFLIGHT_CODE, (20, 120), FONT, 4.0, (255, 255, 255), 12, cv2.LINE_AA)
    ok, buf = cv2.imencode(".jpg", img)
    if not ok:
        return
    b64 = base64.b64encode(buf.tobytes()).decode()
    hint = (
        f"Model {MODEL!r} on {BASE_URL} cannot see images.\n\n"
        "Text-only models do not error on image parts — the frames are dropped\n"
        "silently and you get answers like \"I can't view the video stream\".\n\n"
        "Start a VISION model in another shell:\n"
        "    task run:vision            (examples/llama/qwen3-vl-2b.yaml)\n\n"
        "A vision model is a normal llama.cpp model plus `mmproj_path`.\n"
        "If yours IS a VLM that just misread the probe: SKIP_PREFLIGHT=1 task vision"
    )
    try:
        r = client.chat.completions.create(
            model=MODEL,
            max_tokens=24,
            messages=[{"role": "user", "content": [
                {"type": "text", "text": "What number is written in this image? Reply with digits only."},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
            ]}],
        )
        reply = (r.choices[0].message.content or "").strip()
    except Exception as e:  # noqa: BLE001
        raise SystemExit(f"Preflight request failed:\n  {e}\n\n{hint}") from None
    if PREFLIGHT_CODE not in reply:
        raise SystemExit(
            f"Preflight: asked the model to read {PREFLIGHT_CODE} from an image, got {reply!r}.\n\n"
            + hint
        )


preflight()
print("preflight ok — the model read the test image")

cap = cv2.VideoCapture(CAMERA)
if not cap.isOpened():
    raise SystemExit(f"Cannot open camera index {CAMERA} (check permissions / device)")

last_auto = time.monotonic()
try:
    while True:
        ok, frame = cap.read()
        if not ok:
            continue
        with _lock:
            _recent.append(frame.copy())

        cv2.imshow("vision live — SPACE=ask  q=quit", overlay(frame))
        key = cv2.waitKey(1) & 0xFF
        if key in (ord("q"), 27):  # q or ESC
            break
        if key == ord(" "):
            maybe_query()

        now = time.monotonic()
        if INTERVAL > 0 and now - last_auto >= INTERVAL:
            last_auto = now
            maybe_query(auto=True)
finally:
    cap.release()
    cv2.destroyAllWindows()
    with _lock:
        if _dropped:
            print(f"\n{_dropped} tick(s) dropped — the model could not keep up with {INTERVAL}s.")
            print("Raise INTERVAL, or lower MAX_TOKENS / IMG_WIDTH, to close the gap.")

print("\nDONE")
