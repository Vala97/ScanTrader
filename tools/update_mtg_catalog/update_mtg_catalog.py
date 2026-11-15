#!/usr/bin/env python3
"""Append new paper English Magic cards to the published Milo .npz.

Downloads the catalog named by stcat-index.json, finds Scryfall ids that are
missing, embeds only those images with model.onnx, and writes a full new .npz.
Images are not kept. This script never uploads a GitHub release.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import time
import zipfile
from datetime import date
from pathlib import Path

import numpy as np
import requests
from PIL import Image

INDEX_URL = "https://github.com/Vala97/ScanTrader/releases/download/stcat/stcat-index.json"
MODEL_URL = "https://huggingface.co/HanClinto/milo/resolve/main/model.onnx"
MODEL_SHA256 = "bd13d8d60383c69da04dce261f32e93fdaeaa8fd618fbc991e7385f71b3d45df"
SCRYFALL = "https://api.scryfall.com"
USER_AGENT = "ScanTrader-update-mtg-catalog/1.0 (https://github.com/Vala97/ScanTrader)"
INPUT_SIZE = 448
EMBED_DIM = 128
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32).reshape(3, 1, 1)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32).reshape(3, 1, 1)
MIN_REQUEST_GAP = 0.1  # at most 10 requests per second


def log(msg: str) -> None:
    print(msg, flush=True)


def version_parts(value: str) -> list[int]:
    parts: list[int] = []
    for piece in value.strip().split("."):
        if piece.isdigit():
            parts.append(int(piece))
        elif piece == "":
            parts.append(0)
        else:
            raise ValueError(f"versione non numerica: {value}")
    return parts or [0]


def version_greater(left: str, right: str) -> bool:
    a = version_parts(left)
    b = version_parts(right)
    n = max(len(a), len(b))
    a += [0] * (n - len(a))
    b += [0] * (n - len(b))
    return a > b


def dotted_date(iso_day: str) -> str:
    year, month, day = iso_day.split("-")
    return f"{int(year)}.{int(month)}.{int(day)}"


def choose_version(current: str, newest_set_day: str | None, today: str) -> str:
    """Newest set date, otherwise the run date. Always strictly newer."""
    candidates = []
    if newest_set_day:
        candidates.append(dotted_date(newest_set_day))
    candidates.append(dotted_date(today))
    for candidate in candidates:
        if version_greater(candidate, current):
            return candidate
    parts = version_parts(current)
    parts[-1] += 1
    return ".".join(str(part) for part in parts)


def paper_english(card: dict) -> bool:
    """English paper printing. Digital-only (Arena/MTGO without paper) is out.

    Milo's English catalog rows are lang=en and one id per Scryfall printing.
    A back face is not a second id.
    """
    if (card.get("lang") or "").lower() != "en":
        return False
    games = card.get("games") or []
    if "paper" not in games:
        return False
    return front_image_url(card) is not None


def front_image_url(card: dict) -> str | None:
    uris = card.get("image_uris") or {}
    for key in ("large", "normal", "png"):
        url = uris.get(key)
        if isinstance(url, str) and url.startswith("https://"):
            return url
    faces = card.get("card_faces") or []
    if faces:
        face_uris = faces[0].get("image_uris") or {}
        for key in ("large", "normal", "png"):
            url = face_uris.get(key)
            if isinstance(url, str) and url.startswith("https://"):
                return url
    return None


class Pace:
    def __init__(self, gap: float = MIN_REQUEST_GAP) -> None:
        self.gap = gap
        self._next = 0.0

    def wait(self) -> None:
        now = time.monotonic()
        if now < self._next:
            time.sleep(self._next - now)
        self._next = time.monotonic() + self.gap


def request_json(session: requests.Session, url: str, pace: Pace) -> dict:
    pace.wait()
    response = session.get(url, timeout=120, headers={"Accept": "application/json"})
    if response.status_code == 404:
        return {"data": [], "has_more": False}
    response.raise_for_status()
    return response.json()


def mtg_entry(index: dict) -> dict:
    catalogs = index.get("catalogs")
    if not isinstance(catalogs, list):
        raise SystemExit("stcat-index.json senza catalogs")
    chosen = None
    for entry in catalogs:
        if not isinstance(entry, dict):
            continue
        catalog_id = str(entry.get("id") or "").lower()
        if catalog_id in {"mtg", "magic"} or catalog_id.startswith(("mtg-", "magic-")):
            if chosen is None or version_greater(str(entry.get("version") or "0"), str(chosen.get("version") or "0")):
                chosen = entry
    if chosen is None or not str(chosen.get("url") or "").startswith("https://"):
        raise SystemExit("indice senza una riga mtg")
    return chosen


def load_catalog(path: Path) -> tuple[np.ndarray, list[str], int]:
    with np.load(path, allow_pickle=False) as data:
        if "embeddings" not in data.files or "card_ids" not in data.files:
            raise SystemExit(f"{path.name} non ha embeddings e card_ids")
        embeddings = np.array(data["embeddings"], dtype=np.float32, copy=True)
        raw_ids = data["card_ids"]
        ids = [str(item).replace("\x00", "").strip() for item in raw_ids.tolist()]
        width = raw_ids.dtype.itemsize // 4 if raw_ids.dtype.kind == "U" else 36
    if embeddings.ndim != 2 or embeddings.shape[1] != EMBED_DIM:
        raise SystemExit(f"embeddings shape {embeddings.shape}, atteso (*, {EMBED_DIM})")
    if embeddings.shape[0] != len(ids):
        raise SystemExit("embeddings e card_ids hanno lunghezze diverse")
    return embeddings, ids, max(width, 36)


def save_catalog(path: Path, embeddings: np.ndarray, ids: list[str], width: int) -> None:
    """NumPy 1.0 .npy inside the zip. The app rejects npy 2.0."""
    needed = max(width, max((len(item) for item in ids), default=1))
    id_array = np.array(ids, dtype=f"<U{needed}")
    matrix = np.ascontiguousarray(embeddings, dtype="<f4")
    emb_buf = io.BytesIO()
    id_buf = io.BytesIO()
    np.lib.format.write_array(emb_buf, matrix, version=(1, 0))
    np.lib.format.write_array(id_buf, id_array, version=(1, 0))
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("embeddings.npy", emb_buf.getvalue())
        archive.writestr("card_ids.npy", id_buf.getvalue())


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_file(session: requests.Session, url: str, dest: Path, pace: Pace, expected_sha: str | None = None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        if expected_sha is None or sha256_file(dest) == expected_sha:
            return
        dest.unlink()
    pace.wait()
    tmp = dest.with_suffix(dest.suffix + ".part")
    with session.get(url, stream=True, timeout=300) as response:
        response.raise_for_status()
        with tmp.open("wb") as handle:
            for chunk in response.iter_content(1024 * 1024):
                if chunk:
                    handle.write(chunk)
    if expected_sha is not None and sha256_file(tmp) != expected_sha:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"sha256 inatteso per {dest.name}")
    tmp.replace(dest)


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


def search_set(session: requests.Session, set_code: str, pace: Pace) -> list[dict]:
    query = f"e:{set_code.lower()} lang:en game:paper unique:prints"
    url = f"{SCRYFALL}/cards/search?q={requests.utils.quote(query)}"
    cards: list[dict] = []
    while url:
        page = request_json(session, url, pace)
        cards.extend(page.get("data") or [])
        url = page.get("next_page") if page.get("has_more") else None
    return cards


def iter_bulk(session: requests.Session, pace: Pace, dest: Path):
    import ijson

    bulk = request_json(session, f"{SCRYFALL}/bulk-data", pace)
    entry = next((item for item in bulk.get("data") or [] if item.get("type") == "default_cards"), None)
    if entry is None:
        raise SystemExit("Scryfall bulk default_cards assente")
    download_file(session, entry["download_uri"], dest, pace)
    with dest.open("rb") as handle:
        yield from ijson.items(handle, "item")


def missing_cards(cards, known: set[str], set_code: str | None) -> list[dict]:
    wanted = set_code.lower() if set_code else None
    chosen: dict[str, dict] = {}
    for card in cards:
        if not paper_english(card):
            continue
        if wanted and str(card.get("set") or "").lower() != wanted:
            continue
        card_id = str(card.get("id") or "")
        if not card_id or card_id in known or card_id in chosen:
            continue
        chosen[card_id] = card
    return list(chosen.values())


def sanity(old_count: int, embeddings: np.ndarray, ids: list[str]) -> None:
    if embeddings.shape[0] < old_count:
        raise SystemExit("il catalogo nuovo ha meno righe del vecchio")
    if embeddings.shape[0] != len(ids):
        raise SystemExit("righe e id non coincidono")
    if embeddings.shape[1] != EMBED_DIM:
        raise SystemExit("dimensione embedding diversa da 128")
    if len(ids) != len(set(ids)):
        raise SystemExit("id duplicati")
    if not np.isfinite(embeddings).all():
        raise SystemExit("embedding non finiti")


def self_match(session_ort, input_name: str, vectors: np.ndarray, images: list[Image.Image]) -> None:
    for index, image in enumerate(images):
        fresh = embed_image(session_ort, input_name, image)
        score = float(np.dot(fresh, vectors[index]))
        if score < 0.999:
            raise SystemExit(f"self-match {score:.4f} sotto 0.999")


def embed_image(session_ort, input_name: str, image: Image.Image) -> np.ndarray:
    batch = preprocess(image)
    raw = session_ort.run(None, {input_name: batch})[0]
    vector = np.asarray(raw, dtype=np.float32).reshape(-1)
    if vector.shape[0] != EMBED_DIM:
        raise SystemExit(f"uscita ONNX di {vector.shape[0]} valori")
    return l2_normalize(vector)


def download_image(session: requests.Session, url: str, pace: Pace) -> Image.Image:
    pace.wait()
    response = session.get(url, timeout=120)
    response.raise_for_status()
    data = response.content
    image = Image.open(io.BytesIO(data))
    image.load()
    return image


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Accoda carte Magic nuove al catalogo Milo. Non pubblica.")
    parser.add_argument("--out-dir", type=Path, default=Path("tools/update_mtg_catalog/out"))
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "scantrader")
    parser.add_argument("--set", dest="set_code", default="", help="Solo questo set, es. trk")
    parser.add_argument("--dry-run", action="store_true", help="Scrive i file in locale e non va oltre")
    parser.add_argument("--index-url", default=INDEX_URL)
    args = parser.parse_args(argv)

    try:
        import onnxruntime as ort
    except ImportError:
        log("Manca onnxruntime. pip install -r tools/update_mtg_catalog/requirements.txt")
        return 1

    session = requests.Session()
    session.headers["User-Agent"] = USER_AGENT
    pace = Pace()
    started = time.time()

    log(f"Indice {args.index_url}")
    index = request_json(session, args.index_url, pace)
    entry = mtg_entry(index)
    current = str(entry.get("version") or "").strip()
    if not current:
        raise SystemExit("la riga mtg non ha version")
    log(f"Catalogo pubblicato {entry.get('url')} versione {current}")

    catalog_path = args.cache / Path(str(entry["url"]).split("?")[0]).name
    download_file(session, entry["url"], catalog_path, pace, expected_sha=str(entry.get("sha256") or "") or None)
    old_embeddings, old_ids, width = load_catalog(catalog_path)
    known = set(old_ids)
    log(f"Catalogo locale {len(old_ids)} carte")

    set_code = args.set_code.strip().lower() or None
    if set_code:
        source = search_set(session, set_code, pace)
        log(f"Scryfall set:{set_code} {len(source)} carte inglesi paper")
    else:
        bulk_path = args.cache / "scryfall-default-cards.json"
        source = iter_bulk(session, pace, bulk_path)
    new_cards = missing_cards(source, known, set_code)
    if not new_cards:
        log("Nessuna carta nuova. Nessun file scritto.")
        args.out_dir.mkdir(parents=True, exist_ok=True)
        (args.out_dir / "update-result.json").write_text(
            json.dumps({"added": 0, "version": current, "unchanged": True}) + "\n",
            encoding="utf-8",
        )
        return 0

    model_path = args.cache / "milo.onnx"
    download_file(session, MODEL_URL, model_path, pace, expected_sha=MODEL_SHA256)
    if sha256_file(model_path) != MODEL_SHA256:
        raise SystemExit("model.onnx sha256 diverso da MODEL_SHA256")

    log(f"Embedding di {len(new_cards)} carte nuove…")
    ort_session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = ort_session.get_inputs()[0].name
    vectors: list[np.ndarray] = []
    added_ids: list[str] = []
    newest_day = ""
    check_images: list[Image.Image] = []
    for card in new_cards:
        url = front_image_url(card)
        if url is None:
            continue
        try:
            image = download_image(session, url, pace)
        except Exception as error:
            log(f"  salto {card.get('id')} immagine: {error}")
            continue
        try:
            vectors.append(embed_image(ort_session, input_name, image))
        except Exception as error:
            log(f"  salto {card.get('id')} embed: {error}")
            image.close()
            continue
        added_ids.append(str(card["id"]))
        released = str(card.get("released_at") or "")
        if released > newest_day:
            newest_day = released
        if len(check_images) < 3:
            check_images.append(image.copy())
        image.close()
        if len(added_ids) % 25 == 0:
            log(f"  {len(added_ids)}/{len(new_cards)}")

    if not added_ids:
        log("Nessuna carta nuova incorporata.")
        return 0

    fresh = np.stack(vectors, axis=0).astype(np.float32)
    self_match(ort_session, input_name, fresh[: len(check_images)], check_images)
    for image in check_images:
        image.close()

    embeddings = np.concatenate([old_embeddings, fresh], axis=0)
    ids = old_ids + added_ids
    sanity(len(old_ids), embeddings, ids)
    version = choose_version(current, newest_day or None, date.today().isoformat())
    filename = f"milo1-scryfall-mtg-{version.replace('.', '-')}.npz"
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    npz_path = out_dir / filename
    save_catalog(npz_path, embeddings, ids, width)

    base_index = out_dir / "stcat-index-base.json"
    base_index.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    new_index = out_dir / "stcat-index.json"
    publish = Path(__file__).resolve().parents[1] / "publish_stcat" / "publish_stcat.py"
    subprocess.run(
        [
            sys.executable,
            str(publish),
            "--version",
            version,
            "--name",
            "mtg=Magic",
            "--base",
            str(base_index),
            "--out",
            str(new_index),
            f"mtg={npz_path}",
        ],
        check=True,
    )
    elapsed = time.time() - started
    result = {
        "added": len(added_ids),
        "previous": len(old_ids),
        "total": len(ids),
        "version": version,
        "previous_version": current,
        "npz": str(npz_path),
        "bytes": npz_path.stat().st_size,
        "seconds": round(elapsed, 1),
        "unchanged": False,
        "dry_run": bool(args.dry_run),
        "set": set_code or "",
    }
    (out_dir / "update-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    log(
        f"Aggiunte {len(added_ids)} carte. Totale {len(ids)}. "
        f"Versione {version}. File {npz_path.name} {npz_path.stat().st_size} byte. {elapsed:.0f}s."
    )
    if args.dry_run:
        log("Dry-run: nessun upload.")
    else:
        log("File pronti. L'upload, se previsto, lo fa il workflow con gh release upload --clobber.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
