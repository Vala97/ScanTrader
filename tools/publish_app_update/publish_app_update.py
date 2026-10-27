#!/usr/bin/env python3
"""Write app-update.json from a built APK and print the gh commands.

This script does not create a release and does not upload anything.
The app-update release must stay a prerelease so the catalog tag `stcat`
remains the latest non-prerelease (`releases/latest`).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shlex
import shutil
import sys
from pathlib import Path

DEFAULT_REPO = "Vala97/ScanTrader"
DEFAULT_TAG = "app-update"
DEFAULT_ASSET = "ScanTrader.apk"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_properties(path: Path) -> tuple[int, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    code = values.get("VERSION_CODE", "")
    name = values.get("VERSION_NAME", "")
    if not code.isdigit() or int(code) <= 0 or not name:
        raise SystemExit(f"version.properties incompleto: {path}")
    return int(code), name


def read_metadata(path: Path, apk_name: str) -> tuple[int, str]:
    document = json.loads(path.read_text(encoding="utf-8"))
    elements = document.get("elements")
    if not isinstance(elements, list) or not elements:
        raise SystemExit(f"output-metadata.json senza elements: {path}")
    chosen = None
    for element in elements:
        if isinstance(element, dict) and element.get("outputFile") == apk_name:
            chosen = element
            break
    if chosen is None:
        chosen = elements[0] if isinstance(elements[0], dict) else None
    if not isinstance(chosen, dict):
        raise SystemExit(f"output-metadata.json illeggibile: {path}")
    code = chosen.get("versionCode")
    name = chosen.get("versionName")
    if not isinstance(code, int) or isinstance(code, bool) or code <= 0:
        raise SystemExit(f"versionCode assente in {path}")
    if not isinstance(name, str) or not name.strip():
        raise SystemExit(f"versionName assente in {path}")
    return code, name.strip()


def resolve_version(args: argparse.Namespace, apk: Path) -> tuple[int, str]:
    if args.version_code is not None or args.version_name is not None:
        if args.version_code is None or args.version_name is None:
            raise SystemExit("Servono sia --version-code sia --version-name.")
        if args.version_code <= 0 or not args.version_name.strip():
            raise SystemExit("Versione non valida.")
        return args.version_code, args.version_name.strip()
    metadata = apk.parent / "output-metadata.json"
    if metadata.is_file():
        return read_metadata(metadata, apk.name)
    props = Path(args.version_properties) if args.version_properties else None
    if props is not None:
        if not props.is_file():
            raise SystemExit(f"version.properties mancante: {props}")
        return read_properties(props)
    default_props = Path(__file__).resolve().parents[2] / "app" / "version.properties"
    if default_props.is_file():
        return read_properties(default_props)
    raise SystemExit(
        "Versione sconosciuta. Passa --version-code e --version-name, "
        "oppure genera l'APK così che output-metadata.json gli stia accanto."
    )


def quote(value: str) -> str:
    return shlex.quote(value)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Scrive app-update.json e stampa i comandi gh. Non pubblica nulla.",
    )
    parser.add_argument("apk", help="APK già compilato")
    parser.add_argument("--version-code", type=int, default=None)
    parser.add_argument("--version-name", default=None)
    parser.add_argument(
        "--version-properties",
        default=None,
        help="app/version.properties, usato se non c'è output-metadata.json",
    )
    parser.add_argument("--notes", default=None, help="Testo italiano mostrato nel dialogo")
    parser.add_argument("--min-sdk", type=int, default=None)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--tag", default=DEFAULT_TAG)
    parser.add_argument("--asset-name", default=DEFAULT_ASSET)
    parser.add_argument("--out", default="app-update.json")
    args = parser.parse_args(argv)

    apk = Path(args.apk)
    if not apk.is_file():
        raise SystemExit(f"APK mancante: {apk}")
    if args.min_sdk is not None and args.min_sdk <= 0:
        raise SystemExit("--min-sdk deve essere un intero positivo.")
    asset_name = args.asset_name.strip()
    if not asset_name or "/" in asset_name or "\\" in asset_name or asset_name in {".", ".."}:
        raise SystemExit(f"nome asset non valido: {asset_name}")

    version_code, version_name = resolve_version(args, apk)
    notes = args.notes if args.notes is not None else f"Versione {version_name}."
    digest = sha256(apk)
    url = f"https://github.com/{args.repo}/releases/download/{args.tag}/{asset_name}"
    document = {
        "versionCode": version_code,
        "versionName": version_name,
        "apkUrl": url,
        "sha256": digest,
        "notes": notes,
    }
    if args.min_sdk is not None:
        document["minSdk"] = args.min_sdk

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    staged = out.parent / asset_name
    if staged.resolve() != apk.resolve():
        shutil.copyfile(apk, staged)
    else:
        staged = apk

    title = f"ScanTrader {version_name}"
    release_notes = (
        "Canale di aggiornamento in-app. Prerelease: "
        "il release dei cataloghi (stcat) resta l'ultimo non-prerelease."
    )
    print(f"Scritto {out}. Nessun upload eseguito.")
    print(f"APK da caricare: {staged}")
    print()
    print("# Il tag app-update DEVE restare un prerelease.")
    print("# Un release normale ruberebbe releases/latest al tag stcat dei cataloghi.")
    print("# L'app NON legge releases/latest per gli aggiornamenti.")
    print("# Crea il release una volta:")
    print(
        "gh release create "
        f"{quote(args.tag)} --repo {quote(args.repo)} --prerelease "
        f"--title {quote('Aggiornamenti ScanTrader')} "
        f"--notes {quote(release_notes)} "
        f"{quote(str(staged))} {quote(str(out))}"
    )
    print()
    print("# Se il release esiste già, sostituisci gli asset e tienilo prerelease:")
    print(f"gh release edit {quote(args.tag)} --repo {quote(args.repo)} --prerelease --title {quote(title)}")
    print(
        "gh release upload "
        f"{quote(args.tag)} --repo {quote(args.repo)} --clobber "
        f"{quote(str(staged))} {quote(str(out))}"
    )
    print()
    print(
        "Indice letto dall'app: "
        f"https://github.com/{args.repo}/releases/download/{args.tag}/app-update.json"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
