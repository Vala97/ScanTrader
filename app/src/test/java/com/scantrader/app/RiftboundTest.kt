package com.scantrader.app

import com.scantrader.app.data.catalog.RiftboundCatalog
import com.scantrader.app.data.model.Game
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.scantrader.app.network.cardtrader.CtProduct
import com.scantrader.app.scan.OcrParser
import com.scantrader.app.vision.MatchFusion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RiftboundTest {
    // Bottom-left line as printed on real cards (CardTrader images of Origins).
    @Test
    fun readsTheBottomLeftCode() {
        val p = OcrParser.parse("", Game.RIFTBOUND, bottomPriorityText = "OGN • 001/298\nEnvar Studio • ©2025RGI")
        assertEquals("ogn", p.setCode)
        assertEquals("001", p.collectorNumber)
        assertEquals(298, p.setCardCount)
        assertNull(p.nameGuess) // the title is in the middle band, the catalog gives the name
    }

    @Test
    fun readsAlternateArtShowcaseAndSigned() {
        assertEquals(Triple("ogn", "007a", 298), OcrParser.riftboundCode("OGN • 007a/298"))
        assertEquals(Triple("ogn", "299", 298), OcrParser.riftboundCode("OGN · 299/298"))
        assertEquals(Triple("ogn", "299s", 298), OcrParser.riftboundCode("OGN 299s/298"))
        assertEquals(Triple("sfd", "082", 221), OcrParser.riftboundCode("SFD - 082/221"))
    }

    @Test
    fun fixesTypicalOcrSwaps() {
        // 0 read as O, 1 as l, code with a zero, bullet lost or read as a dot
        assertEquals(Triple("ogn", "001", 298), OcrParser.riftboundCode("0GN . OOl/298"))
        assertEquals(Triple("ven", "063", 221), OcrParser.riftboundCode("VEN063/221"))
        // not a Riftbound set: ignored
        assertNull(OcrParser.riftboundCode("ABC • 001/298"))
        // copyright line is not a code
        assertNull(OcrParser.riftboundCode("©2025RGI"))
    }

    @Test
    fun numberOnlyWithoutSet() {
        val p = OcrParser.parse("", Game.RIFTBOUND, bottomPriorityText = "• 045/298")
        assertNull(p.setCode)
        assertEquals("045", p.collectorNumber)
        assertEquals(298, p.setCardCount)
    }

    @Test
    fun numberKeyAndFoilRules() {
        assertEquals("1", RiftboundCatalog.numberKey("001"))
        assertEquals("7a", RiftboundCatalog.numberKey("007a"))
        assertEquals("299s", RiftboundCatalog.numberKey("299S"))
        assertNull(RiftboundCatalog.numberKey("SP4x"))
        // Common/Uncommon can be either; the rest only exist foil
        assertFalse(RiftboundCatalog.alwaysFoil("Common"))
        assertFalse(RiftboundCatalog.alwaysFoil("Uncommon"))
        for (r in listOf("Rare", "Epic", "Legendary", "Showcase", "Alternate Art")) assertTrue(r, RiftboundCatalog.alwaysFoil(r))
        assertEquals(298, RiftboundCatalog.printedSize((1..298).toList() + listOf(305, 306)))
    }

    private class FakeApi : CardTraderApi {
        var calls = 0
        override suspend fun expansions() = listOf(
            CtExpansion(4166, 22, "ogn", "Origins"),
            CtExpansion(4299, 22, "sfd", "Spiritforged"),
            CtExpansion(1, 1, "mom", "Magic set"),
        ).also { calls++ }

        private fun bp(id: Int, name: String, n: String, r: String?) =
            CtBlueprint(id = id, name = name, gameId = 22, fixedProperties = mapOf("collector_number" to n, "riftbound_rarity" to r), imageUrl = "https://cardtrader.com/x/$id.jpg")

        override suspend fun blueprints(expansionId: Int): List<CtBlueprint> = when (expansionId) {
            4166 -> listOf(
                bp(1, "Blazing Scorcher", "001", "Common"),
                bp(2, "Fury Rune", "007", "Common"),
                bp(3, "Fury Rune", "007a", "Alternate Art"),
                bp(4, "Kai'Sa - Daughter of the Void", "299", "Showcase"),
                bp(5, "Kai'Sa - Daughter of the Void", "299s", "Showcase"),
                CtBlueprint(id = 6, name = "Origins Booster", gameId = 22), // sealed: no collector number
            )
            4299 -> listOf(bp(7, "Ezreal - Dashing", "082", "Rare"), bp(8, "Blazing Scorcher reprint", "001", "Common"))
            else -> emptyList()
        }

        override suspend fun products(blueprintId: Int, language: String?, foil: Boolean?): Map<String, List<CtProduct>> = emptyMap()
    }

    private fun tmpFile() = File.createTempFile("riftbound", ".json").also { it.delete(); it.deleteOnExit() }

    @Test
    fun catalogFindsTheExactBlueprint() = runBlocking {
        val api = FakeApi()
        val cat = RiftboundCatalog(api, token = { "tok" }, file = tmpFile())
        assertEquals(7, cat.ensure())
        val alt = cat.lookup("ogn", "007a", 298).single()
        assertEquals(3, alt.ctBlueprintId)
        assertEquals("Alternate Art", alt.rarity)
        assertEquals(Game.RIFTBOUND, alt.game)
        assertEquals("ogn", alt.setCode)
        assertEquals(4, cat.lookup("ogn", "299", 298).single().ctBlueprintId)
        assertEquals(5, cat.lookup("ogn", "299s", 298).single().ctBlueprintId)
        // set unreadable: every set with that number, to confirm by hand
        assertEquals(setOf(1, 8), cat.lookup(null, "001", null).map { it.ctBlueprintId }.toSet())
        assertTrue(cat.lookup("ogn", "123", 298).isEmpty())
    }

    @Test
    fun catalogIsSavedAndReusedWithoutToken() = runBlocking {
        val file = tmpFile()
        val api = FakeApi()
        RiftboundCatalog(api, token = { "tok" }, file = file).ensure()
        assertTrue(file.exists())
        // later, without token and without network: the saved list is used
        val again = RiftboundCatalog(api, token = { null }, file = file)
        assertEquals(7, again.ensure())
        assertEquals(1, api.calls)
    }

    @Test
    fun noTokenAndNoCatalogSaysWhatToDo() = runBlocking {
        try {
            RiftboundCatalog(FakeApi(), token = { "" }, file = tmpFile()).ensure()
            fail("expected NoTokenException")
        } catch (e: RiftboundCatalog.NoTokenException) {
            assertTrue(e.message!!.contains("token"))
        }
    }

    @Test
    fun autosaveOnlyWithTheExactCode() = runBlocking {
        val cat = RiftboundCatalog(FakeApi(), token = { "tok" }, file = tmpFile())
        cat.ensure()
        val parsed = OcrParser.parse("", Game.RIFTBOUND, bottomPriorityText = "OGN • 007a/298")
        val top = MatchFusion.fuse(cat.lookup(parsed.setCode, parsed.collectorNumber, parsed.setCardCount), parsed).first()
        assertTrue(MatchFusion.evaluateAutoMatch(top, parsed).accepted)
        // number only (set unread): no autosave, the user picks
        val loose = OcrParser.parse("", Game.RIFTBOUND, bottomPriorityText = "007a/298")
        val looseTop = MatchFusion.fuse(cat.lookup(loose.setCode, loose.collectorNumber, loose.setCardCount), loose).first()
        assertFalse(MatchFusion.evaluateAutoMatch(looseTop, loose).accepted)
    }
}
