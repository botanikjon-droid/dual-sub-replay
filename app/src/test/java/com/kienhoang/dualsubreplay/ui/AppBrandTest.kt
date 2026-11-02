package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBrandTest {
    @Test
    fun theUzbekBuildIsShokalonWithItsLogo() {
        val brand = appBrand("uz")
        assertEquals("SHOKALON", brand.name)
        assertEquals("Ultratovush darslarini o‘rganish platformasi", brand.tagline)
        assertTrue(brand.hasLogo)
    }

    @Test
    fun otherBuildsKeepDualSubReplay() {
        listOf("full", "fdroid").forEach { distribution ->
            val brand = appBrand(distribution)
            assertEquals("DualSub Replay", brand.name)
            assertFalse(brand.hasLogo)
        }
    }
}
