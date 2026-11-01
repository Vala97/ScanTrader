package com.scantrader.app

import com.scantrader.app.data.catalog.CtExpansionResolver
import com.scantrader.app.data.catalog.ExpansionsSyncController
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CtExpansionDao
import com.scantrader.app.data.db.CtExpansionEntity
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.export.CardTraderCsv
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.scantrader.app.network.cardtrader.CtProduct
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CtExpansionResolverTest {
    @Test
    fun syncThrottleIsOncePerDay() {
        val day = ExpansionsSyncController.SYNC_INTERVAL_MS
        assertTrue(ExpansionsSyncController.shouldSync(0L, 1L))
        assertFalse(ExpansionsSyncController.shouldSync(100L, 100L + day - 1))
        assertTrue(ExpansionsSyncController.shouldSync(100L, 100L + day))
    }

    @Test
    fun collectorSiblingAndHeuristic() {
        assertEquals("cfra", CtExpansionResolver.collectorSiblingCode("FRA"))
        assertTrue(CtExpansionResolver.looksLikeCollector("frame:showcase"))
        assertTrue(CtExpansionResolver.looksLikeCollector("promo:facetfoil"))
        assertTrue(CtExpansionResolver.looksLikeCollector("etched|frame:extendedart"))
        assertFalse(CtExpansionResolver.looksLikeCollector(null))
        assertFalse(CtExpansionResolver.looksLikeCollector("etched"))
        assertFalse(CtExpansionResolver.looksLikeCollector("frame:legendary"))
    }

    @Test
    fun showcaseVariantMapsToCollectorCode() = runBlocking {
        val dao = FakeExpansionDao(
            listOf(
                CtExpansionEntity(1, 1, "fra", "Reality Fracture"),
                CtExpansionEntity(2, 1, "cfra", "Reality Fracture Collectors"),
            ),
        )
        val resolver = CtExpansionResolver(dao)
        resolver.refreshSnapshot()
        assertEquals(
            "cfra",
            resolver.resolve(
                game = Game.MTG,
                setCode = "fra",
                variant = "frame:showcase",
                allowNetwork = false,
            ),
        )
        assertEquals(
            "fra",
            resolver.resolve(
                game = Game.MTG,
                setCode = "fra",
                variant = null,
                allowNetwork = false,
            ),
        )
    }

    @Test
    fun blueprintHitPrefersCollectorEvenWithoutVariant() = runBlocking {
        val dao = FakeExpansionDao(
            listOf(
                CtExpansionEntity(10, 1, "fra", "Reality Fracture"),
                CtExpansionEntity(11, 1, "cfra", "Reality Fracture Collectors"),
            ),
        )
        val api = object : CardTraderApi {
            override suspend fun expansions(): List<CtExpansion> = emptyList()
            override suspend fun blueprints(expansionId: Int): List<CtBlueprint> = when (expansionId) {
                10 -> listOf(CtBlueprint(id = 1, name = "Regular", scryfallId = "regular-id"))
                11 -> listOf(CtBlueprint(id = 2, name = "Collector", scryfallId = "collector-id"))
                else -> emptyList()
            }
            override suspend fun products(
                blueprintId: Int,
                language: String?,
                foil: Boolean?,
            ): Map<String, List<CtProduct>> = emptyMap()
        }
        val resolver = CtExpansionResolver(dao, api = api, token = { "token" })
        resolver.refreshSnapshot()
        assertEquals(
            "cfra",
            resolver.resolve(
                game = Game.MTG,
                setCode = "fra",
                scryfallIds = listOf("collector-id"),
                variant = null,
                allowNetwork = true,
            ),
        )
        assertEquals(
            "fra",
            resolver.resolve(
                game = Game.MTG,
                setCode = "fra",
                scryfallIds = listOf("regular-id"),
                variant = null,
                allowNetwork = true,
            ),
        )
    }

    @Test
    fun csvUsesPreResolvedExpansionCodes() {
        val entry = CardEntryEntity(
            id = 42,
            collectionId = 1,
            game = Game.MTG,
            name = "Marwyn, the Preserver",
            setCode = "fra",
            setName = "Reality Fracture",
            collectorNumber = "300",
            scryfallId = "sf-1",
            tcgplayerId = null,
            imageUrl = null,
            quantity = 1,
            language = "English",
            condition = "Near Mint",
            foil = false,
            priceEur = "0.14",
            variant = "frame:showcase",
        )
        val csv = CardTraderCsv.build(
            entries = listOf(entry),
            game = Game.MTG,
            expansionCodes = mapOf(42L to "cfra"),
        )
        assertTrue(csv.contains("\"Marwyn, the Preserver\",cfra,300,1,en,Near Mint,false,14"))
        assertFalse(csv.contains(",fra,"))
    }

    @Test
    fun cachedResolveMatchesHeuristic() = runBlocking {
        val dao = FakeExpansionDao(
            listOf(
                CtExpansionEntity(1, 1, "dft", "Aetherdrift"),
                CtExpansionEntity(2, 1, "cdft", "Aetherdrift Collectors"),
            ),
        )
        val resolver = CtExpansionResolver(dao)
        resolver.refreshSnapshot()
        assertEquals("cdft", resolver.resolveCached(Game.MTG, "dft", "promo:boosterfun"))
        assertEquals("dft", resolver.resolveCached(Game.MTG, "dft", null))
    }

    private class FakeExpansionDao(
        private var rows: List<CtExpansionEntity>,
    ) : CtExpansionDao {
        override suspend fun upsertAll(items: List<CtExpansionEntity>) {
            rows = items
        }

        override suspend fun getAll(): List<CtExpansionEntity> = rows

        override suspend fun byGame(gameId: Int): List<CtExpansionEntity> =
            rows.filter { it.gameId == gameId }

        override suspend fun findByCode(gameId: Int, code: String): CtExpansionEntity? =
            rows.firstOrNull { it.gameId == gameId && it.code.equals(code, true) }

        override suspend fun findByCodeAny(code: String): CtExpansionEntity? =
            rows.firstOrNull { it.code.equals(code, true) }

        override suspend fun count(): Int = rows.size
    }
}
