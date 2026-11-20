"""Unit tests for the incremental Pokémon catalog update. No network."""

import tempfile
import unittest
from pathlib import Path

import numpy as np

from update_pokemon_catalog import (
    catalog_entry,
    choose_latest_set,
    parse_card_count,
    released_set_details,
    sets_to_refresh,
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

    def test_latest_set_keeps_a_newer_published_row(self):
        chosen = choose_latest_set(
            {"latest_set_name": "Foundations", "latest_set_code": "fdn", "latest_set_date": "2026-11-01"},
            [{"id": "old", "name": "Base", "releaseDate": "1999-01-09"}],
        )
        self.assertEqual(chosen["name"], "Foundations")
        self.assertEqual(chosen["code"], "fdn")
        newer = choose_latest_set(
            {"latest_set_name": "Base", "latest_set_code": "base1", "latest_set_date": "1999-01-09"},
            [{"id": "me06", "name": "Mega Evolution", "releaseDate": "2026-11-01"}],
        )
        self.assertEqual(newer, {"name": "Mega Evolution", "code": "me06", "date": "2026-11-01"})
        preview = choose_latest_set(
            {"latest_set_name": "Mega Evolution", "latest_set_code": "me06", "latest_set_date": "2026-11-01"},
            [{"id": "darkhold", "name": "Darkhold", "releaseDate": "2027-04-09"}],
            today="2026-11-18",
        )
        self.assertEqual(preview["code"], "me06")
        self.assertEqual(preview["date"], "2026-11-01")

    def test_future_sets_are_skipped_until_release_and_card_counts_refresh(self):
        details = [
            {"id": "me06", "name": "Mega Evolution", "releaseDate": "2026-11-01", "cardCount": 200},
            {"id": "nau", "name": "Nauctis", "releaseDate": "2027-02-01", "cardCount": 24},
            {"id": "sv10", "name": "Existing", "releaseDate": "2025-01-01", "cardCount": 190},
        ]
        self.assertEqual({item["id"] for item in released_set_details(details, "2026-11-18")}, {"me06", "sv10"})
        refresh = sets_to_refresh(details, {"sv10": 180}, "2026-11-18")
        self.assertEqual(refresh, ["me06", "sv10"])

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

    def test_card_count_parses_int_or_tcgdex_object(self):
        self.assertEqual(parse_card_count(141), 141)
        self.assertEqual(parse_card_count("141"), 141)
        self.assertEqual(
            parse_card_count({"total": 141, "official": 103}),
            141,
        )
        self.assertEqual(
            parse_card_count({"official": 182, "holo": 119}),
            182,
        )
        self.assertEqual(
            parse_card_count({"firstEd": 0, "holo": 119, "normal": 152, "official": 182, "reverse": 165, "total": 244}),
            244,
        )
        self.assertIsNone(parse_card_count(None))
        self.assertIsNone(parse_card_count({}))
        self.assertIsNone(parse_card_count({"official": "n/a"}))
        self.assertIsNone(parse_card_count(["141"]))
        self.assertIsNone(parse_card_count("cards"))

    def test_sets_to_refresh_accepts_tcgdex_card_count_objects(self):
        details = [
            {"id": "me06", "name": "Delta Reign", "releaseDate": "2026-11-06", "cardCount": {"total": 141, "official": 103}},
            {"id": "sv10", "name": "Destined Rivals", "releaseDate": "2025-05-30", "cardCount": {"total": 244, "official": 182}},
            {"id": "svp", "name": "SVP Black Star Promos", "releaseDate": "2023-03-31", "cardCount": {"official": 225}},
            {"id": "weird", "name": "Broken", "releaseDate": "2025-01-01", "cardCount": {"holo": 10}},
            {"id": "nau", "name": "Nauctis", "releaseDate": "2027-02-01", "cardCount": {"total": 24}},
        ]
        known = {"me06": 141, "sv10": 180, "svp": 225, "weird": 10}
        # me06 matches total; sv10 total changed; svp official-only matches;
        # weird is unreadable so the comparison is skipped; nau is a preview.
        self.assertEqual(sets_to_refresh(details, known, "2026-11-20"), ["sv10"])
        # A set missing from the catalog is still refreshed, even with a dict count.
        self.assertEqual(
            sets_to_refresh(details, {"sv10": 244, "svp": 225, "weird": 10}, "2026-11-20"),
            ["me06"],
        )
        # Unreadable or missing counts do not crash and do not refresh a known set.
        self.assertEqual(
            sets_to_refresh(
                [
                    {"id": "sv10", "releaseDate": "2025-05-30", "cardCount": None},
                    {"id": "svp", "releaseDate": "2023-03-31"},
                    {"id": "bad", "releaseDate": "2025-01-01", "cardCount": {"holo": 1}},
                ],
                {"sv10": 180, "svp": 225, "bad": 1},
                "2026-11-20",
            ),
            [],
        )


if __name__ == "__main__":
    unittest.main()
