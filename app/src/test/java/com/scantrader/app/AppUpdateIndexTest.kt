package com.scantrader.app

import com.scantrader.app.update.AppUpdateIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateIndexTest {
    private val sha = "ab".repeat(32)
    private val apk = "https://github.com/Vala97/ScanTrader/releases/download/app-update/ScanTrader.apk"

    private fun json(
        versionCode: String = "2",
        versionName: String = "\"1.1\"",
        url: String = "\"$apk\"",
        hash: String = "\"$sha\"",
        notes: String = "\"Note in italiano\"",
        minSdk: String? = null,
    ): String {
        val extra = minSdk?.let { ""","minSdk":$it""" }.orEmpty()
        return """
            {
              "versionCode": $versionCode,
              "versionName": $versionName,
              "apkUrl": $url,
              "sha256": $hash,
              "notes": $notes
              $extra
            }
        """.trimIndent()
    }

    @Test
    fun newerVersionIsAGreaterVersionCode() {
        assertTrue(AppUpdateIndex.isNewer(2, 1))
        assertFalse(AppUpdateIndex.isNewer(1, 1))
        assertFalse(AppUpdateIndex.isNewer(1, 2))
        assertTrue(AppUpdateIndex.deviceCanInstall(null, 26))
        assertTrue(AppUpdateIndex.deviceCanInstall(26, 26))
        assertFalse(AppUpdateIndex.deviceCanInstall(27, 26))
    }

    @Test
    fun parsesAGithubReleaseDocument() {
        val parsed = AppUpdateIndex.parse(json(minSdk = "26"))
        requireNotNull(parsed)
        assertEquals(2, parsed.versionCode)
        assertEquals("1.1", parsed.versionName)
        assertEquals(apk, parsed.apkUrl)
        assertEquals(sha, parsed.sha256)
        assertEquals("Note in italiano", parsed.notes)
        assertEquals(26, parsed.minSdk)
        val upper = AppUpdateIndex.parse(json(hash = "\"${sha.uppercase()}\""))
        assertEquals(sha, upper?.sha256)
        val cdn = AppUpdateIndex.parse(
            json(url = "\"https://release-assets.githubusercontent.com/ScanTrader.apk\""),
        )
        assertEquals("https://release-assets.githubusercontent.com/ScanTrader.apk", cdn?.apkUrl)
        val numericCode = AppUpdateIndex.parse(json(versionCode = "\"3\""))
        assertEquals(3, numericCode?.versionCode)
    }

    @Test
    fun rejectsHttpUrlsBadHashesAndForeignHosts() {
        assertNull(AppUpdateIndex.parse(""))
        assertNull(AppUpdateIndex.parse("{"))
        assertNull(AppUpdateIndex.parse(json(url = "\"http://github.com/Vala97/ScanTrader/releases/download/app-update/ScanTrader.apk\"")))
        assertNull(AppUpdateIndex.parse(json(url = "\"https://example.com/ScanTrader.apk\"")))
        assertNull(AppUpdateIndex.parse(json(url = "\"https://github.com.evil.example/ScanTrader.apk\"")))
        assertNull(AppUpdateIndex.parse(json(hash = "\"zz\"")))
        assertNull(AppUpdateIndex.parse(json(hash = "\"${"zz".repeat(32)}\"")))
        assertNull(AppUpdateIndex.parse(json(hash = "\"${"ab".repeat(31)}\"")))
        assertNull(AppUpdateIndex.parse(json(versionCode = "0")))
        assertNull(AppUpdateIndex.parse(json(versionCode = "-1")))
        assertNull(AppUpdateIndex.parse(json(versionCode = "3000000000")))
        assertNull(AppUpdateIndex.parse(json(versionName = "\"\"")))
        assertNull(AppUpdateIndex.parse(json(versionName = "1")))
        assertNull(AppUpdateIndex.parse(json(minSdk = "\"no\"")))
        assertNull(AppUpdateIndex.parse(json(minSdk = "0")))
        assertNull(AppUpdateIndex.parse(json(minSdk = "1.5")))
        val noMin = AppUpdateIndex.parse(json())
        assertNull(noMin?.minSdk)
        assertEquals("", AppUpdateIndex.parse(json(notes = "\"\""))?.notes)
    }

    @Test
    fun sha256MismatchDeletesThePartialAndLeavesTheInstalledFile() {
        val dir = File.createTempFile("apk-upd", "").apply {
            delete()
            mkdirs()
        }
        try {
            val dest = File(dir, "scantrader-update.apk")
            dest.writeBytes("installed".toByteArray())
            val before = dest.readBytes()
            val part = File(dir, "scantrader-update.apk.part")
            part.writeBytes("tampered".toByteArray())
            assertNull(AppUpdateIndex.promoteVerified(part, dest, sha))
            assertFalse(part.exists())
            assertTrue(dest.isFile)
            assertTrue(before.contentEquals(dest.readBytes()))

            val missing = File(dir, "absent.apk")
            val badPart = File(dir, "other.part")
            badPart.writeBytes("nope".toByteArray())
            assertNull(AppUpdateIndex.promoteVerified(badPart, missing, sha))
            assertFalse(badPart.exists())
            assertFalse(missing.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun matchingSha256ReplacesTheInstalledFile() {
        val dir = File.createTempFile("apk-ok", "").apply {
            delete()
            mkdirs()
        }
        try {
            val payload = "hello-apk".toByteArray()
            val hash = AppUpdateIndex.sha256(payload)
            val dest = File(dir, "scantrader-update.apk")
            dest.writeBytes("old".toByteArray())
            val part = File(dir, "scantrader-update.apk.part")
            part.writeBytes(payload)
            val promoted = AppUpdateIndex.promoteVerified(part, dest, hash)
            assertEquals(dest, promoted)
            assertFalse(part.exists())
            assertTrue(payload.contentEquals(dest.readBytes()))
            assertEquals(hash, AppUpdateIndex.sha256(dest))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun autoCheckRunsAtMostOncePerDay() {
        val day = AppUpdateIndex.CHECK_INTERVAL_MS
        assertFalse(AppUpdateIndex.shouldAutoCheck(autoEnabled = false, lastCheckAtMs = 0L, nowMs = day))
        assertTrue(AppUpdateIndex.shouldAutoCheck(autoEnabled = true, lastCheckAtMs = 0L, nowMs = day))
        assertTrue(AppUpdateIndex.shouldAutoCheck(autoEnabled = true, lastCheckAtMs = -1L, nowMs = 0L))
        val last = 1_000L
        assertFalse(AppUpdateIndex.shouldAutoCheck(true, last, last + day - 1L))
        assertTrue(AppUpdateIndex.shouldAutoCheck(true, last, last + day))
    }
}
