package com.kienhoang.dualsubreplay.dubbing

import android.content.Context

/** This build has no dubbing voice; the "uz" build supplies one. */
@Suppress("UNUSED_PARAMETER")
internal fun createDubbingVoice(context: Context): DubbingVoice? = null
