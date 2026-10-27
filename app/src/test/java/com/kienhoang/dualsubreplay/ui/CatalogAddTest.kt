package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogTopic
import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.addCatalogVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogAddTest {
    @Test
    fun anEmptyTopicGoesToTheExtraLessonsTopic() {
        assertEquals(DEFAULT_ADDED_TOPIC, chosenTopic("  "))
        assertEquals("Fetal exokardiografiya", chosenTopic(" Fetal exokardiografiya "))
        val sections = listOf(CatalogSection("Homila skriningi", listOf(CatalogTopic("I trimestr", emptyList()))))
        val added = addCatalogVideo(sections, "Homila skriningi", chosenTopic(""), CatalogVideo("abcdefghijk", "1st trimester fetal echo"))
        assertEquals(listOf("I trimestr", DEFAULT_ADDED_TOPIC), added.single().topics.map { it.name })
    }
}
