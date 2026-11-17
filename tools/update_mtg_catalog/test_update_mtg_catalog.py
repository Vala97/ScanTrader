import gzip
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from update_mtg_catalog import (
    bulk_source,
    choose_version,
    front_image_url,
    iter_cards_file,
    new_paper_sets,
    paper_english,
    save_catalog,
    validate_saved_catalog,
    version_greater,
)


class UpdateMtgCatalogTest(unittest.TestCase):
    def test_paper_english_keeps_a_paper_card_and_drops_digital_only(self):
        paper = {
            "id": "aaa",
            "lang": "en",
            "games": ["paper", "mtgo"],
            "image_uris": {"large": "https://cards.scryfall.io/large/front/a.jpg"},
        }
        arena = {
            "id": "bbb",
            "lang": "en",
            "digital": True,
            "games": ["arena"],
            "image_uris": {"large": "https://cards.scryfall.io/large/front/b.jpg"},
        }
        italian = {
            "id": "ccc",
            "lang": "it",
            "games": ["paper"],
            "image_uris": {"large": "https://cards.scryfall.io/large/front/c.jpg"},
        }
        self.assertTrue(paper_english(paper))
        self.assertFalse(paper_english(arena))
        self.assertFalse(paper_english(italian))
        self.assertTrue(front_image_url(paper).endswith("a.jpg"))

    def test_version_is_strictly_newer(self):
        self.assertTrue(version_greater("2026.11.13", "2026.07.09"))
        self.assertFalse(version_greater("2026.07.09", "2026.07.09"))
        self.assertEqual(choose_version("2026.07.09", "2026-11-13", "2026-11-12"), "2026.11.13")
        self.assertEqual(choose_version("2026.11.13", "2026-11-13", "2026-11-12"), "2026.11.14")

    def test_bulk_prefers_legacy_uri_and_reads_json_and_jsonl(self):
        self.assertEqual(
            bulk_source({
                "download_uri": "https://example/cards.json",
                "jsonl_download_uri": "https://example/cards.jsonl",
            }),
            ("https://example/cards.json", "json"),
        )
        self.assertEqual(
            bulk_source({"jsonl_download_uri": "https://example/cards.jsonl"}),
            ("https://example/cards.jsonl", "jsonl"),
        )
        cards = [
            {"id": "11111111-1111-1111-1111-111111111111", "lang": "en", "set": "old"},
            {"id": "22222222-2222-2222-2222-222222222222", "lang": "en", "set": "new"},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            array = root / "cards.json"
            array.write_text(json.dumps(cards), encoding="utf-8")
            self.assertEqual(list(iter_cards_file(array, "json")), cards)
            lines = "\n".join(json.dumps(card) for card in cards) + "\n"
            jsonl = root / "cards.jsonl"
            jsonl.write_text(lines, encoding="utf-8")
            self.assertEqual([card["id"] for card in iter_cards_file(jsonl, "jsonl")], [card["id"] for card in cards])
            packed = root / "cards.jsonl.gz"
            with gzip.open(packed, "wt", encoding="utf-8") as handle:
                handle.write(lines)
            self.assertEqual(list(iter_cards_file(packed, "jsonl")), cards)

    def test_new_paper_sets_are_those_released_after_the_catalog_version(self):
        sets = [
            {"code": "old", "released_at": "2026-07-01", "digital": False},
            {"code": "same", "released_at": "2026-07-09", "digital": False},
            {"code": "trk", "released_at": "2026-11-01", "digital": False},
            {"code": "arena", "released_at": "2026-11-02", "digital": True},
        ]
        self.assertEqual(new_paper_sets(sets, "2026.07.09"), ["trk"])
        self.assertEqual(new_paper_sets(sets, "2026.11.01"), [])
        self.assertIsNone(new_paper_sets(sets, "not-a-date"))

    def test_saved_catalog_must_load_and_not_shrink(self):
        ids = ["11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"]
        vectors = np.zeros((2, 128), dtype=np.float32)
        vectors[:, 0] = 1
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "catalog.npz"
            save_catalog(path, vectors, ids, 36)
            validate_saved_catalog(path, previous_count=2)
            short = Path(tmp) / "short.npz"
            save_catalog(short, vectors[:1], ids[:1], 36)
            with self.assertRaises(SystemExit):
                validate_saved_catalog(short, previous_count=2)


if __name__ == "__main__":
    unittest.main()
