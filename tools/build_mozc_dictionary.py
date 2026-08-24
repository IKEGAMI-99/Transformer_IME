#!/usr/bin/env python3
"""Build a compact on-device kana->surface SQLite dictionary from Mozc OSS data.

v0.6 adds a *small* 2/3-kana prefix index for predictive conversion.  Longer typed
readings filter those indexed full readings at runtime.  Mozc manual place/name/word
entries receive a lower predictive cost so useful named entities survive the compact index.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import tempfile
import urllib.request
from collections import defaultdict
from pathlib import Path

MOZC_REV = "master"
RAW_ROOT = f"https://raw.githubusercontent.com/google/mozc/{MOZC_REV}/src/data"
BASE_FILES = [f"dictionary_oss/dictionary{i:02d}.txt" for i in range(10)]
MANUAL_FILES = ["dictionary_manual/places.tsv", "dictionary_manual/words.tsv"]
PREFIX_KEEP = 16


def download(url: str, path: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "Transformer-IME-dictionary-builder/0.6"})
    with urllib.request.urlopen(request, timeout=120) as response, path.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)


def normalize_reading(text: str) -> str:
    chars = []
    for ch in text.strip():
        code = ord(ch)
        chars.append(chr(code - 0x60) if 0x30A1 <= code <= 0x30F6 else ch)
    return "".join(chars)


def valid_entry(reading: str, surface: str) -> bool:
    if not reading or not surface or len(reading) > 48 or len(surface) > 80:
        return False
    return not any(ord(ch) < 0x20 for ch in reading + surface)


def add_entry(entries: dict[str, dict[str, int]], reading: str, surface: str, cost: int) -> None:
    reading = normalize_reading(reading)
    surface = surface.strip()
    if not valid_entry(reading, surface):
        return
    old = entries[reading].get(surface)
    if old is None or cost < old:
        entries[reading][surface] = cost


def parse_base(path: Path, entries: dict[str, dict[str, int]]) -> int:
    count = 0
    with path.open("r", encoding="utf-8", errors="replace") as source:
        for line in source:
            if not line or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            reading, cost_text, surface = parts[0], parts[3], parts[4]
            try:
                cost = int(cost_text)
            except ValueError:
                continue
            add_entry(entries, reading, surface, cost)
            count += 1
    return count


def parse_manual(path: Path, entries: dict[str, dict[str, int]], preferred_cost: int = 1200) -> int:
    count = 0
    with path.open("r", encoding="utf-8", errors="replace") as source:
        for line in source:
            if not line or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            add_entry(entries, parts[0], parts[1], preferred_cost)
            count += 1
    return count


def keep_prefix_candidate(bucket: dict[str, tuple[int, str]], surface: str, score: int, reading: str) -> None:
    old = bucket.get(surface)
    if old is None or score < old[0]:
        bucket[surface] = (score, reading)
    if len(bucket) > PREFIX_KEEP * 3:
        trimmed = sorted(bucket.items(), key=lambda item: (item[1][0], len(item[1][1]), item[0]))[: PREFIX_KEEP * 2]
        bucket.clear()
        bucket.update(trimmed)


def build_database(output: Path, entries: dict[str, dict[str, int]], max_candidates: int) -> dict:
    if output.exists():
        output.unlink()
    output.parent.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(output)
    conn.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA page_size=4096;
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE entries(
            reading TEXT NOT NULL,
            surface TEXT NOT NULL,
            cost INTEGER NOT NULL,
            PRIMARY KEY(reading, cost, surface)
        ) WITHOUT ROWID;
        CREATE TABLE predictions(
            prefix TEXT NOT NULL,
            reading TEXT NOT NULL,
            surface TEXT NOT NULL,
            cost INTEGER NOT NULL,
            PRIMARY KEY(prefix, cost, surface, reading)
        ) WITHOUT ROWID;
        """
    )

    written = 0
    batch = []
    prefix_map: dict[str, dict[str, tuple[int, str]]] = defaultdict(dict)

    for reading in sorted(entries):
        ranked = sorted(entries[reading].items(), key=lambda item: (item[1], len(item[0]), item[0]))
        for surface, cost in ranked[:max_candidates]:
            batch.append((reading, surface, cost))
            written += 1
            if len(batch) >= 20_000:
                conn.executemany("INSERT INTO entries(reading,surface,cost) VALUES(?,?,?)", batch)
                batch.clear()

        # Keep the predictive index compact: only strong dictionary readings, only their best
        # surface, and only the 2/3-kana prefixes.  A longer live reading filters by full reading.
        if len(reading) >= 3 and ranked:
            surface, cost = ranked[0]
            if cost <= 5500:
                for prefix_len in (2, 3):
                    if prefix_len >= len(reading):
                        continue
                    prefix = reading[:prefix_len]
                    extension = len(reading) - prefix_len
                    predictive_cost = cost + extension * 110
                    keep_prefix_candidate(prefix_map[prefix], surface, predictive_cost, reading)

    if batch:
        conn.executemany("INSERT INTO entries(reading,surface,cost) VALUES(?,?,?)", batch)

    predictive_written = 0
    prediction_batch = []
    for prefix in sorted(prefix_map):
        ranked = sorted(
            prefix_map[prefix].items(),
            key=lambda item: (item[1][0], len(item[1][1]), len(item[0]), item[0]),
        )[:PREFIX_KEEP]
        for surface, (cost, reading) in ranked:
            prediction_batch.append((prefix, reading, surface, cost))
            predictive_written += 1
            if len(prediction_batch) >= 20_000:
                conn.executemany(
                    "INSERT INTO predictions(prefix,reading,surface,cost) VALUES(?,?,?,?)", prediction_batch
                )
                prediction_batch.clear()
    if prediction_batch:
        conn.executemany("INSERT INTO predictions(prefix,reading,surface,cost) VALUES(?,?,?,?)", prediction_batch)

    metadata = {
        "format_version": "2",
        "source": "Mozc OSS dictionary + Mozc dictionary_manual",
        "source_revision": MOZC_REV,
        "max_candidates_per_reading": str(max_candidates),
        "unique_readings": str(len(entries)),
        "entry_count": str(written),
        "prediction_prefix_count": str(len(prefix_map)),
        "prediction_entry_count": str(predictive_written),
    }
    conn.executemany("INSERT INTO metadata(key,value) VALUES(?,?)", metadata.items())
    conn.execute("PRAGMA user_version=2")
    conn.execute("ANALYZE")
    conn.commit()
    conn.execute("VACUUM")
    conn.close()

    return {
        **metadata,
        "file_size": output.stat().st_size,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--max-candidates", type=int, default=12)
    args = parser.parse_args()

    entries: dict[str, dict[str, int]] = defaultdict(dict)
    parsed = 0
    with tempfile.TemporaryDirectory(prefix="mozc-dict-") as temp_dir:
        temp = Path(temp_dir)
        for relative in BASE_FILES:
            local = temp / Path(relative).name
            url = f"{RAW_ROOT}/{relative}"
            print("Downloading", url, flush=True)
            download(url, local)
            parsed += parse_base(local, entries)
        for relative in MANUAL_FILES:
            local = temp / Path(relative).name
            url = f"{RAW_ROOT}/{relative}"
            print("Downloading", url, flush=True)
            download(url, local)
            parsed += parse_manual(local, entries)

    info = build_database(args.output, entries, args.max_candidates)
    info["parsed_source_rows"] = parsed
    if args.metadata:
        args.metadata.parent.mkdir(parents=True, exist_ok=True)
        args.metadata.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(info, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
