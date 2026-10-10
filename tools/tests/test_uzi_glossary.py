"""Tests for tools/glossary/convert_uzi_glossary.py and the generated app asset."""

import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "glossary"))
import convert_uzi_glossary as conv  # noqa: E402

ASSET = ROOT / "app" / "src" / "main" / "assets" / "uzi_glossary.tsv"
WORKBOOK = ROOT / "docs" / "glossary" / "UZI_atamalari_tekshirilgan.xlsx"


def row(number, english, uz, note, status, uz_fix=None, note_fix=None, general="Yo'q", other=None):
    return (number, "Kategoriya", english, other, uz, note, status, uz_fix, note_fix, general)


class ConvertRowsTest(unittest.TestCase):
    def test_status_rules(self):
        result = conv.convert_rows(
            [
                row(1, "liver", "jigar", "izoh", "To'g'ri", general="Ha"),
                row(2, "echogenicity", "exogenlik", "eski", "Tuzatildi", "echogenlik", "yangi izoh"),
                row(3, "gone", "x", "y", "O‘chirish"),
                row(4, "draft", "x", "y", "Tekshirilmagan"),
                row(5, "new term", "yangi", "izoh", "Yangi"),
            ],
        )
        self.assertEqual(result.total_rows, 5)
        by_id = {e.id: e for e in result.entries}
        self.assertEqual(sorted(by_id), [1, 2, 5])
        self.assertEqual((by_id[1].uzbek, by_id[1].status, by_id[1].general), ("jigar", "approved", True))
        self.assertEqual((by_id[2].uzbek, by_id[2].note, by_id[2].status), ("echogenlik", "yangi izoh", "corrected"))
        self.assertEqual(by_id[5].status, "new")
        self.assertEqual(len(result.skipped), 2)

    def test_empty_correction_cells_keep_the_draft(self):
        result = conv.convert_rows(
            [
                row(1, "a", "qoralama", "izoh", "Tuzatildi", None, "tuzatilgan izoh"),
                row(2, "b", "qoralama", "izoh", "Tuzatildi", "tuzatilgan", None),
            ],
        )
        a, b = result.entries
        self.assertEqual((a.uzbek, a.note), ("qoralama", "tuzatilgan izoh"))
        self.assertEqual((b.uzbek, b.note), ("tuzatilgan", "izoh"))

    def test_uzbek_typography_only_changes_apostrophes(self):
        self.assertEqual(conv.uzbek_typography("o't yo'li"), "o‘t yo‘li")
        self.assertEqual(conv.uzbek_typography("ma'no, taʼminlaydi"), "ma’no, ta’minlaydi")
        self.assertEqual(conv.uzbek_typography("jigar venasining"), "jigar venasining")
        self.assertEqual(conv.uzbek_typography("'kometa dumi'"), "'kometa dumi'")

    def test_aliases_are_split(self):
        (entry,) = conv.convert_rows([row(1, "resistive index", "RI", "x", "To'g'ri", other="RI; resistivity index")]).entries
        self.assertEqual(entry.aliases, ["RI", "resistivity index"])


class AssetTest(unittest.TestCase):
    def test_asset_has_every_reviewed_term(self):
        lines = [l for l in ASSET.read_text(encoding="utf-8").splitlines() if l and not l.startswith("#")]
        header, body = lines[0].split("\t"), lines[1:]
        self.assertEqual(header, conv.HEADER)
        rows = [l.split("\t") for l in body]
        self.assertTrue(all(len(r) == len(conv.HEADER) for r in rows))
        self.assertEqual(len(rows), 227)
        statuses = [r[6] for r in rows]
        self.assertEqual(statuses.count("approved"), 172)
        self.assertEqual(statuses.count("corrected"), 55)
        self.assertEqual(sum(r[7] == "1" for r in rows), 20)

    @unittest.skipUnless(importlib.util.find_spec("openpyxl"), "openpyxl not installed")
    def test_asset_matches_the_workbook(self):
        result = conv.convert_rows(conv.read_workbook(WORKBOOK))
        self.assertEqual(result.problems, [])
        expected = conv.to_tsv(result.entries, WORKBOOK.name)
        self.assertEqual(ASSET.read_text(encoding="utf-8"), expected, "Run tools/glossary/convert_uzi_glossary.py again")


if __name__ == "__main__":
    unittest.main()
