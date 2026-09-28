package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BergamotCatalogTest {
    @Test fun mapsAppLanguageCodesToMozillaCodes() {
        assertEquals("zh-Hans", bergamotLanguageCode("zh"))
        assertEquals("zh-Hans", bergamotLanguageCode("zh-TW"))
        assertEquals("nb", bergamotLanguageCode("no"))
        assertEquals("he", bergamotLanguageCode("iw"))
        assertEquals("vi", bergamotLanguageCode("vi"))
    }

    @Test fun routesThroughEnglishOnlyWhenNeitherSideIsEnglish() {
        assertTrue(bergamotRoute("vi", "vi").isEmpty())
        assertEquals(listOf(BergamotPair("en", "vi")), bergamotRoute("en", "vi"))
        assertEquals(listOf(BergamotPair("ja", "en")), bergamotRoute("ja", "en"))
        assertEquals(listOf(BergamotPair("ja", "en"), BergamotPair("en", "vi")), bergamotRoute("ja", "vi"))
    }

    @Test fun picksNewestCompleteReleaseModelPerPair() {
        val catalog =
            parseBergamotCatalog(
                """
                {"data": [
                  ${record("en", "vi", "1.0", "model", "old-model.bin")},
                  ${record("en", "vi", "1.0", "lex", "old-lex.bin")},
                  ${record("en", "vi", "1.0", "vocab", "old-vocab.spm")},
                  ${record("en", "vi", "2.0", "model", "model.envi.bin")},
                  ${record("en", "vi", "2.0", "lex", "lex.envi.bin")},
                  ${record("en", "vi", "2.0", "vocab", "vocab.envi.spm")},
                  ${record("en", "vi", "3.0", "model", "incomplete.bin")},
                  ${record("en", "vi", "9.0", "model", "nightly.bin", "env.channel == 'nightly'")},
                  ${record("en", "vi", "9.0", "lex", "nightly-lex.bin", "env.channel == 'nightly'")},
                  ${record("en", "vi", "9.0", "vocab", "nightly.spm", "env.channel == 'nightly'")}
                ]}
                """.trimIndent(),
                attachmentBaseUrl = "https://cdn.example/",
            )

        val model = catalog.getValue(BergamotPair("en", "vi"))
        assertEquals("2.0", model.version)
        assertEquals("model.envi.bin", model.model.name)
        assertEquals("https://cdn.example/files/model.envi.bin", model.model.url)
        assertEquals("hash-model.envi.bin", model.model.sha256)
        assertEquals(3, model.files.size)
    }

    @Test fun supportsSeparateSourceAndTargetVocabularies() {
        val catalog =
            parseBergamotCatalog(
                """
                {"data": [
                  ${record("en", "ja", "1.0", "model", "model.enja.bin")},
                  ${record("en", "ja", "1.0", "lex", "lex.enja.bin")},
                  ${record("en", "ja", "1.0", "srcvocab", "srcvocab.enja.spm")},
                  ${record("en", "ja", "1.0", "trgvocab", "trgvocab.enja.spm")}
                ]}
                """.trimIndent(),
            )

        val model = catalog.getValue(BergamotPair("en", "ja"))
        assertEquals("srcvocab.enja.spm", model.sourceVocabulary.name)
        assertEquals("trgvocab.enja.spm", model.targetVocabulary.name)
        assertEquals(4, model.files.size)
        assertNull(catalog[BergamotPair("ja", "en")])
    }

    @Test fun ordersVersionsWithPreReleasesFirst() {
        assertTrue(compareModelVersions("2.1", "2.0") > 0)
        assertTrue(compareModelVersions("1.10", "1.9") > 0)
        assertTrue(compareModelVersions("1.0a1", "1.0") < 0)
        assertEquals(0, compareModelVersions("1.0", "1.0"))
    }

    @Test fun quotesModelPathsInConfig() {
        val config = bergamotModelConfig("/m/model.bin", "/m/lex.bin", "/m/src.spm", "/m/trg \"x\".spm")
        assertTrue(config.contains("models: [\"/m/model.bin\"]"))
        assertTrue(config.contains("vocabs: [\"/m/src.spm\", \"/m/trg \\\"x\\\".spm\"]"))
        assertTrue(config.contains("shortlist: [\"/m/lex.bin\", false]"))
        assertTrue(config.contains("gemm-precision: int8shiftAlphaAll"))
    }

    private fun record(
        from: String,
        to: String,
        version: String,
        type: String,
        name: String,
        filter: String = "",
    ) = """
        {"fromLang": "$from", "toLang": "$to", "version": "$version", "fileType": "$type", "name": "$name",
         "filter_expression": "$filter",
         "attachment": {"filename": "$name", "location": "files/$name", "hash": "hash-$name", "size": 10}}
        """.trimIndent()
}
