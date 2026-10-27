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
        catalogs.append(
            {
                "id": catalog_id,
                "name": names.get(catalog_id, catalog_id),
                "version": args.version,
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "url": url,
            }
        )
        paths.append(path)
    return {"catalogs": catalogs}, paths


def quote(path: Path) -> str:
    return shlex.quote(str(path))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Scrive stcat-index.json e stampa i comandi gh. Non pubblica nulla.",
    )
    parser.add_argument("--version", required=True, help="Versione scritta su ogni catalogo, es. 2026.10.26")
    parser.add_argument("--tag", default=DEFAULT_TAG, help=f"Tag del release (default {DEFAULT_TAG})")
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"owner/name (default {DEFAULT_REPO})")
    parser.add_argument("--out", default="stcat-index.json", help="Dove scrivere l'indice")
    parser.add_argument(
        "--name",
        action="append",
        default=[],
        help="Nome visibile, ripetibile: pokemon-en=Pokémon (inglese)",
    )
    parser.add_argument(
        "files",
        nargs="+",
        help="File .stcat, oppure id=percorso (l'id di default è il nome del file senza estensione)",
    )
    args = parser.parse_args(argv)
    document, paths = build_index(args)
    out = Path(args.out)
    out.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    asset_args = " ".join(quote(path) for path in paths)
    index_arg = quote(out)
    print(f"Scritto {out} ({len(document['catalogs'])} cataloghi). Nessun upload eseguito.")
    print()
    print("# Il release con questo tag deve restare l'ultimo non-prerelease,")
    print("# altrimenti releases/latest/download/stcat-index.json non lo vede.")
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
        f"https://github.com/{args.repo}/releases/latest/download/stcat-index.json"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
