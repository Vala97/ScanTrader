package com.scantrader.app.vision

import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

/**
 * One `.stcat` published on the ScanTrader GitHub release.
 * [id] is already safe to use as a file name.
 */
data class StcatCatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

/**
 * Parses `stcat-index.json`, checks sha256, and compares dotted versions.
 * No Android types: unit tests run this on the JVM.
 */
object PokemonCatalogIndex {
    const val INDEX_URL =
        "https://github.com/Vala97/ScanTrader/releases/latest/download/stcat-index.json"

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(json: String): List<StcatCatalogEntry> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonObject) return emptyList()
        val catalogs = root.asJsonObject.get("catalogs") ?: return emptyList()
        if (!catalogs.isJsonArray) return emptyList()
        val out = ArrayList<StcatCatalogEntry>()
        val seen = HashSet<String>()
        for (element in catalogs.asJsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val rawId = obj.string("id") ?: continue
            val id = fileIdOrNull(rawId) ?: continue
            if (!seen.add(id)) continue
            val url = obj.string("url") ?: continue
            if (!url.startsWith("https://")) continue
            val sha = obj.string("sha256") ?: continue
            if (!SHA256.matches(sha)) continue
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: id
            val version = obj.string("version").orEmpty()
            val size = obj.longOrNull("size") ?: 0L
            out += StcatCatalogEntry(
                id = id,
                name = name,
                version = version.trim(),
                size = size.coerceAtLeast(0L),
                sha256 = sha.lowercase(Locale.US),
                url = url,
            )
        }
        return out
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
     * Dotted numeric compare. `1.2` > `1.1`, `2026.10.26` > `2026.9.1`.
     * Missing trailing parts count as 0. Returns positive when [left] is newer.
     */
    fun compareVersions(left: String, right: String): Int {
        val a = left.trim().split('.')
        val b = right.trim().split('.')
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val asg = a.getOrElse(i) { "0" }.trim()
            val bsg = b.getOrElse(i) { "0" }.trim()
            val an = asg.toLongOrNull()
            val bn = bsg.toLongOrNull()
            val cmp = if (an != null && bn != null) an.compareTo(bn) else asg.compareTo(bsg)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * A file on disk is outdated when its stored version is blank, or the
     * remote version compares greater. A missing file is not an update.
     */
    fun isUpdate(remoteVersion: String, installedVersion: String?, fileExists: Boolean): Boolean {
        if (!fileExists) return false
        if (installedVersion.isNullOrBlank()) return remoteVersion.isNotBlank()
        return compareVersions(remoteVersion, installedVersion) > 0
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        val mb = 1024.0 * 1024.0
        val kb = 1024.0
        val number = NumberFormat.getNumberInstance(Locale.ITALY).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = if (bytes >= 1024L) 1 else 0
        }
        return when {
            bytes >= mb -> number.format(bytes / mb) + " MB"
            bytes >= kb -> number.format(bytes / kb) + " KB"
            else -> "$bytes B"
        }
    }

    /** Magic catalog published in the same index as the Pokémon `.stcat` files. */
    const val MTG_CATALOG_ID = "mtg"

    fun isMtgCatalogId(id: String): Boolean {
        val key = id.trim().lowercase(Locale.US)
        return key == MTG_CATALOG_ID || key == "magic" ||
            key.startsWith("$MTG_CATALOG_ID-") || key.startsWith("magic-")
    }

    /** Newest Magic row. Pokémon rows stay out of the Magic download. */
    fun mtgEntry(entries: List<StcatCatalogEntry>): StcatCatalogEntry? =
        entries.filter { isMtgCatalogId(it.id) }
            .maxWithOrNull { left, right -> compareVersions(left.version, right.version) }

    fun fileIdOrNull(id: String): String? {
        val cleaned = id.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.contains("..")) {
            return null
        }
        return cleaned
    }

    private fun com.google.gson.JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }

    private fun com.google.gson.JsonObject.longOrNull(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive) return null
        val prim = value.asJsonPrimitive
        return when {
            prim.isNumber -> prim.asLong
            prim.isString -> prim.asString.trim().toLongOrNull()
            else -> null
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
