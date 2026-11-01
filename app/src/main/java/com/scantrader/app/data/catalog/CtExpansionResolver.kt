package com.scantrader.app.data.catalog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CtExpansionDao
import com.scantrader.app.data.db.CtExpansionEntity
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CardTraderCatalog
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.scan.ScanDebug
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves CardTrader expansion codes from the local Room list.
 * When both `X` and `cX` exist (Collector Edition), prefers a blueprint
 * match on scryfall_id, then a variant heuristic, then the base code.
 */
class CtExpansionResolver(
    private val dao: CtExpansionDao,
    private val api: CardTraderApi? = null,
    private val token: () -> String? = { null },
    private val blueprintCacheDir: File? = null,
) {
    private val memoryBlueprints = ConcurrentHashMap<Int, List<CtBlueprint>>()
    private val blueprintLock = Mutex()
    private val gson = Gson()
    @Volatile private var snapshot: List<CtExpansionEntity> = emptyList()

    suspend fun refreshSnapshot() {
        snapshot = dao.getAll()
    }

    suspend fun resolveEntry(entry: CardEntryEntity, allowNetwork: Boolean = true): String =
        resolve(
            game = entry.game,
            setCode = entry.setCode,
            scryfallIds = listOfNotNull(entry.catalogScryfallId, entry.scryfallId),
            variant = entry.variant,
            allowNetwork = allowNetwork,
        )

    suspend fun resolveCard(card: IdentifiedCard, allowNetwork: Boolean = true): String =
        resolve(
            game = card.game,
            setCode = card.setCode,
            scryfallIds = listOfNotNull(card.scryfallId),
            variant = com.scantrader.app.scan.CardFinish.variantOf(card.finish),
            allowNetwork = allowNetwork,
        )

    suspend fun resolve(
        game: Game,
        setCode: String,
        scryfallIds: List<String> = emptyList(),
        variant: String? = null,
        allowNetwork: Boolean = true,
    ): String {
        val code = setCode.trim().lowercase(Locale.ROOT)
        if (code.isEmpty()) {
            return when (game) {
                Game.MTG -> ExpansionCodes.mtg(setCode)
                Game.POKEMON -> ExpansionCodes.pokemon(setCode)
                else -> code
            }
        }

        return when (game) {
            Game.MTG -> resolveMtg(code, scryfallIds, variant, allowNetwork)
            Game.POKEMON -> resolvePokemon(code)
            else -> code
        }
    }

    /**
     * Sync path for the tracker: snapshot + heuristic, no network.
     * Falls back to [ExpansionCodes] when the snapshot is empty.
     */
    fun resolveCached(
        game: Game,
        setCode: String,
        variant: String? = null,
    ): String {
        val code = setCode.trim().lowercase(Locale.ROOT)
        if (code.isEmpty()) {
            return when (game) {
                Game.MTG -> ExpansionCodes.mtg(setCode)
                Game.POKEMON -> ExpansionCodes.pokemon(setCode)
                else -> setCode.lowercase(Locale.ROOT)
            }
        }
        return when (game) {
            Game.MTG -> resolveMtgFrom(snapshot, code, variant)
            Game.POKEMON -> {
                snapshot.firstOrNull { it.gameId == POKEMON_GAME_ID && it.code.equals(code, true) }
                    ?.code?.lowercase(Locale.ROOT)
                    ?: snapshot.firstOrNull { it.code.equals(code, true) }?.code?.lowercase(Locale.ROOT)
                    ?: ExpansionCodes.pokemon(code)
            }
            else -> code
        }
    }

    private suspend fun resolveMtg(
        code: String,
        scryfallIds: List<String>,
        variant: String?,
        allowNetwork: Boolean,
    ): String {
        ensureSnapshot()
        val base = snapshot.find { it.gameId == MTG_GAME_ID && it.code.equals(code, true) }
        val collectorCode = collectorSiblingCode(code)
        val collector = snapshot.find { it.gameId == MTG_GAME_ID && it.code.equals(collectorCode, true) }

        if (base != null && collector != null) {
            val fromBlueprints = if (allowNetwork && scryfallIds.any { it.isNotBlank() }) {
                resolveViaBlueprints(base, collector, scryfallIds)
            } else {
                null
            }
            if (fromBlueprints != null) return fromBlueprints
            return if (looksLikeCollector(variant)) collector.code.lowercase(Locale.ROOT) else base.code.lowercase(Locale.ROOT)
        }
        if (base != null) return base.code.lowercase(Locale.ROOT)
        if (collector != null && looksLikeCollector(variant)) {
            return collector.code.lowercase(Locale.ROOT)
        }
        snapshot.find { it.gameId == MTG_GAME_ID && it.code.equals(code, true) }
            ?.let { return it.code.lowercase(Locale.ROOT) }
        return ExpansionCodes.mtg(code)
    }

    private fun resolveMtgFrom(
        rows: List<CtExpansionEntity>,
        code: String,
        variant: String?,
    ): String {
        val base = rows.find { it.gameId == MTG_GAME_ID && it.code.equals(code, true) }
        val collector = rows.find { it.gameId == MTG_GAME_ID && it.code.equals(collectorSiblingCode(code), true) }
        if (base != null && collector != null) {
            return if (looksLikeCollector(variant)) {
                collector.code.lowercase(Locale.ROOT)
            } else {
                base.code.lowercase(Locale.ROOT)
            }
        }
        if (base != null) return base.code.lowercase(Locale.ROOT)
        if (collector != null && looksLikeCollector(variant)) {
            return collector.code.lowercase(Locale.ROOT)
        }
        rows.find { it.gameId == MTG_GAME_ID && it.code.equals(code, true) }
            ?.let { return it.code.lowercase(Locale.ROOT) }
        return ExpansionCodes.mtg(code)
    }

    private suspend fun resolvePokemon(code: String): String {
        ensureSnapshot()
        snapshot.firstOrNull { it.gameId == POKEMON_GAME_ID && it.code.equals(code, true) }
            ?.let { return it.code.lowercase(Locale.ROOT) }
        snapshot.firstOrNull { it.code.equals(code, true) }
            ?.let { return it.code.lowercase(Locale.ROOT) }
        return ExpansionCodes.pokemon(code)
    }

    private suspend fun ensureSnapshot() {
        if (snapshot.isNotEmpty()) return
        snapshot = dao.getAll()
    }

    private suspend fun resolveViaBlueprints(
        base: CtExpansionEntity,
        collector: CtExpansionEntity,
        scryfallIds: List<String>,
    ): String? {
        if (token()?.isBlank() != false || api == null) return null
        val ids = scryfallIds.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        val basePrints = loadBlueprints(base.id) ?: return null
        val collectorPrints = loadBlueprints(collector.id) ?: return null
        if (CardTraderCatalog.blueprintId(collectorPrints, ids) != null) {
            return collector.code.lowercase(Locale.ROOT)
        }
        if (CardTraderCatalog.blueprintId(basePrints, ids) != null) {
            return base.code.lowercase(Locale.ROOT)
        }
        return null
    }

    private suspend fun loadBlueprints(expansionId: Int): List<CtBlueprint>? {
        memoryBlueprints[expansionId]?.let { return it }
        return blueprintLock.withLock {
            memoryBlueprints[expansionId]?.let { return it }
            val fromFile = readBlueprintFile(expansionId)
            if (fromFile != null) {
                memoryBlueprints[expansionId] = fromFile
                return fromFile
            }
            val api = this.api ?: return null
            runCatching {
                val loaded = api.blueprints(expansionId)
                memoryBlueprints[expansionId] = loaded
                writeBlueprintFile(expansionId, loaded)
                loaded
            }.onFailure {
                ScanDebug.w("CardTrader blueprints load failed for expansion $expansionId", it)
            }.getOrNull()
        }
    }

    private fun readBlueprintFile(expansionId: Int): List<CtBlueprint>? {
        val dir = blueprintCacheDir ?: return null
        val file = File(dir, "$expansionId.json")
        if (!file.isFile) return null
        return runCatching {
            val type = object : TypeToken<List<CtBlueprint>>() {}.type
            gson.fromJson<List<CtBlueprint>>(file.readText(), type)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeBlueprintFile(expansionId: Int, items: List<CtBlueprint>) {
        val dir = blueprintCacheDir ?: return
        runCatching {
            dir.mkdirs()
            File(dir, "$expansionId.json").writeText(gson.toJson(items))
        }
    }

    /**
     * Looks up MTG expansion ids for [setCode] and its Collector sibling, for price matching.
     */
    suspend fun mtgExpansionCandidates(setCode: String): List<CtExpansionEntity> {
        ensureSnapshot()
        val code = setCode.trim().lowercase(Locale.ROOT)
        if (code.isEmpty()) return emptyList()
        val base = snapshot.find { it.gameId == MTG_GAME_ID && it.code.equals(code, true) }
        val collector = snapshot.find {
            it.gameId == MTG_GAME_ID && it.code.equals(collectorSiblingCode(code), true)
        }
        return listOfNotNull(base, collector).distinctBy { it.id }
    }

    companion object {
        const val MTG_GAME_ID = 1
        /** CardTrader Pokémon TCG game id. */
        const val POKEMON_GAME_ID = 5

        fun collectorSiblingCode(baseCode: String): String =
            "c" + baseCode.trim().lowercase(Locale.ROOT)

        fun looksLikeCollector(variant: String?): Boolean {
            if (variant.isNullOrBlank()) return false
            val v = variant.lowercase(Locale.ROOT)
            val frameHints = listOf(
                "frame:showcase",
                "frame:extendedart",
                "frame:inverted",
                "showcase",
                "extendedart",
                "inverted",
            )
            val promoHints = listOf(
                "serialized",
                "boosterfun",
                "facetfoil",
                "fracturefoil",
                "textured",
                "borderless",
            )
            if (frameHints.any { v.contains(it) }) return true
            if (promoHints.any { v.contains(it) }) return true
            return false
        }
    }
}
