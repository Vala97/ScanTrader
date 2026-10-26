package com.scantrader.app

import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.StockKey
import com.scantrader.app.data.db.StockMerge
import com.scantrader.app.data.db.stamped
import com.scantrader.app.data.model.Game
import com.scantrader.app.scan.CardFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 4→5 migration merges with [StockMerge.collapse] before the unique index.
 * These rows are the fixture: Italian and English printings of one card, `34` vs
 * `034`, `fra` vs `FRA`, and the splits that must stay apart.
 */
class StockMergeTest {
    @Test
    fun splitItalianAndEnglishPrintingsMergeOnTheEnglishId() {
        val italian = row(
            id = 1,
            createdAt = 10,
            name = "Teoria Perfezionata",
            scryfallId = "fra-34-it",
            catalogScryfallId = "fra-34",
            imageUrl = "it.jpg",
            priceEur = "1.20",
            quantity = 1,
        )
        val english = row(
            id = 2,
            createdAt = 20,
            name = "Perfected Theory",
            scryfallId = "fra-34",
            imageUrl = null,
            priceEur = null,
            quantity = 2,
        )
        val merged = StockMerge.collapse(listOf(italian, english)).single()
        assertEquals(3, merged.quantity)
        assertEquals(2L, merged.id)
        assertEquals("Perfected Theory", merged.name)
        assertEquals("it.jpg", merged.imageUrl)
        assertEquals("1.20", merged.priceEur)
        assertEquals("fra-34", merged.catalogScryfallId)
        assertEquals(StockKey.of(english), merged.copyKey)
    }

    @Test
    fun leadingZerosAndSetCaseMergeWhenThereIsNoPrintingId() {
        val short = row(id = 3, createdAt = 1, setCode = "fra", collectorNumber = "34", quantity = 1)
        val padded = row(
            id = 4,
            createdAt = 2,
            setCode = "FRA",
            collectorNumber = "034",
            name = "Teoria Perfezionata",
            quantity = 1,
            imageUrl = "pad.jpg",
            priceEur = "0.40",
        )
        val merged = StockMerge.collapse(listOf(short, padded)).single()
        assertEquals(2, merged.quantity)
        assertEquals("Teoria Perfezionata", merged.name)
        assertEquals("pad.jpg", merged.imageUrl)
        assertEquals("0.40", merged.priceEur)
        assertEquals(StockKey.normalizeCollector("034"), "34")
        assertEquals(StockKey.of(short), StockKey.of(padded))
    }

    @Test
    fun foilConditionLanguageAndVariantStayApart() {
        val base = row(id = 1, createdAt = 1)
        val foil = base.copy(id = 2, foil = true)
        val damaged = base.copy(id = 3, condition = "Damaged")
        val english = base.copy(id = 4, language = "English")
        val etched = base.copy(id = 5, variant = "etched")
        val kept = StockMerge.collapse(listOf(base, foil, damaged, english, etched))
        assertEquals(5, kept.size)
        assertTrue(kept.map { it.id }.containsAll(listOf(1L, 2L, 3L, 4L, 5L)))
    }

    @Test
    fun twoIdsOnTheSameSetAndNumberStayApart() {
        val artA = row(id = 1, scryfallId = "art-a", collectorNumber = "34", setCode = "fra")
        val artB = row(id = 2, scryfallId = "art-b", collectorNumber = "034", setCode = "FRA")
        val kept = StockMerge.collapse(listOf(artA, artB))
        assertEquals(2, kept.size)
        assertFalse(StockKey.of(artA) == StockKey.of(artB))
    }

    @Test
    fun letterSuffixAndOtherCollectionsStayApart() {
        val plain = row(id = 1, collectorNumber = "105", scryfallId = null)
        val suffix = row(id = 2, collectorNumber = "105a", scryfallId = null)
        val otherCollection = plain.copy(id = 3, collectionId = 9)
        assertEquals("105", StockKey.normalizeCollector("0105"))
        assertEquals("105a", StockKey.normalizeCollector("105A"))
        assertEquals("0", StockKey.normalizeCollector("00"))
        val kept = StockMerge.collapse(listOf(plain, suffix, otherCollection))
        assertEquals(3, kept.size)
    }

    @Test
    fun newestNonBlankNameImageAndPriceWin() {
        val older = row(
            id = 1,
            createdAt = 5,
            name = "Old",
            imageUrl = "old.jpg",
            priceEur = "2.00",
            priceUsd = "3.00",
            scryfallId = "fra-34",
        )
        val newerBlankPrice = row(
            id = 2,
            createdAt = 9,
            name = "New",
            imageUrl = "  ",
            priceEur = "",
            priceUsd = null,
            scryfallId = "fra-34",
        )
        val merged = StockMerge.merge(listOf(older, newerBlankPrice))
        assertEquals("New", merged.name)
        assertEquals("old.jpg", merged.imageUrl)
        assertEquals("2.00", merged.priceEur)
        assertEquals("3.00", merged.priceUsd)
        assertEquals(9L, merged.createdAt)
    }

    @Test
    fun pokemonUsesTcgdexIdAndDropsFoil() {
        val first = row(
            id = 1,
            createdAt = 1,
            game = Game.POKEMON,
            scryfallId = null,
            tcgdexId = "sv10-012",
            setCode = "sv10",
            collectorNumber = "012",
            foil = true,
            priceEurFoil = "9.00",
            quantity = 1,
        )
        val second = row(
            id = 2,
            createdAt = 2,
            game = Game.POKEMON,
            scryfallId = null,
            tcgdexId = "sv10-012",
            setCode = "sv10",
            collectorNumber = "12",
            foil = false,
            quantity = 2,
        )
        val other = second.copy(id = 3, tcgdexId = "sv10-099", createdAt = 3)
        val kept = StockMerge.collapse(listOf(first, second, other))
        assertEquals(2, kept.size)
        val merged = kept.single { it.tcgdexId == "sv10-012" }
        assertEquals(3, merged.quantity)
        assertFalse(merged.foil)
        assertNull(merged.priceEurFoil)
        assertEquals(StockKey.of(first), StockKey.of(second))
        assertFalse(StockKey.of(second) == StockKey.of(other))
    }

    @Test
    fun legacySetRowUsesTheSameKeyAsARowWithoutIds() {
        val legacy = row(id = 8, scryfallId = null, setCode = "FRA", collectorNumber = "034").stamped()
        val scanned = row(id = 0, scryfallId = "fra-34", setCode = "fra", collectorNumber = "34").stamped()
        assertEquals(legacy.copyKey, StockKey.withoutPrintingId(scanned))
        assertFalse(legacy.copyKey == scanned.copyKey)
    }

    @Test
    fun variantDropsTheFoilBit() {
        assertNull(CardFinish.variantOf(null))
        assertNull(CardFinish.variantOf("foil+nonfoil"))
        assertNull(CardFinish.variantOf("nonfoil"))
        assertEquals("etched", CardFinish.variantOf("etched+foil"))
        assertEquals("etched|frame:showcase", CardFinish.variantOf("etched+foil|frame:showcase"))
        assertEquals("frame:showcase", CardFinish.variantOf("foil+nonfoil|frame:showcase"))
        assertEquals("promo:promopack", CardFinish.variantOf("promo:promopack"))
        assertNull(CardFinish.variantOf("frame:"))
    }

    @Test
    fun versionFourIsNotADestructiveFallback() {
        val versions = AppDatabase.DESTRUCTIVE_FALLBACK_FROM.toList()
        assertEquals(listOf(1), versions)
        assertFalse(versions.contains(2))
        assertFalse(versions.contains(3))
        assertFalse(versions.contains(4))
        assertFalse(versions.contains(5))
    }
}

private fun row(
    id: Long,
    createdAt: Long = 1,
    collectionId: Long = 1,
    game: Game = Game.MTG,
    name: String = "Teoria Perfezionata",
    setCode: String = "fra",
    collectorNumber: String = "34",
    scryfallId: String? = null,
    catalogScryfallId: String? = null,
    tcgdexId: String? = null,
    language: String = "Italian",
    condition: String = "Near Mint",
    foil: Boolean = false,
    variant: String? = null,
    imageUrl: String? = null,
    priceEur: String? = null,
    priceEurFoil: String? = null,
    priceUsd: String? = null,
    quantity: Int = 1,
) = CardEntryEntity(
    id = id,
    collectionId = collectionId,
    game = game,
    name = name,
    setCode = setCode,
    setName = "Foundations",
    collectorNumber = collectorNumber,
    scryfallId = scryfallId,
    tcgplayerId = null,
    imageUrl = imageUrl,
    quantity = quantity,
    language = language,
    condition = condition,
    foil = foil,
    priceEur = priceEur,
    priceEurFoil = priceEurFoil,
    priceUsd = priceUsd,
    catalogScryfallId = catalogScryfallId,
    createdAt = createdAt,
    variant = variant,
    tcgdexId = tcgdexId,
)
