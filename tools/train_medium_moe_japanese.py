#!/usr/bin/env python3
"""Train and export the Transformer IME Japanese Medium MoE model.

Corpus: Tatoeba Japanese sentence export (CC BY 2.0 FR).
The export is downloaded at build time; raw corpus text is never bundled in the APK.
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import json
import os
import random
import struct
import urllib.request
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

CORPUS_URL = "https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2"
CORPUS_LICENSE = "CC BY 2.0 FR"
CORPUS_ATTRIBUTION = "Tatoeba Project contributors"

VOCAB = 1024
CONTEXT = 24
DIM = 128
HEADS = 4
LAYERS = 4
FF_DIM = 272
EXPERTS = 16
BOS = 1


def char_bucket(ch: str) -> int:
    code = ord(ch)
    # Match Kotlin Int (32-bit signed) overflow semantics.
    mixed = ((code * 0x045D9F3B) & 0xFFFFFFFF) ^ (code >> 7) ^ 0x5F356495
    mixed &= 0xFFFFFFFF
    signed = mixed if mixed < 0x80000000 else mixed - 0x100000000
    return (signed & 0x7FFFFFFF) % VOCAB


def download_corpus(target: Path) -> Path:
    if target.exists() and target.stat().st_size > 1_000_000:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading Japanese corpus: {CORPUS_URL}", flush=True)
    req = urllib.request.Request(CORPUS_URL, headers={"User-Agent": "Transformer-IME-training/0.3"})
    with urllib.request.urlopen(req, timeout=90) as response, target.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    return target


def load_sentences(path: Path, max_sentences: int, seed: int) -> list[str]:
    # Reservoir sampling avoids only learning the first slice of the weekly export.
    rng = random.Random(seed)
    reservoir: list[str] = []
    seen = 0
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) != 3 or parts[1] != "jpn":
                continue
            text = parts[2].strip()
            if len(text) < 4 or len(text) > 120:
                continue
            if not any(("ぁ" <= c <= "ヿ") or ("一" <= c <= "龯") for c in text):
                continue
            seen += 1
            if len(reservoir) < max_sentences:
                reservoir.append(text)
            else:
                j = rng.randrange(seen)
                if j < max_sentences:
                    reservoir[j] = text
    rng.shuffle(reservoir)
    print(f"Loaded {len(reservoir):,} Japanese sentences (sampled from {seen:,})", flush=True)
    return reservoir


def make_token_stream(sentences: list[str]) -> torch.Tensor:
    ids: list[int] = []
    for sentence in sentences:
        ids.append(BOS)
        ids.extend(char_bucket(ch) for ch in sentence if ch not in "\r\n\t")
    if len(ids) < CONTEXT + 2:
        raise RuntimeError("Corpus is too small")
    return torch.tensor(ids, dtype=torch.long)


class Expert(nn.Module):
    def __init__(self):
        super().__init__()
        self.w1 = nn.Linear(DIM, FF_DIM)
        self.w2 = nn.Linear(FF_DIM, DIM)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.w2(F.gelu(self.w1(x), approximate="tanh"))


class Block(nn.Module):
    def __init__(self):
        super().__init__()
        self.ln1 = nn.LayerNorm(DIM, eps=1e-5)
        self.q = nn.Linear(DIM, DIM)
        self.k = nn.Linear(DIM, DIM)
        self.v = nn.Linear(DIM, DIM)
        self.o = nn.Linear(DIM, DIM)
        self.ln2 = nn.LayerNorm(DIM, eps=1e-5)
        self.router = nn.Linear(DIM, EXPERTS)
        self.experts = nn.ModuleList([Expert() for _ in range(EXPERTS)])

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        b, t, _ = x.shape
        z = self.ln1(x)
        q = self.q(z).view(b, t, HEADS, DIM // HEADS).transpose(1, 2)
        k = self.k(z).view(b, t, HEADS, DIM // HEADS).transpose(1, 2)
        v = self.v(z).view(b, t, HEADS, DIM // HEADS).transpose(1, 2)
        attn = F.scaled_dot_product_attention(q, k, v, is_causal=True)
        attn = attn.transpose(1, 2).contiguous().view(b, t, DIM)
        x = x + self.o(attn)

        z2 = self.ln2(x)
        router_logits = self.router(z2)
        router_prob = F.softmax(router_logits, dim=-1)
        top_prob, top_idx = router_prob.max(dim=-1)
        flat = z2.reshape(-1, DIM)
        flat_idx = top_idx.reshape(-1)
        flat_prob = top_prob.reshape(-1)
        moe = torch.zeros_like(flat)
        for expert_idx, expert in enumerate(self.experts):
            mask = flat_idx == expert_idx
            if mask.any():
                # Forward multiplier is exactly 1.0, matching the Kotlin top-1 runtime.
                # The straight-through term still gives the router a gradient signal.
                gate = 1.0 + flat_prob[mask] - flat_prob[mask].detach()
                moe[mask] = expert(flat[mask]) * gate.unsqueeze(-1)
        x = x + moe.view(b, t, DIM)

        # Encourage the sparse router to use the available experts.
        importance = router_prob.mean(dim=(0, 1))
        load = F.one_hot(top_idx, EXPERTS).float().mean(dim=(0, 1))
        aux = EXPERTS * torch.sum(importance * load)
        return x, aux


class JapaneseMoE(nn.Module):
    def __init__(self):
        super().__init__()
        self.token = nn.Embedding(VOCAB, DIM)
        self.pos = nn.Embedding(CONTEXT, DIM)
        self.blocks = nn.ModuleList([Block() for _ in range(LAYERS)])
        self.head = nn.Linear(DIM, VOCAB, bias=True)

    def forward(self, ids: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        t = ids.shape[1]
        positions = torch.arange(t, device=ids.device)
        x = self.token(ids) + self.pos(positions)[None, :, :]
        aux_total = x.new_zeros(())
        for block in self.blocks:
            x, aux = block(x)
            aux_total = aux_total + aux
        return self.head(x), aux_total / len(self.blocks)


def parameter_count(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())


def sample_batch(stream: torch.Tensor, batch_size: int, rng: random.Random) -> tuple[torch.Tensor, torch.Tensor]:
    max_start = stream.numel() - CONTEXT - 1
    starts = [rng.randrange(max_start) for _ in range(batch_size)]
    batch = torch.stack([stream[s:s + CONTEXT + 1] for s in starts])
    return batch[:, :-1], batch[:, 1:]


def quantized_record(tensor: torch.Tensor) -> bytes:
    a = tensor.detach().cpu().float().contiguous().view(-1)
    max_abs = float(a.abs().max().item()) if a.numel() else 0.0
    scale = max(max_abs / 127.0, 1e-8)
    q = torch.clamp(torch.round(a / scale), -127, 127).to(torch.int8)
    return struct.pack("<if", q.numel(), scale) + q.numpy().tobytes()


def export_model(model: JapaneseMoE, output: Path) -> str:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as f:
        f.write(b"MMJQ")
        f.write(struct.pack("<8i", 1, VOCAB, CONTEXT, DIM, HEADS, LAYERS, FF_DIM, EXPERTS))
        f.write(quantized_record(model.token.weight))
        f.write(quantized_record(model.pos.weight))
        for block in model.blocks:
            f.write(quantized_record(block.ln1.weight))
            f.write(quantized_record(block.ln1.bias))
            for linear in (block.q, block.k, block.v, block.o):
                f.write(quantized_record(linear.weight))
                f.write(quantized_record(linear.bias))
            f.write(quantized_record(block.ln2.weight))
            f.write(quantized_record(block.ln2.bias))
            f.write(quantized_record(block.router.weight))
            f.write(quantized_record(block.router.bias))
            for expert in block.experts:
                f.write(quantized_record(expert.w1.weight))
                f.write(quantized_record(expert.w1.bias))
                f.write(quantized_record(expert.w2.weight))
                f.write(quantized_record(expert.w2.bias))
        f.write(quantized_record(model.head.weight))
        f.write(quantized_record(model.head.bias))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"Exported {output} ({output.stat().st_size / 1_000_000:.2f} MB), sha256={digest}", flush=True)
    return digest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--corpus-cache", default=".cache/tatoeba/jpn_sentences.tsv.bz2")
    parser.add_argument("--steps", type=int, default=1800)
    parser.add_argument("--max-sentences", type=int, default=80_000)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--seed", type=int, default=3407)
    parser.add_argument("--lr", type=float, default=5e-4)
    args = parser.parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.set_num_threads(max(1, min(8, os.cpu_count() or 2)))

    corpus_path = download_corpus(Path(args.corpus_cache))
    sentences = load_sentences(corpus_path, args.max_sentences, args.seed)
    stream = make_token_stream(sentences)
    rng = random.Random(args.seed ^ 0xA53A)

    model = JapaneseMoE()
    count = parameter_count(model)
    if count < 5_000_000:
        raise RuntimeError(f"Model must be >=5M parameters, got {count:,}")
    print(f"Training {count:,}-parameter Japanese MoE on {stream.numel():,} character tokens", flush=True)

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    model.train()
    losses: list[float] = []
    for step in range(1, args.steps + 1):
        x, y = sample_batch(stream, args.batch_size, rng)
        logits, aux = model(x)
        lm_loss = F.cross_entropy(logits.reshape(-1, VOCAB), y.reshape(-1))
        loss = lm_loss + 0.01 * aux
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        value = float(lm_loss.detach().item())
        losses.append(value)
        if step == 1 or step % 50 == 0 or step == args.steps:
            recent = sum(losses[-50:]) / len(losses[-50:])
            print(f"step {step:4d}/{args.steps} lm_loss={value:.4f} avg50={recent:.4f} router_aux={float(aux.detach()):.3f}", flush=True)

    model.eval()
    out = Path(args.output)
    digest = export_model(model, out)
    meta = {
        "format": "MMJQ-v1",
        "version": "0.3.0",
        "parameter_count": count,
        "architecture": {
            "layers": LAYERS,
            "dim": DIM,
            "heads": HEADS,
            "experts": EXPERTS,
            "ff_dim": FF_DIM,
            "context": CONTEXT,
            "vocab_hash_buckets": VOCAB,
            "routing": "top-1 MoE",
            "quantization": "symmetric int8 per tensor"
        },
        "training": {
            "corpus": "Tatoeba Japanese sentence weekly export",
            "corpus_url": CORPUS_URL,
            "license": CORPUS_LICENSE,
            "attribution": CORPUS_ATTRIBUTION,
            "sampled_sentences": len(sentences),
            "character_tokens": int(stream.numel()),
            "steps": args.steps,
            "batch_size": args.batch_size,
            "seed": args.seed,
            "final_lm_loss": losses[-1],
            "avg_last_50_lm_loss": sum(losses[-50:]) / min(50, len(losses))
        },
        "sha256": digest,
        "file_size": out.stat().st_size
    }
    metadata = Path(args.metadata)
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(meta, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
