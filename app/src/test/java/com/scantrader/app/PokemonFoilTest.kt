package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PokemonFoilTest {
    @Test
    fun pokemonDropsTheHoloPriceAndKeepsTheRegularOne() {
        val card = IdentifiedCard(
            game = Game.POKEMON,
            name = "Machop",
            setCode = "base1",
            setName = "Base",
            collectorNumber = "52",
            priceEur = "1.25",
            priceEurFoil = "9.99",
            priceUsdFoil = "12.00",
        )
        val stored = card.withoutPokemonFoil()
        assertEquals("1.25", stored.priceEur)
        assertNull(stored.priceEurFoil)
        assertNull(stored.priceUsdFoil)
        assertEquals("1.25", stored.priceForExport(foil = true))
        assertEquals("1.25", card.priceForExport(foil = true))
    }

    @Test
    fun magicStillExportsTheFoilPrice() {
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Lightning Bolt",
            setCode = "lea",
            setName = "Limited Edition Alpha",
            collectorNumber = "161",
            priceEur = "1.25",
            priceEurFoil = "9.99",
        )
        assertFalse(card.withoutPokemonFoil().priceEurFoil.isNullOrBlank())
        assertEquals("9.99", card.priceForExport(foil = true))
        assertEquals("1.25", card.priceForExport(foil = false))
    }
}
