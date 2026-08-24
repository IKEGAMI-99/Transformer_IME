#!/usr/bin/env python3
"""Train the v0.7 ~21M Japanese sparse reranker using the proven MMJQ exporter."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import train_medium_moe_japanese as base

# Wider representation, larger hash vocabulary and longer context.  Top-1 routing keeps active
# compute far below the total parameter count.
base.VOCAB = 4096
base.CONTEXT = 48
base.DIM = 192
base.HEADS = 6
base.LAYERS = 4
base.FF_DIM = 384
base.EXPERTS = 32


def metadata_path(argv: list[str]) -> Path | None:
    if "--metadata" not in argv:
        return None
    index = argv.index("--metadata")
    if index + 1 >= len(argv):
        return None
    return Path(argv[index + 1])


def main() -> int:
    result = base.main()
    path = metadata_path(sys.argv)
    if path and path.exists():
        info = json.loads(path.read_text(encoding="utf-8"))
        info["version"] = "0.7.0"
        info["model_name"] = "Japanese Large MoE 21M"
        info["purpose"] = "IME kana-kanji and post-commit context reranking"
        path.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
