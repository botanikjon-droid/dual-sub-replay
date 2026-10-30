package com.kienhoang.dualsubreplay.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.kienhoang.dualsubreplay.R

/*
 * The picture and colours of each SHOKALON section on the home screen. Sections are matched by words in their
 * Uzbek names, so a section the doctor renamed or added still gets a fitting picture (or the general one).
 * The pictures are original flat drawings made for SHOKALON; the neurosonography one is the doctor's own image.
 */

/** Which picture a section gets, from words in its name; "physics" (the probe) when nothing matches. */
internal fun sectionArtKey(sectionName: String): String {
    val name =
        sectionName
            .lowercase()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('`', '\'')
    return SECTION_KEYWORDS.firstOrNull { (_, words) -> words.any { it in name } }?.first ?: "physics"
}

/** Checked in order: "transkranial doppler" before the general "doppler". */
private val SECTION_KEYWORDS =
    listOf(
        "neuro" to listOf("neyro", "neuro"),
        "tcd" to listOf("transkranial"),
        "vascular" to listOf("qon tomir", "doppler", "tomir"),
        "fetal" to listOf("homila", "skrining", "akusher"),
        "gyn" to listOf("ginekolog"),
        "uro" to listOf("urolog", "buyrak"),
        "peds" to listOf("pediatr", "bolalar"),
        "heart" to listOf("yurak", "exokard", "ekokard"),
        "thyroid" to listOf("qalqonsimon"),
        "breast" to listOf("ko'krak", "sut bez"),
        "superficial" to listOf("yuzaki", "limfa"),
        "msk" to listOf("mushak", "bo'g'im", "nerv"),
        "pocus" to listOf("shoshilinch", "pocus", "tez yordam"),
        "elasto" to listOf("elastograf", "kontrast", "intervens"),
        "quality" to listOf("sifat", "kasbiy"),
        "method" to listOf("metodika", "xulosa", "protokol"),
        "abdomen" to listOf("qorin", "jigar", "abdominal"),
        "physics" to listOf("fizika", "asoslar", "apparat"),
    )

/** A section's picture and the two colours of its card (top to bottom). */
internal data class SectionArt(
    @DrawableRes val image: Int,
    val top: Color,
    val bottom: Color,
)

internal fun sectionArt(sectionName: String): SectionArt =
    when (sectionArtKey(sectionName)) {
        "neuro" -> SectionArt(R.drawable.shokalon_section_neuro, Color(0xFF6366F1), Color(0xFF312E81))
        "tcd" -> SectionArt(R.drawable.shokalon_section_tcd, Color(0xFF818CF8), Color(0xFF3730A3))
        "vascular" -> SectionArt(R.drawable.shokalon_section_vascular, Color(0xFF475569), Color(0xFF0F172A))
        "fetal" -> SectionArt(R.drawable.shokalon_section_fetal, Color(0xFFF59E0B), Color(0xFFB45309))
        "gyn" -> SectionArt(R.drawable.shokalon_section_gyn, Color(0xFFEC4899), Color(0xFF9D174D))
        "uro" -> SectionArt(R.drawable.shokalon_section_uro, Color(0xFFEF4444), Color(0xFF7F1D1D))
        "peds" -> SectionArt(R.drawable.shokalon_section_peds, Color(0xFFFB923C), Color(0xFFC2410C))
        "heart" -> SectionArt(R.drawable.shokalon_section_heart, Color(0xFFF43F5E), Color(0xFF9F1239))
        "thyroid" -> SectionArt(R.drawable.shokalon_section_thyroid, Color(0xFFA855F7), Color(0xFF6B21A8))
        "breast" -> SectionArt(R.drawable.shokalon_section_breast, Color(0xFFF472B6), Color(0xFFBE185D))
        "superficial" -> SectionArt(R.drawable.shokalon_section_superficial, Color(0xFF34D399), Color(0xFF047857))
        "msk" -> SectionArt(R.drawable.shokalon_section_msk, Color(0xFFA8A29E), Color(0xFF44403C))
        "pocus" -> SectionArt(R.drawable.shokalon_section_pocus, Color(0xFFF87171), Color(0xFFB91C1C))
        "elasto" -> SectionArt(R.drawable.shokalon_section_elasto, Color(0xFF2DD4BF), Color(0xFF0F766E))
        "quality" -> SectionArt(R.drawable.shokalon_section_quality, Color(0xFFFACC15), Color(0xFFA16207))
        "method" -> SectionArt(R.drawable.shokalon_section_method, Color(0xFF38BDF8), Color(0xFF0369A1))
        "abdomen" -> SectionArt(R.drawable.shokalon_section_abdomen, Color(0xFFF97316), Color(0xFF9A3412))
        else -> SectionArt(R.drawable.shokalon_section_physics, Color(0xFF3B82F6), Color(0xFF1E3A8A))
    }
