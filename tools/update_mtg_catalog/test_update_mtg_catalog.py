import unittest

from update_mtg_catalog import choose_version, front_image_url, paper_english, version_greater


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


if __name__ == "__main__":
    unittest.main()
