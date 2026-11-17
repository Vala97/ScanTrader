"""Annotated tag messages become the update dialog notes. Commits do not."""

import unittest

from release_notes import release_notes

TAG_MESSAGE = """Novità di ScanTrader 0.5
- Etichetta «Foil» nella collezione, accanto alla lingua.

- Testi dell'introduzione rivisti.
"""

COMMIT_MESSAGE = """Merge pull request #36 from Vala97/cursor/release-0-5-f9c5

Prepare ScanTrader 0.5
"""


class ReleaseNotesTest(unittest.TestCase):
    def test_annotated_tag_keeps_its_message_and_drops_blank_lines(self):
        notes = release_notes("tag", TAG_MESSAGE, "0.5")
        self.assertTrue(notes.startswith("Novità di ScanTrader 0.5"))
        self.assertIn("Etichetta «Foil»", notes)
        self.assertNotIn("\n\n", notes)

    def test_a_lightweight_tag_does_not_use_the_commit_message(self):
        self.assertEqual(release_notes("commit", COMMIT_MESSAGE, "0.5"), "Versione 0.5.")

    def test_an_empty_annotation_falls_back(self):
        self.assertEqual(release_notes("tag", "\n\n", "0.5"), "Versione 0.5.")

    def test_only_the_first_twenty_non_empty_lines_are_kept(self):
        contents = "\n".join(f"riga {i}" for i in range(1, 30))
        notes = release_notes("tag", contents, "0.5")
        self.assertEqual(notes.splitlines()[0], "riga 1")
        self.assertEqual(notes.splitlines()[-1], "riga 20")
        self.assertEqual(len(notes.splitlines()), 20)


if __name__ == "__main__":
    unittest.main()
