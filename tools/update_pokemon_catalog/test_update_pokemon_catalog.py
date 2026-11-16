"""Unit tests for the incremental Pokémon catalog update. No network."""

import tempfile
import unittest
from pathlib import Path

import numpy as np

from update_pokemon_catalog import (
    catalog_entry,
    filter_new_cards,
    known_set_ids,
    new_set_ids,
    next_version,
    sets_by_newest,
    validate_saved_stcat,
    write_stcat,
)


class VersionTests(unittest.TestCase):
    def test_next_version_is_strictly_greater_than_dotted_date(self):
        self.assertGreater(next_version("2026.10.26", "2026.11.15"), "2026.10.26")

    def test_same_day_appends_a_counter(self):
        self.assertEqual(next_version("2026.11.15", "2026.11.15"), "2026.11.15.1")
        self.assertEqual(next_version("2026.11.15.1", "2026.11.15"), "2026.11.15.2")


class FilterTests(unittest.TestCase):
    def test_keeps_only_missing_ids_in_the_requested_sets(self):
        cards = [
            {"id": "base1-1", "image": "https://example/base1/1"},
            {"id": "base1-2", "image": "https://example/base1/2"},
            {"id": "sv10-1", "image": "https://example/sv10/1"},
            {"id": "no-image"},
        ]
        missing = filter_new_cards(cards, known={"base1-1"}, sets_filter={"base1"})
        self.assertEqual([c["id"] for c in missing], ["base1-2"])

    def test_newest_sets_use_release_date(self):
        sets = [
            {"id": "old", "releaseDate": "1999-01-09"},
            {"id": "new", "releaseDate": "2026-11-01"},
            {"id": "mid", "releaseDate": "2024-05-01"},
        ]
        self.assertEqual(sets_by_newest(sets, 2), {"new", "mid"})


class IndexTests(unittest.TestCase):
    def test_catalog_entry_reads_pokemon_en(self):
        index = {
            "catalogs": [
                {"id": "mtg", "version": "2026.11.13"},
                {
                    "id": "pokemon-en",
                    "version": "2026.10.26",
                    "url": "https://example/pokemon-en.stcat",
                    "sha256": "abc",
                    "size": 12,
                },
            ]
        }
        entry = catalog_entry(index, "pokemon-en")
        self.assertEqual(entry["version"], "2026.10.26")
        self.assertEqual(entry["url"], "https://example/pokemon-en.stcat")


class SetDiffTests(unittest.TestCase):
    def test_new_sets_are_the_ones_missing_from_published_ids(self):
        known = known_set_ids(["base1-1", "base1-2", "sv10-4"])
        self.assertEqual(known, {"base1", "sv10"})
        fresh = new_set_ids(
            [{"id": "base1"}, {"id": "sv10"}, {"id": "me06"}, {"id": ""}],
            known,
        )
        self.assertEqual(fresh, ["me06"])

    def test_saved_stcat_must_load_and_not_shrink(self):
        ids = ["base1-1", "base1-2"]
        vectors = np.zeros((2, 128), dtype=np.float32)
        vectors[:, 0] = 1
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pokemon-en.stcat"
            write_stcat(path, ids, vectors)
            validate_saved_stcat(path, previous_count=2)
            short = Path(tmp) / "short.stcat"
            write_stcat(short, ids[:1], vectors[:1])
            with self.assertRaises(SystemExit):
                validate_saved_stcat(short, previous_count=2)


if __name__ == "__main__":
    unittest.main()
