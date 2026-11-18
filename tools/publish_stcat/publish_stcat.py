#!/usr/bin/env python3
"""Build stcat-index.json and print the gh commands that upload it.

This script does not create a release and does not upload anything.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shlex
import sys
from pathlib import Path

DEFAULT_REPO = "Vala97/ScanTrader"
DEFAULT_TAG = "stcat"
DEFAULT_NAMES = {
    "pokemon-en": "Pokémon (inglese)",
}


def parse_name_flags(items: list[str]) -> dict[str, str]:
    names: dict[str, str] = {}
    for item in items:
        catalog_id, sep, label = item.partition("=")
        if not sep or not catalog_id or not label:
            raise SystemExit(f"--name vuole id=Nome, ricevuto: {item}")
        names[catalog_id] = label
    return names


def catalog_spec(spec: str) -> tuple[str, Path]:
    if "=" in spec:
        catalog_id, raw_path = spec.split("=", 1)
    else:
        raw_path = spec
        catalog_id = Path(spec).stem
    path = Path(raw_path)
    if not catalog_id or catalog_id in {".", ".."} or ".." in catalog_id:
        raise SystemExit(f"id non valido: {catalog_id}")
    if not path.is_file():
        raise SystemExit(f"file mancante: {path}")
    return catalog_id, path


def release_upload_allowed(
    *,
    event: str,
    dry_run: bool,
    validated: bool,
    added: int,
    force: bool = False,
    changed: bool | None = None,
) -> bool:
    """A daily run publishes a validated catalog that changed.

    A manual run does so only when dry_run is false. `force` publishes that
    manual run even when no new set was detected. A failed validation never uploads.
    """
    if changed is None:
        changed = added > 0
    if not validated:
        return False
    if event == "schedule":
        return bool(changed)
    if event == "workflow_dispatch":
        if dry_run:
            return False
        return bool(force or changed)
    return False


def index_matches_catalog(index_path: Path, catalog_id: str, file_path: Path) -> None:
    """The published row must describe this file. Raise before any upload."""
    document = json.loads(Path(index_path).read_text(encoding="utf-8"))
    rows = [row for row in document.get("catalogs") or [] if isinstance(row, dict) and row.get("id") == catalog_id]
    if len(rows) != 1:
        raise SystemExit(f"indice senza una sola riga {catalog_id}")
    data = Path(file_path).read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    row = rows[0]
    if row.get("sha256") != digest or int(row.get("size") or -1) != len(data):
        raise SystemExit(f"sha256 o size di {catalog_id} non coincidono col file")
    url = str(row.get("url") or "")
    if not url.startswith("https://") or not url.endswith("/" + Path(file_path).name):
        raise SystemExit(f"url di {catalog_id} non punta a questo file")


def attach_latest_set(
    entry: dict,
    *,
    set_name: str = "",
    set_code: str = "",
    set_date: str = "",
    updated_at: str = "",
) -> dict:
    """Add optional fields. Blank values are omitted so an old index stays valid."""
    if set_name.strip():
        entry["latest_set_name"] = set_name.strip()
    if set_code.strip():
        entry["latest_set_code"] = set_code.strip()
    if set_date.strip():
        entry["latest_set_date"] = set_date.strip()
    if updated_at.strip():
        entry["updated_at"] = updated_at.strip()
    return entry


def build_index(args: argparse.Namespace) -> tuple[dict, list[Path]]:
    names = dict(DEFAULT_NAMES)
    names.update(parse_name_flags(args.name))
    catalogs = []
    paths: list[Path] = []
    seen: set[str] = set()
    for spec in args.files:
        catalog_id, path = catalog_spec(spec)
        if catalog_id in seen:
            raise SystemExit(f"id duplicato: {catalog_id}")
        seen.add(catalog_id)
        data = path.read_bytes()
        url = (
            f"https://github.com/{args.repo}/releases/download/"
            f"{args.tag}/{path.name}"
        )
        row = {
            "id": catalog_id,
            "name": names.get(catalog_id, catalog_id),
            "version": args.version,
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "url": url,
        }
        attach_latest_set(
            row,
            set_name=getattr(args, "latest_set_name", ""),
            set_code=getattr(args, "latest_set_code", ""),
            set_date=getattr(args, "latest_set_date", ""),
            updated_at=getattr(args, "updated_at", ""),
        )
        catalogs.append(row)
        paths.append(path)
    return {"catalogs": catalogs}, paths


def quote(path: Path) -> str:
    return shlex.quote(str(path))


def merge_base(document: dict, base: Path) -> dict:
    """Keep rows in ``base`` whose id is not among the files just hashed."""
    raw = json.loads(base.read_text(encoding="utf-8"))
    catalogs = raw.get("catalogs") if isinstance(raw, dict) else None
    if not isinstance(catalogs, list):
        raise SystemExit(f"{base} non ha un array catalogs")
    new_ids = {entry["id"] for entry in document["catalogs"]}
    kept: list[dict] = []
    for entry in catalogs:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            raise SystemExit(f"{base} ha una riga senza id")
        if entry["id"] not in new_ids:
            kept.append(entry)
    document["catalogs"] = kept + document["catalogs"]
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Scrive stcat-index.json e stampa i comandi gh. Non pubblica nulla.",
    )
    parser.add_argument("--version", required=True, help="Versione scritta sui file di questa esecuzione, es. 2026.10.26")
    parser.add_argument("--tag", default=DEFAULT_TAG, help=f"Tag del release (default {DEFAULT_TAG})")
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"owner/name (default {DEFAULT_REPO})")
    parser.add_argument("--out", default="stcat-index.json", help="Dove scrivere l'indice")
    parser.add_argument(
        "--base",
        help="Indice già pubblicato: le righe con un altro id restano, con la loro versione",
    )
    parser.add_argument(
        "--name",
        action="append",
        default=[],
        help="Nome visibile, ripetibile: pokemon-en=Pokémon (inglese)",
    )
    parser.add_argument("--latest-set-name", default="", help="Nome dell'ultimo set incluso, es. Foundations")
    parser.add_argument("--latest-set-code", default="", help="Sigla dell'ultimo set, es. fdn")
    parser.add_argument("--latest-set-date", default="", help="Data di uscita dell'ultimo set, YYYY-MM-DD")
    parser.add_argument("--updated-at", default="", help="Data di questa pubblicazione, YYYY-MM-DD")
    parser.add_argument(
        "files",
        nargs="+",
        help="File .stcat o .npz, oppure id=percorso (l'id di default è il nome del file senza estensione)",
    )
    args = parser.parse_args(argv)
    document, paths = build_index(args)
    if args.base:
        document = merge_base(document, Path(args.base))
    out = Path(args.out)
    out.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    asset_args = " ".join(quote(path) for path in paths)
    index_arg = quote(out)
    print(f"Scritto {out} ({len(document['catalogs'])} cataloghi). Nessun upload eseguito.")
    print()
    print("# L'app legge l'URL fisso del tag, non releases/latest.")
    print("# Un release normale su un tag v* va tenuto fuori da Latest.")
    print("# Non toccare il tag prerelease app-update.")
    if args.base:
        print("# --base ha tenuto le righe già pubblicate. Non ricreare il release:")
        print("# un gh release create sostituirebbe l'elenco degli asset.")
    else:
        print("# Crea il release una volta (non aggiungere --prerelease):")
        print(
            f"gh release create {shlex.quote(args.tag)} --repo {shlex.quote(args.repo)} "
            f"--title {shlex.quote('Cataloghi Pokémon ' + args.version)} "
            f"--notes {shlex.quote('Cataloghi .stcat per ScanTrader')} "
            f"{asset_args} {index_arg}"
        )
        print()
        print("# Se il release esiste già, sostituisci gli asset:")
    print(
        f"gh release upload {shlex.quote(args.tag)} --repo {shlex.quote(args.repo)} "
        f"--clobber {asset_args} {index_arg}"
    )
    print()
    print(
        "Indice letto dall'app: "
        f"https://github.com/{args.repo}/releases/download/{args.tag}/stcat-index.json"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
