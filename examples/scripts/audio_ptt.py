"""Push-to-talk audio demo: hit a key, record from the mic, ask the model.

Press ENTER to start recording, ENTER again to stop; the clip is sent as one
input_audio message to the model (e.g. Voxtral) and the reply is streamed back.
Speak a question and it answers; or set PROMPT to ask something about the audio.

Point this at examples/llama/voxtral-3b.yaml (Voxtral).

Requires: openai, sounddevice. macOS prompts for Microphone permission the first time.

Setup:
    python3 -m venv .venv && .venv/bin/pip install openai sounddevice

Env:
    BASE_URL    default http://localhost:8080/v1   (native HTTP API port)
    MODEL       default: "llm" (or first model from /v1/models)
    API_KEY     default sk-noauth
    PROMPT      text instruction sent with the audio
                (default: "Answer the question asked in the audio.")
    TEMPERATURE default 0.2 — small audio models ramble at the engine default

Run with uv (https://docs.astral.sh/uv/):
    uv run --with openai --with sounddevice examples/scripts/audio_ptt.py
"""
import base64
import io
import os
import shutil
import subprocess
import tempfile
import sys
import traceback
import wave

import sounddevice as sd
from openai import OpenAI

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/v1")
API_KEY = os.environ.get("API_KEY", "sk-noauth")
PROMPT = os.environ.get("PROMPT", "Answer the question asked in the audio.")
# Audio understanding is a transcription-shaped task and wants a low temperature.
# The engine default (0.7) noticeably degrades answer quality on small quantized
# audio models; 0.2 is markedly more reliable on the same clip and prompt.
TEMPERATURE = float(os.environ.get("TEMPERATURE", "0.2"))
AUDIO_RATE = 16000

client = OpenAI(base_url=BASE_URL, api_key=API_KEY)


def record_until_enter():
    """Record 16 kHz mono audio until the user presses ENTER; return WAV bytes."""
    frames = []
    stream = sd.RawInputStream(
        samplerate=AUDIO_RATE, channels=1, dtype="int16",
        callback=lambda indata, n, t, status: frames.append(bytes(indata)),
    )
    with stream:
        input("  ● recording… press ENTER to stop ")
    bio = io.BytesIO()
    with wave.open(bio, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(AUDIO_RATE)
        w.writeframes(b"".join(frames))
    return bio.getvalue()


def ask(wav_bytes):
    audio_b64 = base64.b64encode(wav_bytes).decode()
    content = [
        {"type": "text", "text": PROMPT},
        {"type": "input_audio", "input_audio": {"data": audio_b64, "format": "wav"}},
    ]
    print("  → ", end="", flush=True)
    for chunk in client.chat.completions.create(
        model=MODEL,
        stream=True,
        temperature=TEMPERATURE,
        messages=[{"role": "user", "content": content}],
    ):
        if chunk.choices and chunk.choices[0].delta.content:
            print(chunk.choices[0].delta.content, end="", flush=True)
    print()


ids = [m.id for m in client.models.list().data]
MODEL = os.environ.get("MODEL") or ("llm" if "llm" in ids else (ids[0] if ids else "llm"))
print(f"base_url = {BASE_URL}\nmodel    = {MODEL}\nprompt   = {PROMPT!r}")


# A sentence transcribes far more reliably than isolated digits: the digit probe
# failed on a model that was hearing perfectly well.
PREFLIGHT_WORDS = "the quick brown fox jumps over the lazy dog"
PREFLIGHT_KEYWORD = "fox"


def synth_wav():
    """Best-effort speech synthesis using whatever the OS ships. None if neither exists."""
    tmp = os.path.join(tempfile.gettempdir(), "singularitee-preflight.wav")
    if shutil.which("say"):  # macOS
        cmd = ["say", "-o", tmp, "--data-format=LEI16@16000", PREFLIGHT_WORDS]
    elif shutil.which("espeak"):  # common on Linux
        cmd = ["espeak", "-w", tmp, PREFLIGHT_WORDS]
    else:
        return None
    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=30)
        with open(tmp, "rb") as f:
            return f.read()
    except (subprocess.SubprocessError, OSError):
        return None
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass


def preflight():
    """Prove the model can actually HEAR before opening the mic.

    A text-only model does NOT reject audio parts — the server drops them
    silently and the model answers from the text alone, so you only discover the
    problem after granting mic permission and recording. Probe with synthesized
    speech whose content the answer depends on.

    Speech synthesis is best-effort (`say` / `espeak`); with neither available we
    can only warn. Set SKIP_PREFLIGHT=1 to bypass entirely.
    """
    if os.environ.get("SKIP_PREFLIGHT"):
        return
    hint = (
        f"Model {MODEL!r} on {BASE_URL} cannot hear audio.\n\n"
        "Text-only models do not error on audio parts — the clip is dropped\n"
        "silently and you get an answer written from the text prompt alone.\n\n"
        "Start an AUDIO model in another shell:\n"
        "    task run:audio             (examples/llama/voxtral-3b.yaml)\n\n"
        "An audio model is a normal llama.cpp model plus `mmproj_path`.\n"
        "If yours IS an ALM that just misheard the probe: SKIP_PREFLIGHT=1 task audio"
    )
    wav = synth_wav()
    if wav is None:
        print("  ! no `say`/`espeak` — skipping the hearing check.")
        print("  ! if replies ignore what you said, you are on a text-only server:")
        print("  !     task run:audio")
        return
    try:
        r = client.chat.completions.create(
            model=MODEL,
            max_tokens=40,
            temperature=TEMPERATURE,
            messages=[{"role": "user", "content": [
                {"type": "text", "text": "Transcribe the audio verbatim."},
                {"type": "input_audio",
                 "input_audio": {"data": base64.b64encode(wav).decode(), "format": "wav"}},
            ]}],
        )
        reply = (r.choices[0].message.content or "").strip()
    except Exception as e:  # noqa: BLE001
        raise SystemExit(f"Preflight request failed:\n  {e}\n\n{hint}") from None
    if PREFLIGHT_KEYWORD not in reply.lower():
        # ADVISORY, not fatal. Unlike the vision probe (OCR of a rendered number,
        # which a VLM does reliably), this depends on a small ALM transcribing a
        # robotic `say`/`espeak` voice — Voxtral routinely hears the clip and
        # still answers something other than the digits. Failing hard here blocks
        # a working setup, so warn and carry on; a genuinely deaf model shows up
        # immediately anyway, as replies that ignore what you said.
        print(f"  ! preflight: spoke {PREFLIGHT_WORDS!r}, model replied {reply!r}")
        print("  ! that may just be a small model mis-transcribing synthetic speech.")
        print("  ! if replies keep ignoring what you say, you are on a text-only server:")
        print("  !     task run:audio")
        return
    print("preflight ok — the model transcribed the test clip")


preflight()

while True:
    try:
        cmd = input("\nPress ENTER to record (q = quit): ")
    except (EOFError, KeyboardInterrupt):
        break
    if cmd.strip().lower() == "q":
        break
    try:
        wav = record_until_enter()
        secs = len(wav) / (AUDIO_RATE * 2)
        print(f"  captured {secs:.1f}s")
        if secs < 0.3:
            print("  (too short, skipped)")
            continue
        ask(wav)
    except Exception:  # noqa: BLE001
        traceback.print_exc()

print("\nDONE")
sys.exit(0)
