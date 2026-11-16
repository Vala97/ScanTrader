"""The index writer adds optional set fields and leaves the required ones intact."""

import json
import tempfile
import unittest
from pathlib import Path

from publish_stcat import attach_latest_set, main


class PublishStcatTest(unittest.TestCase):
    def test_blank_latest_set_fields_are_omitted(self):
        row = {"id": "mtg", "version": "2026.07.09"}
        attach_latest_set(row, set_name="  ", set_code="", set_date="", updated_at="")
        self.assertEqual(row, {"id": "mtg", "version": "2026.07.09"})

    def test_index_keeps_required_fields_and_adds_the_latest_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            catalog = root / "milo.npz"
            catalog.write_bytes(b"not-a-real-catalog")
            base = root / "base.json"
            base.write_text(
                json.dumps({
                    "catalogs": [{
                        "id": "pokemon-en",
                        "name": "Pokémon (inglese)",
                        "version": "2026.10.26",
                        "size": 3,
                        "sha256": "aa",
                        "url": "https://example/pokemon-en.stcat",
                        "latest_set_name": "Surging Sparks",
                    }]
                }),
                encoding="utf-8",
            )
            out = root / "stcat-index.json"
            code = main([
                "--version", "2026.11.16",
                "--repo", "Vala97/ScanTrader",
                "--tag", "stcat",
                "--base", str(base),
                "--out", str(out),
                "--name", "mtg=Magic",
                "--latest-set-name", "Foundations",
                "--latest-set-code", "fdn",
                "--latest-set-date", "2026-11-01",
                "--updated-at", "2026-11-16",
                f"mtg={catalog}",
            ])
            self.assertEqual(code, 0)
            document = json.loads(out.read_text(encoding="utf-8"))
            by_id = {row["id"]: row for row in document["catalogs"]}
            magic = by_id["mtg"]
            self.assertEqual(magic["version"], "2026.11.16")
            self.assertEqual(magic["name"], "Magic")
            self.assertEqual(len(magic["sha256"]), 64)
            self.assertTrue(magic["url"].endswith("/milo.npz"))
            self.assertEqual(magic["latest_set_name"], "Foundations")
            self.assertEqual(magic["latest_set_code"], "fdn")
            self.assertEqual(magic["latest_set_date"], "2026-11-01")
            self.assertEqual(magic["updated_at"], "2026-11-16")
            self.assertEqual(by_id["pokemon-en"]["latest_set_name"], "Surging Sparks")
            self.assertEqual(by_id["pokemon-en"]["version"], "2026.10.26")


if __name__ == "__main__":
    unittest.main()
