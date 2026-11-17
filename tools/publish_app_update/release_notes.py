#!/usr/bin/env python3
"""Release-dialog notes from an annotated tag, or a short fallback.

The release workflow checks out the commit a tag points at. That makes the
local tag lightweight, so `%(contents)` is the commit message. Call this only
after `git cat-file -t` says the ref is a real tag object.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def release_notes(object_type: str, contents: str, version_name: str, limit: int = 20) -> str:
    """Use the annotated tag message. Anything else becomes ``Versione X.``."""
    name = version_name.strip() or "X"
    fallback = f"Versione {name}."
    if object_type.strip() != "tag":
        return fallback
    lines = [line for line in contents.splitlines() if line.strip()]
    text = "\n".join(lines[:limit]).strip()
    return text or fallback


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--object-type", required=True, help="Output of git cat-file -t")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--contents-file", default="", help="Tag message. Empty reads stdin.")
    parser.add_argument("--limit", type=int, default=20)
    args = parser.parse_args(argv)
    if args.contents_file:
        contents = Path(args.contents_file).read_text(encoding="utf-8")
    else:
        contents = sys.stdin.read()
    sys.stdout.write(release_notes(args.object_type, contents, args.version_name, args.limit))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
