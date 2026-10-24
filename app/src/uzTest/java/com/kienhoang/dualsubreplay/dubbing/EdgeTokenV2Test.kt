package com.kienhoang.dualsubreplay.dubbing

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values are edge-tts's own generate_sec_ms_gec_v2() output (src/edge_tts/drm.py) for the same times. */
class EdgeTokenV2Test {
    private val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    @Test
    fun matchesEdgeTts() {
        val expected =
            mapOf(
                0L to "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
                1_700_000_000L to "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
                1_761_282_000L to "9A4816544561FD97-589929E51D91E27D030AC80394FDF0CF953C46568A95A474B8D3FDCA57D5F4A1",
                1_761_300_000L to "FB5DBA9FF3D535F8-C63AE68E039A9D5A9ADD9FA6648858E3F6A6E20D99B3E279E5440EBEA674C198",
                1_893_456_000L to "7442B6E630CDF0DE-CE82EC1509335DC856270C1EEB0F7F2575909AC26840D2C15B28A41EBB7E9CCF",
                2_000_000_000L to "2D62557F1142BC5D-FD9C30C68FF194A5EE6B45FEFC5E2BA1D1239F88A90569AB55CFFFAA7799F40E",
            )
        expected.forEach { (seconds, value) -> assertEquals("at $seconds", value, edgeSecMsGecV2(seconds, token)) }
    }

    @Test
    fun theTokenHoldsForFiveMinutes() {
        assertEquals(edgeSecMsGecV2(1_761_282_000L, token), edgeSecMsGecV2(1_761_282_299L, token))
    }
}
