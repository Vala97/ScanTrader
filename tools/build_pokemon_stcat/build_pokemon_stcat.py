#!/usr/bin/env python3
"""
Build pokemon.stcat: TCGdex EN card ids + Milo 128-d embeddings.

Matches Android EmbeddingCatalog.save / MiloEmbedder.preprocess:
  magic STCAT10\\0, big-endian ints/floats, ImageNet-normalised 448 RGB CHW.

Usage:
  pip install -r requirements.txt
  python build_pokemon_stcat.py --out ../../pokemon.stcat
  python build_pokemon_stcat.py --sets base1,sv10 --out smoke.stcat   # smoke subset

Then import the .stcat in ScanTrader Settings (Pokémon neural pack), or:
  adb push pokemon.stcat /sdcard/Download/
"""

from __future__ import annotations

import argparse
import io
import struct
import sys
import time
from pathlib import Path

import numpy as np
import requests
from PIL import Image

INPUT_SIZE = 448
EMBED_DIM = 128
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32).reshape(3, 1, 1)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32).reshape(3, 1, 1)
MILO_URL = "https://huggingface.co/HanClinto/milo/resolve/main/model.onnx"
TCGDEX = "https://api.tcgdex.net/v2/en"
MAGIC = b"STCAT10\x00"


def log(msg: str) -> None:
    print(msg, flush=True)


def download(url: str, dest: Path, session: requests.Session) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        return
    log(f"  download {url}")
    with session.get(url, stream=True, timeout=120) as r:
        r.raise_for_status()
        tmp = dest.with_suffix(dest.suffix + ".part")
        with open(tmp, "wb") as f:
            for chunk in r.iter_content(64 * 1024):
                if chunk:
                    f.write(chunk)
        tmp.replace(dest)


def list_cards(session: requests.Session, sets_filter: set[str] | None) -> list[dict]:
    log("Fetching TCGdex EN card list…")
    r = session.get(f"{TCGDEX}/cards", timeout=120)
    r.raise_for_status()
    cards = r.json()
    out = []
    for c in cards:
        cid = c.get("id") or ""
        if not cid or not c.get("image"):
            continue
        set_id = cid.split("-", 1)[0]
        if sets_filter and set_id not in sets_filter:
            continue
        out.append(c)
    log(f"  {len(out)} cards with images" + (f" (sets={sorted(sets_filter)})" if sets_filter else ""))
    return out


def preprocess(img: Image.Image) -> np.ndarray:
    rgb = img.convert("RGB").resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
    arr = np.asarray(rgb, dtype=np.float32) / 255.0  # HWC
    chw = np.transpose(arr, (2, 0, 1))  # CHW
    chw = (chw - MEAN) / STD
    return chw[np.newaxis, ...]  # 1x3x448x448


def l2_normalize(v: np.ndarray) -> np.ndarray:
    n = float(np.linalg.norm(v))
    if n <= 0:
        return v.astype(np.float32)
    return (v / n).astype(np.float32)


def write_stcat(path: Path, ids: list[str], embeddings: np.ndarray) -> None:
    assert embeddings.dtype == np.float32
    assert embeddings.ndim == 2 and embeddings.shape[1] == EMBED_DIM
    assert len(ids) == embeddings.shape[0]
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">i", 1))  # version
        f.write(struct.pack(">i", len(ids)))
        f.write(struct.pack(">i", EMBED_DIM))
        # big-endian floats (Java DataOutputStream)
        for v in embeddings.reshape(-1):
            f.write(struct.pack(">f", float(v)))
        for card_id in ids:
            raw = card_id.encode("utf-8")
            if len(raw) > 65535:
                raise ValueError(f"id too long: {card_id}")
            f.write(struct.pack(">H", len(raw)))
            f.write(raw)
    log(f"Wrote {path} ({len(ids)} cards, {path.stat().st_size / 1e6:.1f} MB)")


def main() -> int:
    ap = argparse.ArgumentParser(description="Build pokemon.stcat from TCGdex EN + Milo")
    ap.add_argument("--out", type=Path, default=Path("pokemon.stcat"))
    ap.add_argument("--cache", type=Path, default=Path("cache"))
    ap.add_argument("--model", type=Path, default=None, help="Path to milo.onnx (default: cache/milo.onnx)")
    ap.add_argument(
        "--sets",
        type=str,
        default="",
        help="Comma-separated TCGdex set ids (e.g. base1,sv10). Empty = all EN.",
    )
    ap.add_argument("--limit", type=int, default=0, help="Max cards (0 = all)")
    ap.add_argument("--sleep", type=float, default=0.05, help="Delay between image downloads")
    args = ap.parse_args()

    try:
        import onnxruntime as ort
    except ImportError:
        log("Install deps: pip install -r requirements.txt")
        return 1

    sets_filter = {s.strip() for s in args.sets.split(",") if s.strip()} or None
    cache: Path = args.cache
    model_path = args.model or (cache / "milo.onnx")
    img_dir = cache / "images"
    img_dir.mkdir(parents=True, exist_ok=True)

    session = requests.Session()
    session.headers["User-Agent"] = "ScanTrader-build_pokemon_stcat/1.0"

    download(MILO_URL, model_path, session)
    cards = list_cards(session, sets_filter)
    if args.limit > 0:
        cards = cards[: args.limit]

    log(f"Loading ONNX {model_path}…")
    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name

    ids: list[str] = []
    vectors: list[np.ndarray] = []
    t0 = time.time()
    for i, card in enumerate(cards):
        cid = card["id"]
        image_base = card["image"].rstrip("/")
        # Prefer high.webp, fall back to low.webp
        local = img_dir / f"{cid.replace('/', '_')}.webp"
        if not local.exists():
            ok = False
            for suffix in ("high.webp", "low.webp"):
                url = f"{image_base}/{suffix}"
                try:
                    download(url, local, session)
                    ok = True
                    break
                except Exception as e:
                    log(f"  warn {cid} {suffix}: {e}")
            if not ok:
                continue
            time.sleep(args.sleep)
        try:
            img = Image.open(local)
            batch = preprocess(img)
            out = sess.run(None, {input_name: batch})[0]
            vec = np.asarray(out).reshape(-1)
            if vec.shape[0] != EMBED_DIM:
                # Some exports wrap batch dim
                vec = vec.reshape(-1)[:EMBED_DIM]
            if vec.shape[0] != EMBED_DIM:
                log(f"  skip {cid}: dim {vec.shape}")
                continue
            ids.append(cid)
            vectors.append(l2_normalize(vec))
        except Exception as e:
            log(f"  skip {cid}: {e}")
            continue
        if (i + 1) % 50 == 0 or i + 1 == len(cards):
            elapsed = time.time() - t0
            log(f"  embedded {len(ids)}/{i + 1} ({elapsed:.0f}s)")

    if not ids:
        log("No embeddings produced.")
        return 1

    emb = np.stack(vectors, axis=0).astype(np.float32)
    write_stcat(args.out, ids, emb)
    return 0


if __name__ == "__main__":
    sys.exit(main())
