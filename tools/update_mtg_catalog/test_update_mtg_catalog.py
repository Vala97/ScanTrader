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
    latest_released_set,
    new_paper_sets,
    paper_english,
    partition_catalog_cards,
    released_paper_sets,
    save_catalog,
    sets_to_refresh,
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
        self.assertEqual(choose_version("2026.07.09", "2026-11-13", "2026-11-18"), "2026.11.13")
        self.assertEqual(choose_version("2026.07.09", "2027-04-09", "2026-11-18"), "2026.11.18")
        self.assertEqual(choose_version("2027.4.9", "2026-11-17", "2026-11-18"), "2027.4.10")
        self.assertEqual(choose_version("2026.11.13", "2026-11-13", "2026-11-12"), "2026.11.14")
        # The live catalog is already 2027.4.10. The app only updates on a
        # strictly greater dotted version, so a republish cannot go back to
        # 2026.11.13 even though that is the real last-set date.
        self.assertEqual(choose_version("2027.4.10", "2026-11-13", "2026-11-20"), "2027.4.11")

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
        self.assertEqual(new_paper_sets(sets, "2026.11.01", today="2026-11-18"), [])
        self.assertIsNone(new_paper_sets(sets, "not-a-date", today="2026-11-18"))

    def test_preview_sets_do_not_hide_released_sets_or_new_cards(self):
        sets = [
            {"code": "lcc", "name": "Lost Caverns Commander", "released_at": "2023-11-17", "digital": False, "card_count": 10},
            {"code": "nau", "name": "Nauctis", "released_at": "2027-02-01", "digital": False, "card_count": 24},
            {"code": "mdd", "name": "Marvel: Darkhold Destiny", "released_at": "2027-04-09", "digital": False, "card_count": 12},
            {"code": "trk", "name": "Star Trek", "released_at": "2026-11-13", "digital": False, "card_count": 300},
        ]
        released = released_paper_sets(sets, "2026-11-18")
        self.assertEqual({item["code"] for item in released}, {"lcc", "trk"})
        # Catalog already has lcc (10) and nothing of trk. A later reveal bumps lcc to 12.
        refresh = sets_to_refresh({"lcc": 12, "trk": 300}, {"lcc": 10})
        self.assertEqual(refresh, ["lcc", "trk"])
        self.assertEqual(
            latest_released_set(sets, "2026-11-18"),
            {"name": "Star Trek", "code": "trk", "date": "2026-11-13"},
        )
        cards = [
            {"id": "old-1", "lang": "en", "set": "lcc", "games": ["paper"], "image_uris": {"large": "https://example/a.jpg"}},
            {"id": "new-1", "lang": "en", "set": "lcc", "games": ["paper"], "image_uris": {"large": "https://example/b.jpg"}},
            {"id": "preview", "lang": "en", "set": "mdd", "games": ["paper"], "image_uris": {"large": "https://example/c.jpg"}},
            {"id": "trek", "lang": "en", "set": "trk", "games": ["paper"], "image_uris": {"large": "https://example/d.jpg"}},
        ]
        to_add, drop_ids, present, released_counts, known_counts = partition_catalog_cards(
            cards,
            {"old-1", "preview"},
            {"lcc", "trk"},
        )
        self.assertEqual([card["id"] for card in to_add], ["new-1", "trek"])
        self.assertEqual(drop_ids, {"preview"})
        self.assertEqual(present, {"lcc", "trk"})
        self.assertEqual(sets_to_refresh(released_counts, known_counts), ["lcc", "trk"])

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

    def test_latest_set_prefers_main_set_over_tokens_on_the_same_day(self):
        sets = [
            {"code": "ttrk", "name": "Star Trek Tokens", "released_at": "2026-11-13", "digital": False, "set_type": "token", "parent_set_code": "trk", "card_count": 27},
            {"code": "atrk", "name": "Star Trek Art Series", "released_at": "2026-11-13", "digital": False, "set_type": "memorabilia", "parent_set_code": "trk", "card_count": 54},
            {"code": "trc", "name": "Star Trek Commander", "released_at": "2026-11-13", "digital": False, "set_type": "commander", "parent_set_code": "trk", "card_count": 556},
            {"code": "sds", "name": "Stardates", "released_at": "2026-11-13", "digital": False, "set_type": "masterpiece", "parent_set_code": "trk", "card_count": 30},
            {"code": "punk", "name": "Prismatic Pride", "released_at": "2026-11-13", "digital": False, "set_type": "funny", "parent_set_code": "unk", "card_count": 20},
            {"code": "trk", "name": "Star Trek", "released_at": "2026-11-13", "digital": False, "set_type": "expansion", "card_count": 587},
            {"code": "lcc", "name": "Lost Caverns Commander", "released_at": "2023-11-17", "digital": False, "set_type": "commander", "card_count": 10},
        ]
        expected = {"name": "Star Trek", "code": "trk", "date": "2026-11-13"}
        self.assertEqual(latest_released_set(sets, "2026-11-20"), expected)
        self.assertEqual(latest_released_set(list(reversed(sets)), "2026-11-20"), expected)

    def test_latest_set_prefers_commander_over_tokens_when_parent_is_absent(self):
        sets = [
            {"code": "ttrk", "name": "Star Trek Tokens", "released_at": "2026-11-13", "digital": False, "set_type": "token", "parent_set_code": "trk", "card_count": 27},
            {"code": "ttrc", "name": "Star Trek Commander Tokens", "released_at": "2026-11-13", "digital": False, "set_type": "token", "parent_set_code": "trc", "card_count": 14},
            {"code": "trc", "name": "Star Trek Commander", "released_at": "2026-11-13", "digital": False, "set_type": "commander", "parent_set_code": "trk", "card_count": 556},
            {"code": "atrk", "name": "Star Trek Art Series", "released_at": "2026-11-13", "digital": False, "set_type": "memorabilia", "parent_set_code": "trk", "card_count": 54},
        ]
        self.assertEqual(
            latest_released_set(sets, "2026-11-20"),
            {"name": "Star Trek Commander", "code": "trc", "date": "2026-11-13"},
        )
        # Tokens first in the input, parent still wins over its own tokens.
        self.assertEqual(
            latest_released_set(list(reversed(sets)), "2026-11-20", {"ttrc", "trc"}),
            {"name": "Star Trek Commander", "code": "trc", "date": "2026-11-13"},
        )


if __name__ == "__main__":
    unittest.main()
