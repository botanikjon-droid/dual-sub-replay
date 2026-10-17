package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.BuildConfig

/**
 * The app's name and one-line purpose as shown inside the app. The Uzbek build is SHOKALON, an ultrasound
 * lesson platform; its debug resources carry the portrait logo (src/uzDebug/res/drawable-nodpi/drawer_logo.png).
 */
internal data class AppBrand(
    val name: String,
    val tagline: String,
    val hasLogo: Boolean,
)

internal fun appBrand(distribution: String = BuildConfig.DISTRIBUTION): AppBrand =
    if (distribution == "uz") {
        AppBrand(name = "SHOKALON", tagline = "Ultratovush darslarini o‘rganish platformasi", hasLogo = true)
    } else {
        AppBrand(name = "DualSub Replay", tagline = "Learn languages with YouTube", hasLogo = false)
    }
