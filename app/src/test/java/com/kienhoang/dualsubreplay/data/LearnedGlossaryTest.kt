package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedGlossaryTest {
    @Test
    fun fileRoundTripsAndSkipsBrokenLines() {
        val terms =
            listOf(
                LearnedTerm(
                    "splenic window",
                    "taloq oynasi",
                    "orqa yon tomondan",
                    LearnedTermStatus.APPROVED,
                    LEARNED_SOURCE_DOCTOR,
                    3,
                    "abc",
                ),
                LearnedTerm("echogenic reflex", "exogen signal"),
            )
        assertEquals(terms, parseLearnedGlossary(formatLearnedGlossary(terms)))
        assertEquals(emptyList<LearnedTerm>(), parseLearnedGlossary("english\tuzbek\nbroken\nx\t\tn\tpending\n"))
    }

    @Test
    fun suggestionsAreAddedOnceAndKnownTermsSkipped() {
        val existing =
            listOf(LearnedTerm("Splenic-window", "taloq oynasi", status = LearnedTermStatus.APPROVED, source = LEARNED_SOURCE_DOCTOR))
        val merged =
            mergeLearnedSuggestions(
                existing,
                listOf(
                    LearnedTerm("splenic window", "taloq darchasi"),
                    LearnedTerm("echogenic reflex", "exogen signal"),
                    LearnedTerm("Echogenic reflex", "exogen aks"),
                    LearnedTerm("portal vein", "darvoza venasi"),
                ),
                videoId = "vid",
                isKnown = { it == "portal vein" },
            )
        assertEquals(2, merged.size)
        // The doctor's wording and approval are kept; it was only seen once more.
        assertEquals("taloq oynasi", merged[0].uzbek)
        assertEquals(LearnedTermStatus.APPROVED, merged[0].status)
        assertEquals(2, merged[0].seen)
        assertEquals(LearnedTerm("echogenic reflex", "exogen signal", videoId = "vid"), merged[1])
    }

    @Test
    fun doctorEditsApproveAndReviewOrderPutsPendingFirst() {
        var terms =
            listOf(
                LearnedTerm("b term", "b"),
                LearnedTerm("a term", "a", seen = 4),
                LearnedTerm("c term", "c", status = LearnedTermStatus.APPROVED),
            )
        assertEquals(listOf("a term", "b term", "c term"), learnedTermsForReview(terms).map { it.english })
        terms = upsertDoctorTerm(terms, "B-term", "bb")
        val edited = terms.first { it.key == "b term" }
        assertEquals(LearnedTerm("B-term", "bb", status = LearnedTermStatus.APPROVED, source = LEARNED_SOURCE_DOCTOR), edited)
        terms = approveLearnedTerm(terms, "a term")
        assertTrue(terms.all { it.status == LearnedTermStatus.APPROVED })
        assertEquals(2, removeLearnedTerm(terms, "A TERM").size)
    }

    @Test
    fun onlyTermsInTheTextAreSent() {
        val terms = listOf(LearnedTerm("echogenic reflex", "x"), LearnedTerm("splenic window", "y"), LearnedTerm("RI", "z"))
        assertEquals(
            listOf("echogenic reflex", "splenic window"),
            learnedTermsIn(terms, listOf("small and larger echogenic reflexes", "using the Splenic-window here")).map { it.english },
        )
    }
}
