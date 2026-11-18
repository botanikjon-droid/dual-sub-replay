// SPDX-License-Identifier: LGPL-3.0-only
// This file only is a Kotlin port of src/edge_tts/drm.py from edge-tts (https://github.com/rany2/edge-tts,
// copyright rany2 and contributors), licensed under the GNU LGPL v3 like its source; see THIRD_PARTY_NOTICES.md.
package com.kienhoang.dualsubreplay.dubbing

/*
 * The v2 Sec-MS-GEC token of Microsoft Edge's Read Aloud voices (Sardor, Madina), "<16 hex>-<64 hex>":
 * a keyed SHA-256 of the 5-minute Windows file time and the client token, followed by Microsoft's CS64
 * checksum of the same text. Microsoft stopped accepting the v1 token (a plain SHA-256) in October 2026.
 * All arithmetic is modulo 2^32, which Kotlin's Int gives directly. Checked against edge-tts's own output
 * in EdgeTokenV2Test.
 */

internal const val EDGE_SEC_MS_GEC_VERSION_PREFIX = "2-"

private val SHA256_INITIAL =
    intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19,
    )
private val SHA256_STATE =
    intArrayOf(
        0xFD9FBEA2.toInt(),
        0x91243CE7.toInt(),
        0xBE499094.toInt(),
        0xCD2E1E0A.toInt(),
        0x6C006F49,
        0x04BD35AB,
        0xFEB0A7E4.toInt(),
        0x03EFFBD2,
    )
private val SHA256_SCHEDULE =
    longArrayOf(
        0xEB24BF9E,
        0x1558691F,
        0xA0C09EE2,
        0x0CFA32BA,
        0xA622EE8C,
        0xD1DD9491,
        0xC0DD25A0,
        0xF9E70AB6,
        0xF0C31573,
        0x7058CE4E,
        0x7188E606,
        0x00E5AB33,
        0xD258B060,
        0x72766564,
    ).map(Long::toInt).toIntArray()
private val SHA256_ROUND_CONSTANTS =
    longArrayOf(
        0x428A2F98,
        0x71374491,
        0xB5C0FBCF,
        0xE9B5DBA5,
        0x3956C25B,
        0x59F111F1,
        0x923F82A4,
        0xAB1C5ED5,
        0xD807AA98,
        0x12835B01,
        0x243185BE,
        0x550C7DC3,
        0x72BE5D74,
        0x80DEB1FE,
        0x9BDC06A7,
        0xC19BF174,
        0xE49B69C1,
        0xEFBE4786,
        0x0FC19DC6,
        0x240CA1CC,
        0x2DE92C6F,
        0x4A7484AA,
        0x5CB0A9DC,
        0x76F988DA,
        0x983E5152,
        0xA831C66D,
        0xB00327C8,
        0xBF597FC7,
        0xC6E00BF3,
        0xD5A79147,
        0x06CA6351,
        0x14292967,
        0x27B70A85,
        0x2E1B2138,
        0x4D2C6DFC,
        0x53380D13,
        0x650A7354,
        0x766A0ABB,
        0x81C2C92E,
        0x92722C85,
        0xA2BFE8A1,
        0xA81A664B,
        0xC24B8B70,
        0xC76C51A3,
        0xD192E819,
        0xD6990624,
        0xF40E3585,
        0x106AA070,
        0x19A4C116,
        0x1E376C08,
        0x2748774C,
        0x34B0BCB5,
        0x391C0CB3,
        0x4ED8AA4A,
        0x5B9CCA4F,
        0x682E6FF3,
        0x748F82EE,
        0x78A5636F,
        0x84C87814,
        0x8CC70208,
        0x90BEFFFA,
        0xA4506CEB,
        0xBEF9A3F7,
        0xC67178F2,
    ).map(Long::toInt).toIntArray()
private const val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
private val SHA256_BLOCK_CONSTANTS = SHA256_ROUND_CONSTANTS.copyOf().also { it[14] = SHA256_ADJUSTMENT }
private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0

private val CS64_COEFFICIENTS =
    longArrayOf(
        0xE82E91B1,
        0x44DE24C6,
        0x3BEB65BC,
        0xC89C5933,
        0x8D1EC211,
        0x314EF5D5,
        0xE92491B1,
        0xF0A5B9D5,
        0x06064CF3,
        0xDA79FA60,
        0xBED20560,
        0x89F9C9F8,
        0x35FA38C6,
        0xCB49E3C6,
    ).map(Long::toInt).toIntArray()
private val CS64_OFFSETS =
    longArrayOf(
        0xB32700D0,
        0x88DFF51D,
        0x29514038,
        0xB328CBDF,
        0xD405B4D5,
        0x26E55F3C,
        0x607801C6,
        0x82B2C1FB,
        0xFA51A3DA,
        0x207CC464,
        0x3C3C74F3,
        0xD2D14B61,
        0xBB04A2C1,
        0xC5626167,
    ).map(Long::toInt).toIntArray()
private const val CS64_FACTOR = 0x82893933.toInt()
private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
private const val CS64_ADJUSTMENT1 = 0x0B1A0000

private fun rol16(value: Int): Int = (value shl 16) or (value ushr 16)

private fun sigma0(x: Int): Int = (x ushr 7) xor (x shl 25) xor (x ushr 18) xor (x shl 14) xor (x ushr 3)

private fun sigma1(x: Int): Int = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)

/** Standard SHA-256 rounds [firstRound]..63 on [state]; returns the working registers (not yet added). */
private fun sha256Rounds(
    state: IntArray,
    words: IntArray,
    firstRound: Int,
): IntArray {
    val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    var f = state[5]
    var g = state[6]
    var h = state[7]
    for (index in firstRound until 64) {
        val bigSigma1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
        val t1 = h + bigSigma1 + (g xor (e and (f xor g))) + constants[index] + words[index]
        val bigSigma0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
        val t2 = bigSigma0 + ((a and b) or (c and (a or b)))
        h = g
        g = f
        f = e
        e = d + t1
        d = c
        c = b
        b = a
        a = t1 + t2
    }
    return intArrayOf(a, b, c, d, e, f, g, h)
}

private fun expandSchedule(
    words: IntArray,
    firstWord: Int,
) {
    for (index in firstWord until 64) {
        words[index] = sigma1(words[index - 2]) + words[index - 7] + sigma0(words[index - 15]) + words[index - 16]
    }
}

private fun bigEndianInt(
    data: ByteArray,
    from: Int,
    to: Int,
): Int {
    var value = 0
    for (index in from until to) value = (value shl 8) or (data[index].toInt() and 0xFF)
    return value
}

/** The state after the hidden key and the first seven bytes of [data]. */
private fun sha256Init(data: ByteArray): IntArray {
    val words = IntArray(64)
    words[14] = bigEndianInt(data, 0, 3)
    words[15] = bigEndianInt(data, 3, 7)
    for (index in 16 until 30) {
        var value = SHA256_SCHEDULE[index - 16]
        if (index - 2 >= 14) {
            val s1 = sigma1(words[index - 2])
            value += if (index == 16) s1 xor SHA256_SIGMA1_ADJUSTMENT else s1
        }
        if (index - 7 >= 14) value += words[index - 7]
        if (index - 15 >= 14) {
            val s0 = sigma0(words[index - 15])
            value += if (index == 29) s0 xor SHA256_SIGMA0_ADJUSTMENT else s0
        }
        words[index] = value
    }
    words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
    expandSchedule(words, 31)
    val working = sha256Rounds(SHA256_STATE, words, 14)
    return IntArray(8) { SHA256_INITIAL[it] + working[it] }
}

private const val HIDDEN_KEY_BYTES = 57
private const val BLOCK_BYTES = 64

/** The keyed SHA-256 of [data] (at least seven bytes), as 32 bytes. */
private fun sha256Digest(data: ByteArray): ByteArray {
    val length = HIDDEN_KEY_BYTES + data.size
    val zeros = Math.floorMod(55 - length, BLOCK_BYTES)
    val padded = ByteArray(data.size - 7 + 1 + zeros + 8)
    data.copyInto(padded, 0, 7)
    padded[data.size - 7] = 0x80.toByte()
    val bits = length.toLong() * 8
    for (index in 0 until 8) padded[padded.size - 1 - index] = (bits ushr (8 * index)).toByte()
    var state = sha256Init(data)
    for (offset in padded.indices step BLOCK_BYTES) {
        val words = IntArray(64)
        for (index in 0 until 16) words[index] = bigEndianInt(padded, offset + index * 4, offset + index * 4 + 4)
        expandSchedule(words, 16)
        val working = sha256Rounds(state, words, 0)
        state = IntArray(8) { state[it] + working[it] }
    }
    val digest = ByteArray(32)
    for (index in 0 until 8) for (shift in 0 until 4) digest[index * 4 + shift] = (state[index] ushr (24 - 8 * shift)).toByte()
    return digest
}

private fun littleEndianInt(
    data: ByteArray,
    offset: Int,
): Int {
    var value = 0
    for (index in 3 downTo 0) value = (value shl 8) or (data.getOrElse(offset + index) { 0 }.toInt() and 0xFF)
    return value
}

/** Little-endian words of a zero byte followed by [data], zero-padded to an even count. */
private fun cs64Words(data: ByteArray): IntArray {
    val bytes = byteArrayOf(0) + data
    var count = (bytes.size + 3) / 4
    if (count % 2 == 1) count += 1
    return IntArray(count) { littleEndianInt(bytes, it * 4) }
}

/** Microsoft's CS64 checksum of [data], keyed by the first eight bytes of its digest. */
@Suppress("MagicNumber")
private fun cs64Digest(
    data: ByteArray,
    digest: ByteArray,
): Pair<Int, Int> {
    val keyA = littleEndianInt(digest, 0) or 1
    val keyB = littleEndianInt(digest, 4) or 1
    val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
    val terms = CS64_COEFFICIENTS.mapIndexed { index, coefficient -> coefficient * factors[index % 2] }.toMutableList()
    val offsets = CS64_OFFSETS.toMutableList()
    cs64Words(data).forEachIndexed { index, word ->
        var term = word * (if (index % 2 == 0) keyA else keyB)
        var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
        if (index == 0) {
            term += CS64_ADJUSTMENT0 * factors[0]
            offset += CS64_ADJUSTMENT1
        }
        terms += term
        offsets += offset
    }
    var swapAcc = 0
    var swapLast = 0
    var state = 0
    var acc = 0
    for (index in terms.indices step 2) {
        val x = terms[index]
        val y = terms[index + 1]
        var t = rol16(offsets[index] + x)
        var q = t * 0x76F50F34
        swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
        t = rol16(offsets[index + 1] + y)
        q = t * 0x1881CE3C
        swapLast = rol16(q) * 0x35BD1EC9 + q
        swapAcc += swapLast

        state = state * keyA + x
        state = rol16(state) * 0xCF98B111.toInt()
        state = rol16(state) * 0x87085B9F.toInt()
        state = rol16(state) * 0x12CEB96D
        state = rol16(state) * 0x257E1D83
        acc += state
        t = state * keyB + y
        t = rol16(t) * 0xA27416F5.toInt()
        t = rol16(t) * 0xD38396FF.toInt()
        state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
        acc += state
    }
    return (swapLast xor state) to (swapAcc xor acc)
}

private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
private const val TOKEN_WINDOW_SECONDS = 300L
private const val FILE_TIME_UNITS_PER_SECOND = 10_000_000L

private fun hex(value: Int): String = "%08X".format(value)

private fun littleEndianHex(value: Int): String = hex(Integer.reverseBytes(value))

/** The v2 Sec-MS-GEC token for [unixSeconds] (already corrected for clock skew) and [clientToken]. */
internal fun edgeSecMsGecV2(
    unixSeconds: Long,
    clientToken: String,
): String {
    var seconds = unixSeconds + WINDOWS_EPOCH_SECONDS
    seconds -= seconds % TOKEN_WINDOW_SECONDS
    val data = "${seconds * FILE_TIME_UNITS_PER_SECOND}$clientToken".toByteArray(Charsets.US_ASCII)
    val digest = sha256Digest(data)
    val (first, second) = cs64Digest(data, digest)
    return littleEndianHex(first) + littleEndianHex(second) + "-" + digest.joinToString("") { "%02X".format(it) }
}
