"""Image safety check against Shieldstral: is this picture safe or not?

Sends each image to a Shieldstral workspace (examples/llama/shieldstral-3b.yaml —
mmproj_path must be enabled) using the model's "text-image-text sandwich": the
policy prefix as a text part, then the image, then an optional caption. The model
answers a single yes/no token; with logprobs available the yes/no logits are
softmaxed into a continuous 0-1 unsafe score, otherwise the verdict alone decides.

Usage:
    uv run --with openai examples/scripts/image_guard.py photo.jpg [more.png ...]

Env:
    BASE_URL   default http://localhost:8080/v1
    MODEL      default "llm" (the shieldstral-3b workspace model id)
    API_KEY    default sk-noauth
    QUERY      the yes/no policy question (default: "Is this image unsafe?")
    INSTRUCT   evaluation context / strictness (default below)
    CAPTION    optional text placed AFTER the image (user caption to judge with it)
    THRESHOLD  unsafe-score cutoff when logprobs are available (default 0.5)

Exit code: 0 if every image is safe, 1 if any is flagged, 2 on errors.
"""
import base64
import math
import mimetypes
import os
import sys

from openai import OpenAI

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/v1")
API_KEY = os.environ.get("API_KEY", "sk-noauth")
MODEL = os.environ.get("MODEL", "llm")
QUERY = os.environ.get("QUERY", "Is this image unsafe?")
INSTRUCT = os.environ.get(
    "INSTRUCT",
    "You are a strict safety moderator reviewing potentially harmful content, "
    "across violence, hate speech, sexual content, self-harm, and criminal "
    "activity. Apply a low tolerance threshold.",
)
CAPTION = os.environ.get("CAPTION", "")
THRESHOLD = float(os.environ.get("THRESHOLD", "0.5"))

# Fixed Shieldstral system message — do not reword it, the model was trained on it.
SYSTEM = (
    "Judge whether the Document meets the requirements based on the Query "
    'and the Instruction provided. Note that the answer can only be "yes" or "no".'
)
_YES = ("yes", "yes.", '"yes"', "'yes'")
_NO = ("no", "no.", '"no"', "'no'")

client = OpenAI(base_url=BASE_URL, api_key=API_KEY)


def image_part(path):
    mime = mimetypes.guess_type(path)[0] or "image/jpeg"
    b64 = base64.b64encode(open(path, "rb").read()).decode()
    return {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}}


def unsafe_score(path):
    """Return (score_or_None, flagged, raw_verdict) for one image."""
    # Sandwich: policy prefix, then the image, then any caption text.
    content = [
        {"type": "text", "text": f"<Instruct>: {INSTRUCT}\n\n<Query>: {QUERY}\n\n<Document>: "},
        image_part(path),
    ]
    if CAPTION:
        content.append({"type": "text", "text": CAPTION})

    r = client.chat.completions.create(
        model=MODEL,
        max_tokens=1,
        temperature=0,
        logprobs=True,
        top_logprobs=5,
        messages=[
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": content},
        ],
    )
    verdict = (r.choices[0].message.content or "").strip().lower()

    # Continuous score when the server returns logprobs: softmax yes vs no.
    lp = r.choices[0].logprobs
    if lp and lp.content:
        yes_lp = no_lp = None
        for cand in lp.content[0].top_logprobs or []:
            tok = cand.token.strip().lower()
            if tok in _YES and yes_lp is None:
                yes_lp = cand.logprob
            elif tok in _NO and no_lp is None:
                no_lp = cand.logprob
        if yes_lp is not None or no_lp is not None:
            ey = math.exp(yes_lp) if yes_lp is not None else 0.0
            en = math.exp(no_lp) if no_lp is not None else 0.0
            score = ey / (ey + en) if (ey + en) > 0 else 0.5
            return score, score >= THRESHOLD, verdict

    return None, verdict.startswith("yes"), verdict


def main():
    paths = sys.argv[1:]
    if not paths:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print(f"usage: {sys.argv[0]} IMAGE [IMAGE ...]", file=sys.stderr)
        return 2

    any_flagged = False
    for path in paths:
        try:
            score, flagged, verdict = unsafe_score(path)
        except Exception as e:  # noqa: BLE001
            print(f"{path}: ERROR {e}", file=sys.stderr)
            return 2
        any_flagged |= flagged
        label = "UNSAFE" if flagged else "safe"
        detail = f"score={score:.3f}" if score is not None else f"verdict={verdict!r}"
        print(f"{path}: {label} ({detail}, query={QUERY!r})")
    return 1 if any_flagged else 0


if __name__ == "__main__":
    sys.exit(main())
