#!/usr/bin/env python3
"""
Append new English Pokémon cards to the published pokemon-en.stcat.

Reads the live stcat index, keeps every vector already in the file, and
embeds only TCGdex card ids that are missing. The new file is a full STCAT10
rewrite with the same ids in the same order plus the new ones. Milo weights
are checked against the app's MODEL_SHA256 before any image is embedded.

Usage:
  python update_pokemon_catalog.py --dry-run --newest 2
  python update_pokemon_catalog.py --sets sv10 --dry-run
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import struct
import subprocess
import sys
import time
from datetime import date
from pathlib import Path

import numpy as np
import requests
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
PUBLISH = ROOT / "tools" / "publish_stcat" / "publish_stcat.py"
INDEX_URL = "https://github.com/Vala97/ScanTrader/releases/download/stcat/stcat-index.json"
TCGDEX = "https://api.tcgdex.net/v2/en"
MILO_URL = "https://huggingface.co/HanClinto/milo/resolve/main/model.onnx"
MODEL_SHA256 = "bd13d8d60383c69da04dce261f32e93fdaeaa8fd618fbc991e7385f71b3d45df"
CATALOG_ID = "pokemon-en"
INPUT_SIZE = 448
EMBED_DIM = 128
MAGIC = b"STCAT10\x00"
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32).reshape(3, 1, 1)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32).reshape(3, 1, 1)


def log(msg: str) -> None:
    print(msg, flush=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def next_version(current: str | None, today: str | None = None) -> str:
    """A version the app's dotted compare treats as strictly newer."""
    base = today or date.today().isoformat().replace("-", ".")
    current = (current or "").strip()
    if not current or base > current:
        return base
    if current == base:
        return f"{base}.1"
    prefix = base + "."
    if current.startswith(prefix):
        tail = current[len(prefix):]
        try:
            return f"{base}.{int(tail) + 1}"
        except ValueError:
            pass
    return f"{base}.1"


def catalog_entry(index: dict, catalog_id: str) -> dict:
    for entry in index.get("catalogs") or []:
        if entry.get("id") == catalog_id:
            return entry
    raise SystemExit(f"index has no {catalog_id} catalog")


def filter_new_cards(cards: list[dict], known: set[str], sets_filter: set[str] | None) -> list[dict]:
    out = []
    for card in cards:
        cid = card.get("id") or ""
        if not cid or not card.get("image") or cid in known:
            continue
        set_id = cid.split("-", 1)[0]
        if sets_filter and set_id not in sets_filter:
            continue
        out.append(card)
    return out


def sets_by_newest(sets: list[dict], count: int) -> set[str]:
    dated = [s for s in sets if s.get("id") and s.get("releaseDate")]
    dated.sort(key=lambda s: s["releaseDate"], reverse=True)
    return {s["id"] for s in dated[:count]}


def read_stcat(path: Path) -> tuple[list[str], np.ndarray]:
    data = path.read_bytes()
    if not data.startswith(MAGIC):
        raise SystemExit(f"{path} is not an STCAT10 file")
    offset = len(MAGIC)
    version, count, dim = struct.unpack_from(">iii", data, offset)
    offset += 12
    if version != 1 or dim != EMBED_DIM or count < 0:
        raise SystemExit(f"unexpected stcat header version={version} count={count} dim={dim}")
    floats = count * dim
    need = offset + floats * 4
    if len(data) < need:
        raise SystemExit("stcat file truncated in the embedding block")
    flat = np.frombuffer(data[offset:need], dtype=">f4").astype(np.float32)
    offset = need
    ids: list[str] = []
    for _ in range(count):
        if offset + 2 > len(data):
            raise SystemExit("stcat file truncated in the id block")
        (n,) = struct.unpack_from(">H", data, offset)
        offset += 2
        ids.append(data[offset:offset + n].decode("utf-8"))
        offset += n
    if len(ids) != count or len(set(ids)) != count:
        raise SystemExit("stcat ids are missing or duplicated")
    return ids, flat.reshape(count, dim)


def write_stcat(path: Path, ids: list[str], embeddings: np.ndarray) -> None:
    if embeddings.dtype != np.float32 or embeddings.shape != (len(ids), EMBED_DIM):
        raise SystemExit(f"bad embedding block {embeddings.shape}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">i", 1))
        f.write(struct.pack(">i", len(ids)))
        f.write(struct.pack(">i", EMBED_DIM))
        for value in embeddings.reshape(-1):
            f.write(struct.pack(">f", float(value)))
        for card_id in ids:
            raw = card_id.encode("utf-8")
            if len(raw) > 65535:
                raise SystemExit(f"id too long: {card_id}")
            f.write(struct.pack(">H", len(raw)))
            f.write(raw)


def get_json(session: requests.Session, url: str) -> dict | list:
    delay = 2.0
    for attempt in range(6):
        response = session.get(url, timeout=120)
        if response.status_code == 429 or response.status_code >= 500:
            log(f"  {response.status_code} on {url}, retry in {delay:.0f}s")
            time.sleep(delay)
            delay = min(delay * 2, 60)
            continue
        response.raise_for_status()
        return response.json()
    raise SystemExit(f"TCGdex failed: {url}")


def get_bytes(session: requests.Session, url: str) -> bytes:
    delay = 2.0
    for attempt in range(6):
        response = session.get(url, timeout=120)
        if response.status_code == 404:
            raise FileNotFoundError(url)
        if response.status_code == 429 or response.status_code >= 500:
            time.sleep(delay)
            delay = min(delay * 2, 60)
            continue
        response.raise_for_status()
        return response.content
    raise SystemExit(f"image failed: {url}")


def download(session: requests.Session, url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        return
    log(f"  download {url}")
    part = dest.with_suffix(dest.suffix + ".part")
    with session.get(url, stream=True, timeout=300) as response:
        response.raise_for_status()
        with open(part, "wb") as f:
            for chunk in response.iter_content(1024 * 1024):
                if chunk:
                    f.write(chunk)
    part.replace(dest)


def preprocess(img: Image.Image) -> np.ndarray:
    rgb = img.convert("RGB").resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
    arr = np.asarray(rgb, dtype=np.float32) / 255.0
    chw = np.transpose(arr, (2, 0, 1))
    return ((chw - MEAN) / STD)[np.newaxis, ...]


def l2_normalize(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm <= 0:
        return vector.astype(np.float32)
    return (vector / norm).astype(np.float32)


def resolve_sets(session: requests.Session, sets_arg: str, newest: int) -> set[str] | None:
    if sets_arg.strip():
        return {part.strip() for part in sets_arg.split(",") if part.strip()}
    if newest <= 0:
        return None
    # The set list has no releaseDate. The set page does.
    listing = get_json(session, f"{TCGDEX}/sets")
    chosen: list[dict] = []
    for brief in listing:
        set_id = brief.get("id")
        if not set_id:
            continue
        detail = get_json(session, f"{TCGDEX}/sets/{set_id}")
        if detail.get("releaseDate"):
            chosen.append(detail)
    picked = sets_by_newest(chosen, newest)
    log(f"Newest {newest} sets: {sorted(picked)}")
    return picked


def embed_missing(session, session_ort, cards: list[dict]) -> tuple[list[str], np.ndarray]:
    input_name = session_ort.get_inputs()[0].name
    ids: list[str] = []
    rows: list[np.ndarray] = []
    started = time.time()
    for index, card in enumerate(cards, start=1):
        cid = card["id"]
        image = card["image"].rstrip("/")
        try:
            blob = get_bytes(session, image + "/high.webp")
        except FileNotFoundError:
            try:
                blob = get_bytes(session, image + "/low.webp")
            except FileNotFoundError:
                log(f"  skip {cid}: no image")
                continue
        vector = session_ort.run(None, {input_name: preprocess(Image.open(io.BytesIO(blob)))})[0][0]
        ids.append(cid)
        rows.append(l2_normalize(np.asarray(vector, dtype=np.float32).reshape(-1)))
        if index % 25 == 0 or index == len(cards):
            rate = index / max(time.time() - started, 0.001)
            log(f"  embedded {index}/{len(cards)} ({rate:.1f}/s)")
    if not rows:
        return [], np.zeros((0, EMBED_DIM), np.float32)
    return ids, np.stack(rows).astype(np.float32)


def publish(out: Path, version: str, base: Path) -> None:
    cmd = [
        sys.executable,
        str(PUBLISH),
        "--id", CATALOG_ID,
        "--version", version,
        "--file", str(out),
        "--base", str(base),
        "--clobber",
    ]
    log("Publishing " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("out/pokemon-en.stcat"))
    parser.add_argument("--cache", type=Path, default=Path("cache"))
    parser.add_argument("--model", type=Path, default=None)
    parser.add_argument("--sets", default="")
    parser.add_argument("--newest", type=int, default=0, help="Only cards from the N newest TCGdex sets")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--index", default=INDEX_URL)
    args = parser.parse_args()

    import onnxruntime as ort

    session = requests.Session()
    session.headers["User-Agent"] = "ScanTrader-pokemon-catalog/1.0"
    index_path = args.cache / "stcat-index.json"
    download(session, args.index, index_path)
    index = json.loads(index_path.read_text(encoding="utf-8"))
    entry = catalog_entry(index, CATALOG_ID)
    current = str(entry.get("version") or "")
    published = args.cache / "pokemon-en.published.stcat"
    download(session, entry["url"], published)
    digest = sha256_file(published)
    if entry.get("sha256") and digest != entry["sha256"]:
        raise SystemExit("published pokemon-en.stcat sha256 does not match the index")
    if entry.get("size") and published.stat().st_size != int(entry["size"]):
        raise SystemExit("published pokemon-en.stcat size does not match the index")

    old_ids, old_vectors = read_stcat(published)
    log(f"Published {CATALOG_ID} {current}: {len(old_ids)} cards")
    chosen = resolve_sets(session, args.sets, args.newest)
    cards = get_json(session, f"{TCGDEX}/cards")
    missing = filter_new_cards(cards, set(old_ids), chosen)
    log(f"Missing cards to embed: {len(missing)}")
    version = next_version(current)
    if not missing:
        log(f"Nothing new. Index stays at {current}.")
        return 0

    model = args.model or (args.cache / "model.onnx")
    download(session, MILO_URL, model)
    if sha256_file(model) != MODEL_SHA256:
        raise SystemExit(f"{model} sha256 does not match MODEL_SHA256")
    log(f"Milo sha256 ok ({MODEL_SHA256[:12]}…)")
    ort_session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    new_ids, new_vectors = embed_missing(session, ort_session, missing)
    if not new_ids:
        log("No embeddable cards. Index unchanged.")
        return 0
    ids = old_ids + new_ids
    vectors = np.concatenate([old_vectors, new_vectors], axis=0)
    write_stcat(args.out, ids, vectors)
    check_ids, check_vectors = read_stcat(args.out)
    if check_ids != ids or check_vectors.shape != vectors.shape:
        raise SystemExit("rewritten stcat failed the read-back check")
    if not np.allclose(check_vectors[:len(old_ids)], old_vectors, atol=1e-6):
        raise SystemExit("old embeddings changed")
    norms = np.linalg.norm(check_vectors[len(old_ids):], axis=1)
    if np.any(np.abs(norms - 1.0) > 1e-3):
        raise SystemExit("a new embedding is not unit length")
    log(
        f"Catalog {version}: {len(ids)} cards "
        f"(+{len(new_ids)}), {args.out.stat().st_size / 1e6:.1f} MB"
    )
    if args.dry_run:
        log("Dry run: not uploading.")
        return 0
    publish(args.out, version, index_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
