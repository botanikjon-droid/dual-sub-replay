package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    @Test
    fun installedBuildComesFromTheVersionName() {
        assertEquals(31, installedUzBuild("1.0.4-uz-build31"))
        assertNull(installedUzBuild("1.0.4"))
    }

    @Test
    fun releaseBuildComesFromTheNotesCiWrites() {
        val json =
            """{"body":"Uzbek build of x at 38831af (run 32). Download it.","published_at":"2026-10-14T01:30:12Z",""" +
                """"assets":[{"name":"DualSub-Replay-uz.apk","browser_download_url":"https://example.com/a.apk"}]}"""
        assertEquals(UzRelease(32, "https://example.com/a.apk", "2026-10-14 01:30"), parseUzRelease(json))
        assertEquals(UZ_APK_URL, parseUzRelease("""{"body":"no run"}""").apkUrl)
        assertNull(parseUzRelease("""{"body":"no run"}""").build)
    }

    @Test
    fun verdictComparesBuilds() {
        assertEquals(UpdateVerdict.NEWER, updateVerdict(31, 32))
        assertEquals(UpdateVerdict.CURRENT, updateVerdict(32, 32))
        assertEquals(UpdateVerdict.UNKNOWN, updateVerdict(null, 32))
    }
}
