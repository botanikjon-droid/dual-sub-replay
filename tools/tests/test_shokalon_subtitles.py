"""Tests for the SHOKALON subtitle tools in tools/shokalon (stage 6 ZIP check, stage 8 translation check)."""

import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "shokalon"))
import check_subtitle_zip as zipcheck  # noqa: E402
import check_translation as trcheck  # noqa: E402
import package_translations as packer  # noqa: E402
import srt  # noqa: E402

GOOD = "1\n00:00:01,000 --> 00:00:03,500\nThe portal vein lies here.\n\n2\n00:00:04,000 --> 00:00:06,000\nIt measures 12 mm.\n"
IDS = {
    "V001": {"video_id": "x6hXhLNc-jU", "code": "03.10.01", "vid": "V001"},
    "V004": {"video_id": "U50O8_fsemY", "code": "03.11.06", "vid": "V004"},
    "V005": {"video_id": "eoze_8nUjfA", "code": "03.10.07", "vid": "V005"},
}
MANIFEST = (
    "video_id\tvid\tcode\tstatus\tfile\tlanguage\tauto\tcues\ttitle\tnote\n"
    "x6hXhLNc-jU\tV001\t03.10.01\tbor\tSHK_031001_V001_ORIGINAL.srt\ten\t\t2\tLiver\t\n"
    "U50O8_fsemY\tV004\t03.11.06\tbor\tSHK_031106_V004_ASR-EN.srt\ten\tha\t2\tCBD\t\n"
    "eoze_8nUjfA\tV005\t03.10.07\tsubtitr yo‘q\t\t\t\t\tSWE\tNo caption tracks\n"
)


def make_zip(files, manifest=MANIFEST):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, text in files.items():
            archive.writestr(name, text)
        if manifest is not None:
            archive.writestr("manifest.tsv", manifest)
    handle = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
    handle.write(buffer.getvalue())
    handle.close()
    return Path(handle.name)


class SrtTest(unittest.TestCase):
    def test_names_follow_the_shokalon_rule(self):
        name = srt.parse_name("SHK_031106_V004_UZ-LATIN-V2.srt")
        self.assertEqual(("031106", "V004", "UZ-LATIN-V2", "03.11.06"), (name.code, name.vid, name.variant, name.dotted_code))
        self.assertEqual("YT-x6hXhLNc-jU", srt.parse_name("SHK_000000_YT-x6hXhLNc-jU_ASR-EN.srt").vid)
        self.assertIsNone(srt.parse_name("SHK_3106_V004_ORIGINAL.srt"))
        self.assertIsNone(srt.parse_name("lesson.srt"))

    def test_cues_keep_index_timing_and_text(self):
        cues = srt.parse_srt("﻿" + GOOD.replace("\n", "\r\n"))
        self.assertEqual(2, len(cues))
        self.assertEqual(("1", "00:00:01,000", "00:00:03,500", 1000, 3500), (cues[0].index, cues[0].start, cues[0].end, cues[0].start_ms, cues[0].end_ms))
        self.assertEqual("It measures 12 mm.", cues[1].text)
        with self.assertRaises(srt.SrtError):
            srt.parse_srt("1\nno timing here\ntext\n")


class SubtitleZipTest(unittest.TestCase):
    def test_a_clean_archive_matches_the_catalog(self):
        path = make_zip({"03_SUBTITLES_ORIGINAL/SHK_031001_V001_ORIGINAL.srt": GOOD, "03_SUBTITLES_ORIGINAL/SHK_031106_V004_ASR-EN.srt": GOOD})
        result = zipcheck.check_zip(path, IDS)
        states = {row["vid"]: row["state"] for row in result["lectures"]}
        self.assertEqual({"V001": "fayl bor", "V004": "fayl bor", "V005": "subtitr yo‘q"}, states)
        with tempfile.TemporaryDirectory() as out:
            self.assertEqual(0, zipcheck.write_report(result, Path(out), path.name))
            self.assertIn("| Darslar: subtitr yo‘q | 1 |", (Path(out) / "report.md").read_text(encoding="utf-8"))

    def test_bad_names_wrong_codes_duplicates_and_broken_files_are_errors(self):
        path = make_zip(
            {
                "03_SUBTITLES_ORIGINAL/SHK_031001_V001_ORIGINAL.srt": GOOD,
                "03_SUBTITLES_ORIGINAL/SHK_031001_V001_ASR-EN.srt": GOOD,
                "03_SUBTITLES_ORIGINAL/SHK_039999_V004_ORIGINAL.srt": GOOD,
                "03_SUBTITLES_ORIGINAL/SHK_031001_V999_ORIGINAL.srt": GOOD,
                "03_SUBTITLES_ORIGINAL/lesson.srt": GOOD,
                "03_SUBTITLES_ORIGINAL/SHK_031007_V005_ORIGINAL.srt": "1\nbroken\n",
                "notes.txt": "x",
            },
        )
        result = zipcheck.check_zip(path, IDS)
        errors = {r.name: "; ".join(r.errors) for r in result["files"] if r.errors}
        self.assertIn("takroriy", errors["SHK_031001_V001_ORIGINAL.srt"])
        self.assertIn("mavzu kodi 03.99.99, katalogda 03.11.06", errors["SHK_039999_V004_ORIGINAL.srt"])
        self.assertIn("katalogda yo‘q", errors["SHK_031001_V999_ORIGINAL.srt"])
        self.assertIn("qoidasiga mos emas", errors["lesson.srt"])
        self.assertIn("SRT buzilgan", errors["SHK_031007_V005_ORIGINAL.srt"])
        self.assertEqual(["notes.txt"], result["other"])
        with tempfile.TemporaryDirectory() as out:
            self.assertGreater(zipcheck.write_report(result, Path(out), path.name), 0)

    def test_a_non_english_transcript_is_flagged_for_review(self):
        path = make_zip({"03_SUBTITLES_ORIGINAL/SHK_031001_V001_ASR-HI.srt": GOOD, "03_SUBTITLES_ORIGINAL/SHK_031106_V004_ASR-EN.srt": GOOD})
        states = {row["vid"]: row["state"] for row in zipcheck.check_zip(path, IDS)["lectures"]}
        self.assertEqual("fayl bor, inglizcha emas", states["V001"])
        self.assertEqual("fayl bor", states["V004"])

    def test_a_missing_manifest_and_a_manifest_file_absent_from_the_zip_are_reported(self):
        path = make_zip({"03_SUBTITLES_ORIGINAL/SHK_031001_V001_ORIGINAL.srt": GOOD})
        states = {row["vid"]: row["state"] for row in zipcheck.check_zip(path, IDS)["lectures"]}
        self.assertEqual("manifestda bor, ZIPda yo‘q", states["V004"])
        no_manifest = zipcheck.check_zip(make_zip({}, manifest=None), IDS)
        with tempfile.TemporaryDirectory() as out:
            self.assertEqual(1, zipcheck.write_report(no_manifest, Path(out), "x.zip"))

    def test_the_shipped_id_table_has_every_catalog_video_once(self):
        ids = zipcheck.read_ids()
        self.assertGreaterEqual(len(ids), 481)
        self.assertEqual(len(ids), len({row["video_id"] for row in ids.values()}))


UZ_GOOD = "1\n00:00:01,000 --> 00:00:03,500\nDarvoza venasi shu yerda joylashgan.\n\n2\n00:00:04,000 --> 00:00:06,000\nUning o‘lchami 12 mm.\n"


def write(folder, name, text):
    path = Path(folder) / name
    path.write_text(text, encoding="utf-8")
    return path


class TranslationCheckTest(unittest.TestCase):
    glossary = trcheck.read_glossary()

    def check(self, source, target, names=("SHK_031004_V009_ORIGINAL.srt", "SHK_031004_V009_UZ-LATIN.srt")):
        with tempfile.TemporaryDirectory() as folder:
            return trcheck.check_pair(write(folder, names[0], source), write(folder, names[1], target), self.glossary)

    def test_a_faithful_translation_passes(self):
        report = self.check(GOOD, UZ_GOOD)
        self.assertEqual([], report.errors)
        self.assertEqual([], report.warnings)

    def test_timing_count_empty_cyrillic_and_numbers_are_errors(self):
        shifted = UZ_GOOD.replace("00:00:04,000", "00:00:04,100")
        self.assertIn("vaqt o‘zgargan", " ".join(self.check(GOOD, shifted).errors))
        one_cue = UZ_GOOD.split("\n\n")[0] + "\n"
        self.assertIn("bloklar soni: asl 2, tarjima 1", self.check(GOOD, one_cue).errors)
        self.assertIn("tarjima bo‘sh", " ".join(self.check(GOOD, UZ_GOOD.replace("Uning o‘lchami 12 mm.", "")).errors))
        self.assertIn("kirill", " ".join(self.check(GOOD, UZ_GOOD.replace("joylashgan", "жойлашган")).errors))
        self.assertIn("son tushib qolgan: 12", " ".join(self.check(GOOD, UZ_GOOD.replace("12 mm", "o‘n ikki mm")).errors))

    def test_units_english_glossary_okina_and_line_length_are_warnings(self):
        report = self.check(GOOD, UZ_GOOD.replace("12 mm", "12").replace("Darvoza venasi", "The portal vein"))
        warnings = " ".join(report.warnings)
        self.assertIn("birlik tushib qolgan: mm", warnings)
        self.assertIn("inglizcha so‘z qolgan: The", warnings)
        self.assertIn("lug‘at atamasi “darvoza venasi” yo‘q", warnings)
        mixed = self.check(GOOD, UZ_GOOD.replace("Uning o‘lchami", "Uning o'lchami o‘lchovi"))
        self.assertTrue(any("o‘/g‘" in item for item in mixed.warnings))
        long = self.check(GOOD, UZ_GOOD.replace("joylashgan.", "joylashgan va u juda uzun bir qatorda yozilgan gap."))
        self.assertIn("qator", " ".join(long.warnings))
        self.assertEqual([], long.errors)

    def test_notes_untranslated_and_repeated_cues_are_caught(self):
        noted = self.check(GOOD, UZ_GOOD.replace("joylashgan.", "joylashgan [tekshirilsin]."))
        self.assertIn("izoh yoki tahrir belgisi", " ".join(noted.errors))
        self.assertIn("izoh yoki tahrir belgisi", " ".join(self.check(GOOD, UZ_GOOD.replace("joylashgan.", "joylashgan (?)")).errors))
        english = self.check(GOOD, UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "The portal vein lies here."))
        self.assertIn("tarjima qilinmagan", " ".join(english.errors))
        repeated = self.check(GOOD, UZ_GOOD.replace("Uning o‘lchami 12 mm.", "Darvoza venasi shu yerda joylashgan."))
        self.assertIn("takrorlangan", " ".join(repeated.warnings))
        # A source tag such as [Music] may stay a tag in the translation.
        tagged = self.check(GOOD.replace("The portal vein lies here.", "[Music]"), UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "[musiqa]"))
        self.assertEqual([], tagged.errors)

    def test_a_batch_is_zipped_with_only_clean_files_and_a_report(self):
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as uz, tempfile.TemporaryDirectory() as out:
            write(src, "SHK_010101_V014_ASR-EN.srt", GOOD)
            write(src, "SHK_010102_V015_ASR-EN.srt", GOOD)
            write(src, "SHK_010103_V016_ORIGINAL.srt", GOOD)
            write(src, "SHK_020701_V100_ASR-EN.srt", GOOD)
            write(uz, "SHK_010101_V014_UZ-LATIN.srt", UZ_GOOD)
            write(uz, "SHK_010102_V015_UZ-LATIN.srt", UZ_GOOD.replace("Uning o‘lchami 12 mm.", ""))
            notes = write(out, "NOTES.tsv", "vid\tblok\tasl\ttarjima\tsabab\tishonch\nV014\t1\tportal\tdarvoza\tASR\tYuqori\n")
            result = packer.package(Path(src), Path(uz), "01", Path(out) / "pkg", notes)
            self.assertEqual((3, 2, 1, 1), (result["sources"], result["translated"], result["good"], result["bad"]))
            self.assertEqual(["SHK_010103_V016_ORIGINAL.srt"], result["missing"])
            with zipfile.ZipFile(result["zip"]) as archive:
                self.assertEqual(["04_SUBTITLES_TRANSLATED/SHK_010101_V014_UZ-LATIN.srt", "HISOBOT.md"], archive.namelist())
            report = (Path(out) / "pkg" / "HISOBOT.md").read_text(encoding="utf-8")
            self.assertIn("| Hali tarjima qilinmagan | 1 |", report)
            self.assertIn("tarjima bo‘sh", report)
            self.assertIn("| V014 | 1 | portal | darvoza | ASR | Yuqori |", report)

    def test_a_section_number_read_as_zero_point_is_accepted(self):
        source = GOOD.replace("The portal vein lies here.", "section 6b 0.2 rules of the road")
        target = UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "6b.2-qism — yo‘l qoidalari")
        self.assertEqual([], self.check(source, target).errors)

    def test_a_section_letter_a_heard_as_eight_is_accepted(self):
        source = GOOD.replace("The portal vein lies here.", "unit 6-8 is section 6-8.6")
        target = UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "6A bo‘limi — 6A.6-qism")
        self.assertEqual([], self.check(source, target).errors)
        self.assertTrue(self.check(source, UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "Ammo bo‘lim")).errors)
        source = GOOD.replace("The portal vein lies here.", "refer back to section 128.1")
        target = UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "12A.1-qismga qayting")
        self.assertEqual([], self.check(source, target).errors)

    def test_4d_heard_as_forty_is_accepted(self):
        source = GOOD.replace("The portal vein lies here.", "for 40 requires even more processing")
        target = UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "4D esa yana ko‘proq ishlov talab qiladi")
        self.assertEqual([], self.check(source, target).errors)

    def test_uzbek_words_with_okina_are_not_english(self):
        target = UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "Darvoza venasi to‘lqin bilan ko‘rinadi.")
        self.assertEqual([], [w for w in self.check(GOOD, target).warnings if "inglizcha" in w])

    def test_a_number_split_by_auto_captions_may_be_written_whole(self):
        source = GOOD.replace("It measures 12 mm.", "The speed is 15 40 meters per second.")
        self.assertEqual([], self.check(source, UZ_GOOD.replace("Uning o‘lchami 12 mm.", "Tezlik 1540 metr/soniya.")).errors)

    def test_a_decimal_whose_zero_point_auto_captions_dropped_may_be_restored(self):
        source = GOOD.replace("12 mm", "05 mm to 5 mm")
        self.assertEqual([], self.check(source, UZ_GOOD.replace("12 mm", "0,05 mm dan 0,5 mm gacha")).errors)

    def test_decimal_comma_counts_as_the_same_number(self):
        source = GOOD.replace("12 mm", "3.5 mm")
        self.assertEqual([], self.check(source, UZ_GOOD.replace("12 mm", "3,5 mm")).errors)

    def test_a_translation_of_another_lecture_is_an_error(self):
        report = self.check(GOOD, UZ_GOOD, ("SHK_031004_V009_ORIGINAL.srt", "SHK_031004_V010_UZ-LATIN.srt"))
        self.assertIn("Video ID", " ".join(report.errors))

    def test_folders_pair_each_source_with_its_newest_translation(self):
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as uz:
            write(src, "SHK_031004_V009_ASR-EN.srt", GOOD)
            write(uz, "SHK_031004_V009_UZ-LATIN.srt", "broken")
            write(uz, "SHK_031004_V009_UZ-LATIN-V2.srt", UZ_GOOD)
            write(uz, "SHK_031005_V010_UZ-LATIN.srt", UZ_GOOD)
            matched, orphans = trcheck.pairs(Path(src), Path(uz))
            self.assertEqual(["SHK_031004_V009_UZ-LATIN-V2.srt"], [target.name for _, target in matched])
            self.assertEqual(["SHK_031005_V010_UZ-LATIN.srt"], orphans)

    def test_the_longest_glossary_term_wins_and_shorter_ones_inside_it_are_not_checked(self):
        found = [term.english[0] for term in trcheck.source_terms(
            "The common bile duct and the bile ducts; the interventricular septum; germinal matrix-intraventricular hemorrhage.",
            self.glossary,
        )]
        self.assertEqual(
            ["common bile duct", "bile duct", "interventricular septum", "germinal matrix-intraventricular hemorrhage"], found
        )
        source = GOOD.replace("The portal vein lies here.", "The common bile duct lies here.")
        target = UZ_GOOD.replace("Darvoza venasi shu yerda joylashgan.", "Umumiy o‘t yo‘li shu yerda.")
        self.assertEqual([], self.check(source, target).warnings)
        wrong = self.check(source.replace("common bile duct", "bile duct"), target.replace("Umumiy o‘t yo‘li", "Xoledox"))
        self.assertIn("lug‘at atamasi “o‘t yo‘li” yo‘q (bile duct)", " ".join(wrong.warnings))

    def test_the_doctors_fixes_reach_the_checker(self):
        uzbek = {term.english[0]: term.uzbek for term in self.glossary}
        self.assertEqual("appenditsit", uzbek["appendicitis"])
        self.assertEqual("appendiks", uzbek["appendix"])
        self.assertEqual("limfadenopatiya", uzbek["lymphadenopathy"])
        self.assertEqual("exogenlik", uzbek["echogenicity"])
        self.assertEqual("yaxshi sifatli o‘sma", uzbek["benign tumor"])
        self.assertEqual("fazoviy ajrata olish qobiliyati", uzbek["spatial resolution"])

    def test_the_shipped_glossary_skips_general_words(self):
        english = {term.english[0] for term in self.glossary}
        self.assertIn("portal vein", english)
        self.assertNotIn("liver", english)


if __name__ == "__main__":
    unittest.main()
