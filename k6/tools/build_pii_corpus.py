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

"""Build a 256-token paragraph corpus for the PII k6 quality/perf test.

Same approach as build_toxicity_corpus.py, but from
gravitee-io/pii-detection-dataset: rows (text + character-offset PII spans)
are packed into monolingual paragraphs of ~TARGET_TOKENS *model* tokens
(counted with the Pii-Multi GLiNER2 tokenizer, no special tokens), split
50/50 into:

  - clean: only span-free rows
  - pii:   1-3 span-bearing rows shuffled into span-free filler

Ground-truth spans are offset-shifted into paragraph coordinates, so the k6
script can score span-level recall/precision by character overlap.

Usage:
  uv run build_pii_corpus.py [--out ../data/pii-corpus.json] [--split train]
      [--per-lang 40] [--target-tokens 256] [--seed 42] [--max-rows 200000]
"""

import argparse
import json
import random
from collections import defaultdict, deque
from pathlib import Path

from datasets import load_dataset
from huggingface_hub import hf_hub_download
from tokenizers import Tokenizer

DATASET = "gravitee-io/pii-detection-dataset"
TOKENIZER_REPO = "gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).parent.parent / "data" / "pii-corpus.json"))
    ap.add_argument("--split", default="train")
    ap.add_argument("--per-lang", type=int, default=40, help="paragraphs per language per class")
    ap.add_argument("--target-tokens", type=int, default=256)
    ap.add_argument("--tolerance", type=float, default=0.125, help="accepted +/- fraction around target")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--max-rows", type=int, default=200_000, help="cap on dataset rows scanned")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    tok = Tokenizer.from_file(hf_hub_download(TOKENIZER_REPO, "tokenizer.json"))

    def ntok(text: str) -> int:
        return len(tok.encode(text, add_special_tokens=False).ids)

    ds = load_dataset(DATASET, split=args.split, streaming=True)
    # row = (text, n_tokens, spans) with spans in row-local character offsets
    pools: dict[str, dict[str, list]] = defaultdict(lambda: {"clean": [], "pii": []})
    for i, row in enumerate(ds):
        if i >= args.max_rows:
            break
        text = str(row["text"]).strip()
        if not text or "\n" in text and len(text) > 2000:
            continue
        n = ntok(text)
        if n == 0 or n > args.target_tokens:
            continue
        spans = [
            {"start": int(s["start"]), "end": int(s["end"]), "label": s["label"]}
            for s in (row["spans"] or [])
            if 0 <= int(s["start"]) < int(s["end"]) <= len(text)
        ]
        pools[row["language"]]["pii" if spans else "clean"].append((text, n, spans))

    lo = int(args.target_tokens * (1 - args.tolerance))
    hi = int(args.target_tokens * (1 + args.tolerance))

    items = []
    for lang in sorted(pools):
        clean_list = pools[lang]["clean"]
        pii_list = pools[lang]["pii"]
        if len(clean_list) < 10 or len(pii_list) < 10:
            continue  # not enough material for this language
        rng.shuffle(clean_list)
        rng.shuffle(pii_list)
        clean = deque(clean_list)
        pii = deque(pii_list)

        def finalize(chosen: list) -> tuple[str, list]:
            """Join rows with a space and shift each row's spans into paragraph offsets."""
            parts, spans, offset = [], [], 0
            for text, _, row_spans in chosen:
                parts.append(text)
                for s in row_spans:
                    spans.append({"start": s["start"] + offset, "end": s["end"] + offset, "label": s["label"]})
                offset += len(text) + 1  # + " " separator
            return " ".join(parts), spans

        def build(n_pii: int) -> dict | None:
            chosen, total = [], 0
            while pii and len(chosen) < n_pii and total + pii[0][1] <= hi:
                r = pii.popleft()
                chosen.append(r)
                total += r[1]
            if n_pii and not chosen:
                if pii:
                    pii.popleft()  # front row alone can never fit — discard it
                return None
            seeds = set(id(r) for r in chosen)
            skipped = []
            while clean and total < args.target_tokens:
                r = clean.popleft()
                if total + r[1] > hi:
                    skipped.append(r)  # too big for the remaining window, keep for later
                    continue
                chosen.append(r)
                total += r[1]
            clean.extend(skipped)
            if total < lo:
                return None
            rng.shuffle(chosen)
            # joining retokenizes differently than the per-row sum — trim clean rows
            # until the real paragraph token count fits the window
            text, spans = finalize(chosen)
            real = ntok(text)
            while real > hi:
                idx = next((i for i in range(len(chosen) - 1, -1, -1) if id(chosen[i]) not in seeds), None)
                if idx is None:
                    return None
                clean.append(chosen.pop(idx))
                text, spans = finalize(chosen)
                real = ntok(text)
            if real < lo:
                return None
            return {
                "text": text,
                "tokens": real,
                "lang": lang,
                "expected": "pii" if seeds else "clean",
                "spans": spans,
            }

        # pii first: it needs both pools, clean-only paragraphs just eat the remainder
        for cls in ("pii", "clean"):
            built, failures = 0, 0
            while built < args.per_lang and failures < 20:
                item = build(rng.randint(1, 3) if cls == "pii" else 0)
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
        "total_spans": sum(len(i["spans"]) for i in items),
        "by_lang": {
            lang: {
                "clean": sum(1 for i in items if i["lang"] == lang and i["expected"] == "clean"),
                "pii": sum(1 for i in items if i["lang"] == lang and i["expected"] == "pii"),
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
