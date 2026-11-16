package com.scantrader.app.vision

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Italian settings line for a catalog that is already on the phone. */
object CatalogStatusText {
    fun line(game: String, updatedAt: String?, setName: String?): String? {
        val date = italianDate(updatedAt) ?: return null
        val set = setName?.trim()?.takeIf { it.isNotEmpty() }
        return if (set == null) {
            "$game: aggiornato il $date"
        } else {
            "$game: aggiornato il $date · ultimo set: $set"
        }
    }

    /** `2026-11-16` or `2026.11.16` → `16/11/2026`. */
    fun italianDate(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = ISO.find(text) ?: DOTTED.find(text) ?: return null
        val year = match.groupValues[1]
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return "%02d/%02d/%s".format(Locale.US, day, month, year)
    }

    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val DOTTED = Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})""")
}

/** When a background download is worth starting. A missing catalog is not an update. */
object CatalogUpdateRules {
    fun shouldDownload(installed: Boolean, installedVersion: String?, remoteVersion: String): Boolean {
        if (!installed) return false
        return PokemonCatalogIndex.isUpdate(remoteVersion, installedVersion, fileExists = true)
    }

    fun payloadMatches(actualSha: String, expectedSha: String, actualSize: Long, expectedSize: Long): Boolean {
        if (expectedSize > 0L && actualSize != expectedSize) return false
        val want = expectedSha.trim()
        if (!SHA.matches(want)) return false
        return actualSha.equals(want, ignoreCase = true)
    }

    private val SHA = Regex("^[0-9a-fA-F]{64}$")
}

/**
 * Replaces a live catalog with a staged file. A failed replace puts the previous
 * file back. The staged file is only removed after the live name points at it.
 */
object CatalogFiles {
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
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    fun swap(live: File, staged: File): Boolean {
        if (!staged.isFile) return false
        live.parentFile?.mkdirs()
        val backup = File(live.parentFile, live.name + ".bak")
        if (backup.exists() && !backup.delete()) return false
        val hadLive = live.exists()
        if (hadLive && !live.renameTo(backup)) return false
        if (!staged.renameTo(live)) {
            if (hadLive) backup.renameTo(live)
            return false
        }
        backup.delete()
        return true
    }
}
