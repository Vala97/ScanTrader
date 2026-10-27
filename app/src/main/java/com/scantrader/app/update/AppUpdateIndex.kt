package com.scantrader.app.update

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * One APK published on the fixed `app-update` GitHub release.
 * The URL is not `releases/latest`, so the Pokémon catalog release can stay latest.
 */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    val minSdk: Int?,
)

/**
 * Parses `app-update.json`, compares version codes, and checks a downloaded file.
 * No Android types: unit tests run this on the JVM.
 */
object AppUpdateIndex {
    const val INDEX_URL =
        "https://github.com/Vala97/ScanTrader/releases/download/app-update/app-update.json"

    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(json: String): AppUpdate? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val versionCode = obj.intOrNull("versionCode") ?: return null
        if (versionCode <= 0) return null
        val versionName = obj.string("versionName") ?: return null
        val url = obj.string("apkUrl") ?: return null
        if (!isGithubHttpsAsset(url)) return null
        val sha = obj.string("sha256") ?: return null
        if (!SHA256.matches(sha)) return null
        val minSdk = when {
            obj.get("minSdk") == null || obj.get("minSdk").isJsonNull -> null
            else -> obj.intOrNull("minSdk") ?: return null
        }
        if (minSdk != null && minSdk <= 0) return null
        val notes = obj.string("notes").orEmpty()
        return AppUpdate(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = url,
            sha256 = sha.lowercase(Locale.US),
            notes = notes,
            minSdk = minSdk,
        )
    }

    fun isGithubHttpsAsset(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "github.com" || host.endsWith(".githubusercontent.com")
    }

    fun isNewer(remoteVersionCode: Int, installedVersionCode: Int): Boolean =
        remoteVersionCode > installedVersionCode

    fun deviceCanInstall(minSdk: Int?, sdkInt: Int): Boolean =
        minSdk == null || sdkInt >= minSdk

    fun shouldAutoCheck(autoEnabled: Boolean, lastCheckAtMs: Long, nowMs: Long): Boolean {
        if (!autoEnabled) return false
        if (lastCheckAtMs <= 0L) return true
        return nowMs - lastCheckAtMs >= CHECK_INTERVAL_MS
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.toHex()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Hashes [part]. On a mismatch deletes only [part] and leaves [dest] as it was.
     * On a match replaces [dest] and removes [part].
     */
    fun promoteVerified(part: File, dest: File, expectedSha256: String): File? {
        if (!part.isFile) return null
        val actual = sha256(part)
        if (!SHA256.matches(expectedSha256) || !actual.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            return null
        }
        if (dest.exists() && !dest.delete()) {
            part.delete()
            return null
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest.takeIf { it.isFile }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun com.google.gson.JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        if (!value.asJsonPrimitive.isString) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }

    private fun com.google.gson.JsonObject.intOrNull(name: String): Int? {
        return try {
            val value = get(name) ?: return null
            if (value.isJsonNull || !value.isJsonPrimitive) return null
            val prim = value.asJsonPrimitive
            val long = when {
                prim.isNumber -> {
                    val text = prim.asString.trim()
                    if (!text.matches(Regex("-?\\d+"))) null else text.toLongOrNull()
                }
                prim.isString -> prim.asString.trim().toLongOrNull()
                else -> null
            } ?: return null
            if (long > Int.MAX_VALUE || long < Int.MIN_VALUE) return null
            long.toInt()
        } catch (_: RuntimeException) {
            null
        }
    }
}
