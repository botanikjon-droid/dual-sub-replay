package com.kienhoang.dualsubreplay.translation

/** JNI bridge to bergamot_jni.cpp. Handles are owned by [OnDeviceTranslator]. */
internal object BergamotNative {
    init {
        System.loadLibrary("dualsub_bergamot")
    }

    /** Loads one model from a Marian YAML config and returns its handle. */
    external fun loadModel(config: String): Long

    external fun translate(
        model: Long,
        texts: Array<String>,
    ): Array<String>

    /** Translates with [first] (source to English) and then [second] (English to target). */
    external fun pivot(
        first: Long,
        second: Long,
        texts: Array<String>,
    ): Array<String>

    external fun releaseModel(model: Long)
}
