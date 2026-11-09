package com.scantrader.app.data.catalog

import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion

/**
 * The saved One Piece print, and no other.
 * `OP02-013` is not `OP02-013a`. `EB-03-NAMI` is not the gold `EB-03-NAMI-G`.
 * Two blueprints with the same collector are used only when the saved title names one version.
 */
object CtBlueprintMatch {
    suspend fun uniqueId(
        setCode: String,
        collectorNumber: String,
        savedName: String,
        expansions: List<CtExpansion>,
        printsOf: suspend (CtExpansion) -> List<CtBlueprint>,
    ): Int? {
        val set = setCode.trim()
        val number = collectorNumber.trim()
        if (set.isEmpty() || number.isEmpty()) return null
        val homes = expansions.filter { CtGameCatalog.key(it.code) == CtGameCatalog.key(set) }
        if (homes.isEmpty()) return null
        val hits = homes.flatMap { expansion ->
            printsOf(expansion).filter { sameCollector(CtGameCatalog.collector(it), number) }
        }
        return narrow(hits, savedName)?.id
    }

    private fun sameCollector(collector: String, wanted: String): Boolean {
        if (collector.isBlank()) return false
        if (CtGameCatalog.isDonQuery(wanted)) {
            return CtGameCatalog.key(collector) == CtGameCatalog.key(wanted)
        }
        return collector.equals(wanted, ignoreCase = true) ||
            CtGameCatalog.key(collector) == CtGameCatalog.key(wanted)
    }

    private fun narrow(hits: List<CtBlueprint>, savedName: String): CtBlueprint? {
        if (hits.isEmpty()) return null
        if (hits.size == 1) return hits.first()
        val version = versionOf(savedName)
        if (version != null) {
            val matched = hits.filter { it.version?.trim().equals(version, ignoreCase = true) }
            return matched.singleOrNull()
        }
        return hits.filter { it.version.isNullOrBlank() }.singleOrNull()
    }

    private fun versionOf(name: String): String? =
        Regex("""\(([^)]+)\)\s*$""").find(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
