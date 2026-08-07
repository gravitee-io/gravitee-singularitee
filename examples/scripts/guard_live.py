"""LIVE camera safety guard: stream the webcam into Shieldstral, overlay SAFE/UNSAFE.

vision_live.py's loop pointed at a guard model instead of a chat VLM: every INTERVAL
seconds the NEWEST frame is fed as a photo through Shieldstral's text-image-text
sandwich (policy prefix, then the frame), and the yes/no verdict is overlaid on the
video as a green SAFE / red UNSAFE banner. With server-side logprobs the yes/no
logits are softmaxed into a continuous 0-1 unsafe score shown next to the verdict;
without them the single-token verdict decides alone.

Point this at examples/llama/shieldstral-3b.yaml (mmproj_path must be enabled).
Same single-flight rule as vision_live.py: one request in flight, late ticks are
DROPPED rather than queued so the verdict always describes the live feed.

Keys:  SPACE = check now    q / ESC = quit

Run with uv (https://docs.astral.sh/uv/):
    uv run --with openai --with opencv-python examples/scripts/guard_live.py

Env:
    BASE_URL   default http://localhost:8080/v1
    MODEL      default "llm" (the shieldstral-3b workspace model id)
    API_KEY    default sk-noauth
    CAMERA     camera device index                 (default 0)
    INTERVAL   auto-check every N seconds; 0 = manual (default 0.6)
    IMG_WIDTH  longest edge sent to the model      (default 448; lower = faster)
    QUERY      the yes/no policy question (default: "Is this image unsafe?")
    INSTRUCT   evaluation context / strictness (default below)
    THRESHOLD  unsafe-score cutoff when logprobs are available (default 0.5)
    FONT_SCALE overlay text size                   (default 1.1)
"""
import base64
import math
import os
import threading
import time
from collections import deque

import cv2  # opencv-python
from openai import OpenAI

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/v1")
API_KEY = os.environ.get("API_KEY", "sk-noauth")
CAMERA = int(os.environ.get("CAMERA", "0"))
INTERVAL = float(os.environ.get("INTERVAL", "0.6"))
IMG_WIDTH = int(os.environ.get("IMG_WIDTH", "448"))
QUERY = os.environ.get("QUERY", "Is this image unsafe?")
INSTRUCT = os.environ.get(
    "INSTRUCT",
    "You are a strict safety moderator reviewing potentially harmful content, "
    "across violence, hate speech, sexual content, self-harm, and criminal "
    "activity. Apply a low tolerance threshold.",
)
THRESHOLD = float(os.environ.get("THRESHOLD", "0.5"))
FONT_SCALE = float(os.environ.get("FONT_SCALE", "1.1"))

# Fixed Shieldstral system message — do not reword it, the model was trained on it.
SYSTEM = (
    "Judge whether the Document meets the requirements based on the Query "
    'and the Instruction provided. Note that the answer can only be "yes" or "no".'
)
_YES = ("yes", "yes.", '"yes"', "'yes'")
_NO = ("no", "no.", '"no"', "'no'")

client = OpenAI(base_url=BASE_URL, api_key=API_KEY)

# Shared state between the UI (main) thread and the guard-query worker thread.
_lock = threading.Lock()
_recent = deque(maxlen=8)  # rolling buffer; only the newest frame is ever sent
_flagged = False
_score = None  # 0-1 unsafe score when logprobs are available, else None
_status = "starting…"
_busy = False
_latency = 0.0
_dropped = 0


def newest_frame_part():
    """The single newest frame as an image content part (live guard = newest still)."""
    with _lock:
        frame = _recent[-1].copy() if _recent else None
    if frame is None:
        return None
    small = cv2.resize(frame, (IMG_WIDTH, int(IMG_WIDTH * frame.shape[0] / frame.shape[1])))
    ok, buf = cv2.imencode(".jpg", small)
    if not ok:
        return None
    b64 = base64.b64encode(buf.tobytes()).decode()
    return {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}


def check_frame():
    """Runs on a worker thread: Shieldstral sandwich on the newest frame."""
    global _flagged, _score, _status, _busy, _latency
    started = time.monotonic()
    flagged, score, status = _flagged, _score, "idle"
    try:
        part = newest_frame_part()
        if part is None:
            status = "no frame"
        else:
            r = client.chat.completions.create(
                model=MODEL,
                max_tokens=1,
                temperature=0,
                logprobs=True,
                top_logprobs=5,
                messages=[
                    {"role": "system", "content": SYSTEM},
                    {"role": "user", "content": [
                        {"type": "text",
                         "text": f"<Instruct>: {INSTRUCT}\n\n<Query>: {QUERY}\n\n<Document>: "},
                        part,
                    ]},
                ],
            )
            verdict = (r.choices[0].message.content or "").strip().lower()
            score = softmax_unsafe(r.choices[0].logprobs)
            flagged = score >= THRESHOLD if score is not None else verdict.startswith("yes")
    except Exception as e:  # noqa: BLE001
        status = f"error: {e}"
    elapsed = time.monotonic() - started
    with _lock:
        _flagged, _score, _status, _busy, _latency = flagged, score, status, False, elapsed


def softmax_unsafe(lp):
    """0-1 unsafe score from yes/no logprobs; None when the server sends no logprobs."""
    if not lp or not lp.content:
        return None
    yes_lp = no_lp = None
    for cand in lp.content[0].top_logprobs or []:
        tok = cand.token.strip().lower()
        if tok in _YES and yes_lp is None:
            yes_lp = cand.logprob
        elif tok in _NO and no_lp is None:
            no_lp = cand.logprob
    if yes_lp is None and no_lp is None:
        return None
    ey = math.exp(yes_lp) if yes_lp is not None else 0.0
    en = math.exp(no_lp) if no_lp is not None else 0.0
    return ey / (ey + en) if (ey + en) > 0 else 0.5


def maybe_check(auto=False):
    """Single-flight: drop the tick (never queue) when a check is still running."""
    global _busy, _dropped
    with _lock:
        if _busy:
            if auto:
                _dropped += 1
            return
        _busy = True
    threading.Thread(target=check_frame, daemon=True).start()


FONT = cv2.FONT_HERSHEY_SIMPLEX


def overlay(frame):
    h, w = frame.shape[:2]
    with _lock:
        flagged, score, status, busy = _flagged, _score, _status, _busy
        latency, dropped = _latency, _dropped
    fs = FONT_SCALE
    thick = max(1, round(2 * fs))
    line_h = int(38 * fs)
    box_h = int(14 * fs) + line_h * 2
    color = (0, 0, 255) if flagged else (0, 200, 0)
    label = "UNSAFE" if flagged else "SAFE"
    if score is not None:
        label += f"  {score:.2f}"
    panel = frame.copy()
    cv2.rectangle(panel, (0, h - box_h), (w, h), (0, 0, 0), -1)
    frame = cv2.addWeighted(panel, 0.55, frame, 0.45, 0)
    # Thin full-frame border in the verdict colour — visible even at a distance.
    cv2.rectangle(frame, (0, 0), (w - 1, h - 1), color, max(2, thick * 2))
    y = h - box_h + line_h
    cv2.putText(frame, label, (12, y), FONT, fs * 1.3, color, thick + 1, cv2.LINE_AA)
    tag = f"[{status}]" + ("  ●" if busy else "")
    if latency:
        tag += f"   {latency * 1000:.0f}ms/check"
        if dropped:
            tag += f"   dropped {dropped}"
    cv2.putText(frame, tag, (12, y + line_h), FONT, fs * 0.8,
                (0, 215, 255) if busy else (200, 200, 200), thick, cv2.LINE_AA)
    cv2.putText(frame, "SPACE=check  q=quit", (12, int(30 * fs)), FONT, fs * 0.7,
                (200, 200, 200), max(1, thick - 1), cv2.LINE_AA)
    return frame


ids = [m.id for m in client.models.list().data]
MODEL = os.environ.get("MODEL") or ("llm" if "llm" in ids else (ids[0] if ids else "llm"))
print(f"base_url = {BASE_URL}\nmodel    = {MODEL}\nquery    = {QUERY!r}")
print(
    f"camera   = {CAMERA}  interval={INTERVAL}s" if INTERVAL > 0
    else f"camera   = {CAMERA}  manual (SPACE to check)"
)


def preflight():
    """Prove the guard answers yes/no on an image before opening the camera.

    A text-only server drops image parts silently, and a chat model would reply
    with prose — either way the verdict parsing would quietly misbehave. Probe
    with a plain black image: any single-token yes/no answer means the sandwich
    round-trips. Set SKIP_PREFLIGHT=1 to bypass.
    """
    if os.environ.get("SKIP_PREFLIGHT"):
        return
    import numpy as np
    ok, buf = cv2.imencode(".jpg", np.zeros((160, 160, 3), dtype=np.uint8))
    b64 = base64.b64encode(buf.tobytes()).decode()
    try:
        r = client.chat.completions.create(
            model=MODEL,
            max_tokens=1,
            temperature=0,
            messages=[
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": [
                    {"type": "text",
                     "text": f"<Instruct>: {INSTRUCT}\n\n<Query>: {QUERY}\n\n<Document>: "},
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                ]},
            ],
        )
        reply = (r.choices[0].message.content or "").strip().lower()
    except Exception as e:  # noqa: BLE001
        raise SystemExit(f"Preflight request failed:\n  {e}") from None
    if not (reply.startswith("yes") or reply.startswith("no")):
        raise SystemExit(
            f"Preflight: expected a yes/no verdict, got {reply!r}.\n"
            f"Is {MODEL!r} on {BASE_URL} really Shieldstral with mmproj enabled?\n"
            "(examples/llama/shieldstral-3b.yaml — uncomment mmproj_path)\n"
            "SKIP_PREFLIGHT=1 to bypass."
        )


preflight()
print("preflight ok — the guard returned a yes/no verdict on the test image")

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

        cv2.imshow("guard live — SPACE=check  q=quit", overlay(frame))
        key = cv2.waitKey(1) & 0xFF
        if key in (ord("q"), 27):  # q or ESC
            break
        if key == ord(" "):
            maybe_check()

        now = time.monotonic()
        if INTERVAL > 0 and now - last_auto >= INTERVAL:
            last_auto = now
            maybe_check(auto=True)
finally:
    cap.release()
    cv2.destroyAllWindows()
    with _lock:
        if _dropped:
            print(f"\n{_dropped} tick(s) dropped — the guard could not keep up with {INTERVAL}s.")
            print("Raise INTERVAL or lower IMG_WIDTH to close the gap.")

print("\nDONE")
