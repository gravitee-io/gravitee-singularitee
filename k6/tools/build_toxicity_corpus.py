#
# Copyright © 2015 The Gravitee team (http://gravitee.io)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Build a 256-token paragraph corpus for the GliGuard k6 quality/perf test.

Packs sentences from gravitee-io/textdetox-multilingual-toxicity-dataset into
monolingual paragraphs of ~TARGET_TOKENS *model* tokens (counted with
GliGuard's own tokenizer.json, no special tokens), split 50/50 into:

  - clean: only non-toxic sentences
  - toxic: 1-3 toxic sentences shuffled into non-toxic filler

Ground truth per paragraph ("does it contain toxic content") is therefore
known, which is exactly what the guardrail is supposed to detect. Each clean
sentence is used at most once across the whole corpus.

Usage:
  uv run build_toxicity_corpus.py [--out ../data/guardrails-corpus.json]
      [--split test] [--per-lang 40] [--target-tokens 256] [--seed 42]
"""

import argparse
import json
import random
from collections import defaultdict, deque
from pathlib import Path

from datasets import load_dataset
from huggingface_hub import hf_hub_download
from tokenizers import Tokenizer

DATASET = "gravitee-io/textdetox-multilingual-toxicity-dataset"
TOKENIZER_REPO = "gravitee-io/gliner4j-gliguard-LLMGuardrails-300M"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).parent.parent / "data" / "guardrails-corpus.json"))
    ap.add_argument("--split", default="test", choices=["train", "test"])
    ap.add_argument("--per-lang", type=int, default=40, help="paragraphs per language per class")
    ap.add_argument("--target-tokens", type=int, default=256)
    ap.add_argument("--tolerance", type=float, default=0.125, help="accepted +/- fraction around target")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    tok = Tokenizer.from_file(hf_hub_download(TOKENIZER_REPO, "tokenizer.json"))

    def ntok(text: str) -> int:
        return len(tok.encode(text, add_special_tokens=False).ids)

    ds = load_dataset(DATASET, split=args.split)
    pools: dict[str, dict[str, list[tuple[str, int]]]] = defaultdict(lambda: {"clean": [], "toxic": []})
    for row in ds:
        text = " ".join(str(row["text"]).split())
        if not text:
            continue
        n = ntok(text)
        if n == 0 or n > args.target_tokens:  # a single over-target sentence can't be packed
            continue
        pools[row["language"]]["clean" if int(row["toxic"]) == 0 else "toxic"].append((text, n))

    lo = int(args.target_tokens * (1 - args.tolerance))
    hi = int(args.target_tokens * (1 + args.tolerance))

    items = []
    for lang in sorted(pools):
        clean_list = pools[lang]["clean"]
        toxic_list = pools[lang]["toxic"]
        rng.shuffle(clean_list)
        rng.shuffle(toxic_list)
        clean = deque(clean_list)
        toxic = deque(toxic_list)

        def build(n_toxic: int) -> dict | None:
            # take up to n_toxic seeds, stopping before the seeds alone overflow the window
            chosen, total = [], 0
            while toxic and len(chosen) < n_toxic and total + toxic[0][1] <= hi:
                s = toxic.popleft()
                chosen.append(s)
                total += s[1]
            if n_toxic and not chosen:
                if toxic:
                    toxic.popleft()  # front seed alone can never fit — discard it
                return None
            seeds = set(chosen)
            skipped = []
            while clean and total < args.target_tokens:
                s = clean.popleft()
                if total + s[1] > hi:
                    skipped.append(s)  # too big for the remaining window, keep for a later paragraph
                    continue
                chosen.append(s)
                total += s[1]
            clean.extend(skipped)
            if total < lo:
                return None  # pool exhausted for this language
            rng.shuffle(chosen)
            # joining retokenizes differently than the per-sentence sum (merges across
            # boundaries, especially for unspaced scripts) — trim clean sentences until
            # the real paragraph token count fits the window
            text = " ".join(t for t, _ in chosen)
            real = ntok(text)
            while real > hi:
                idx = next((i for i in range(len(chosen) - 1, -1, -1) if chosen[i] not in seeds), None)
                if idx is None:
                    return None
                clean.append(chosen.pop(idx))
                text = " ".join(t for t, _ in chosen)
                real = ntok(text)
            if real < lo:
                return None
            return {
                "text": text,
                "tokens": real,
                "lang": lang,
                "expected": "toxic" if seeds else "clean",
                "toxic_sentences": len(seeds),
            }

        # toxic first: it needs both pools, clean-only paragraphs just eat the remainder
        for cls in ("toxic", "clean"):
            built, failures = 0, 0
            while built < args.per_lang and failures < 20:
                item = build(rng.randint(1, 3) if cls == "toxic" else 0)
                if item is None:
                    failures += 1
                    continue
                items.append(item)
                built += 1

    rng.shuffle(items)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    meta = {
        "dataset": DATASET,
        "split": args.split,
        "tokenizer": TOKENIZER_REPO,
        "target_tokens": args.target_tokens,
        "seed": args.seed,
        "items": len(items),
        "by_lang": {
            lang: {
                "clean": sum(1 for i in items if i["lang"] == lang and i["expected"] == "clean"),
                "toxic": sum(1 for i in items if i["lang"] == lang and i["expected"] == "toxic"),
            }
            for lang in sorted({i["lang"] for i in items})
        },
        "token_stats": {
            "min": min(i["tokens"] for i in items),
            "mean": round(sum(i["tokens"] for i in items) / len(items), 1),
            "max": max(i["tokens"] for i in items),
        },
    }
    out.write_text(json.dumps({"meta": meta, "items": items}, ensure_ascii=False))
    print(json.dumps(meta, indent=2, ensure_ascii=False))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
