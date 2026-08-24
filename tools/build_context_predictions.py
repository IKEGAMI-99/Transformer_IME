#!/usr/bin/env python3
"""Build an on-device context -> next phrase database from Japanese Tatoeba sentences.

This is deliberately retrieval-oriented rather than a second generative model.  It gives the
Transformer a much richer pool of continuations that actually occurred in Japanese sentences;
the JP5M model still performs the final context-aware reranking on device.
"""

from __future__ import annotations

import argparse
import bz2
import hashlib
import json
import math
import random
import sqlite3
import tempfile
import urllib.request
from collections import Counter
from pathlib import Path

TATOEBA_URL = "https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2"
SEED = 606


def download(url: str, target: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "Transformer-IME-context-builder/0.6"})
    with urllib.request.urlopen(req, timeout=180) as response, target.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)


def reservoir_sentences(path: Path, maximum: int) -> list[str]:
    rng = random.Random(SEED)
    sample: list[str] = []
    seen = 0
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as source:
        for line in source:
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) != 3:
                continue
            text = " ".join(parts[2].split())
            if len(text) < 3 or len(text) > 140:
                continue
            seen += 1
            if len(sample) < maximum:
                sample.append(text)
            else:
                slot = rng.randrange(seen)
                if slot < maximum:
                    sample[slot] = text
    return sample


def usable(text: str) -> bool:
    return bool(text) and len(text) <= 28 and not text.isspace()


def build_counts(sentences: list[str]) -> Counter[tuple[str, str]]:
    # fugashi + unidic-lite are installed by CI only for this build step.
    import fugashi

    tagger = fugashi.Tagger()
    counts: Counter[tuple[str, str]] = Counter()

    for index, sentence in enumerate(sentences, start=1):
        tokens = [str(word) for word in tagger(sentence) if str(word).strip()]
        if len(tokens) < 2:
            continue
        # Previous 1..3 tokens form the raw suffix context.  Next 1 or 2 tokens become a
        # tappable continuation.  Runtime can match these without shipping a tokenizer.
        for i in range(1, len(tokens)):
            for context_tokens in range(1, min(3, i) + 1):
                context = "".join(tokens[i - context_tokens : i])
                if not usable(context) or len(context) > 24:
                    continue
                for next_tokens in range(1, min(2, len(tokens) - i) + 1):
                    continuation = "".join(tokens[i : i + next_tokens])
                    if usable(continuation) and len(continuation) <= 20:
                        counts[(context, continuation)] += 1
        if index % 10_000 == 0:
            print(f"tokenized {index}/{len(sentences)} sentences; {len(counts):,} unique pairs", flush=True)
    return counts


def write_database(
    output: Path,
    counts: Counter[tuple[str, str]],
    max_candidates: int,
    min_frequency: int,
    sentence_count: int,
) -> dict:
    if output.exists():
        output.unlink()
    output.parent.mkdir(parents=True, exist_ok=True)

    # Sorting once lets us keep only the strongest continuations for each exact context.
    ranked = sorted(
        ((ctx, cont, freq) for (ctx, cont), freq in counts.items() if freq >= min_frequency),
        key=lambda row: (row[0], -row[2], len(row[1]), row[1]),
    )

    conn = sqlite3.connect(output)
    conn.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA page_size=4096;
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE predictions(
            context TEXT NOT NULL,
            continuation TEXT NOT NULL,
            freq INTEGER NOT NULL,
            PRIMARY KEY(context, continuation)
        ) WITHOUT ROWID;
        """
    )

    batch = []
    context_count = 0
    entry_count = 0
    previous = None
    kept = 0
    for context, continuation, freq in ranked:
        if context != previous:
            context_count += 1
            previous = context
            kept = 0
        if kept >= max_candidates:
            continue
        batch.append((context, continuation, freq))
        kept += 1
        entry_count += 1
        if len(batch) >= 20_000:
            conn.executemany("INSERT INTO predictions(context,continuation,freq) VALUES(?,?,?)", batch)
            batch.clear()
    if batch:
        conn.executemany("INSERT INTO predictions(context,continuation,freq) VALUES(?,?,?)", batch)

    metadata = {
        "format_version": "1",
        "source": "Tatoeba Japanese sentence export",
        "source_url": TATOEBA_URL,
        "sampled_sentences": str(sentence_count),
        "unique_contexts": str(context_count),
        "prediction_entries": str(entry_count),
        "max_candidates_per_context": str(max_candidates),
        "min_frequency": str(min_frequency),
    }
    conn.executemany("INSERT INTO metadata(key,value) VALUES(?,?)", metadata.items())
    conn.execute("PRAGMA user_version=1")
    conn.execute("ANALYZE")
    conn.commit()
    conn.execute("VACUUM")
    conn.close()

    return {
        **metadata,
        "file_size": output.stat().st_size,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "unique_pair_count_before_pruning": len(counts),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--max-sentences", type=int, default=60_000)
    parser.add_argument("--max-candidates", type=int, default=10)
    parser.add_argument("--min-frequency", type=int, default=2)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="context-pred-") as temp_dir:
        corpus = Path(temp_dir) / "jpn_sentences.tsv.bz2"
        print("Downloading", TATOEBA_URL, flush=True)
        download(TATOEBA_URL, corpus)
        sentences = reservoir_sentences(corpus, args.max_sentences)

    print(f"building phrase counts from {len(sentences):,} sentences", flush=True)
    counts = build_counts(sentences)
    info = write_database(args.output, counts, args.max_candidates, args.min_frequency, len(sentences))

    if args.metadata:
        args.metadata.parent.mkdir(parents=True, exist_ok=True)
        args.metadata.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(info, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
