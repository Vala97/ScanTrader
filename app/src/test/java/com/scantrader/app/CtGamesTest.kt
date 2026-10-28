package com.scantrader.app

import com.scantrader.app.data.catalog.CtGameCatalog
import com.scantrader.app.data.model.Game
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.scantrader.app.network.cardtrader.CtProduct
import com.scantrader.app.scan.OcrParser
import com.scantrader.app.scan.OcrParser.CtCode
import com.scantrader.app.vision.MatchFusion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CtGamesTest {
    // ---- codici stampati (immagini CardTrader delle carte) ----
    @Test
    fun onePiece() {
        assertEquals(CtCode("op02", "OP02-013"), OcrParser.ctCode(Game.ONE_PIECE, "Whitebeard Pirates  OP02-013 SR 1"))
        assertEquals(CtCode("st10", "ST10-014"), OcrParser.ctCode(Game.ONE_PIECE, "5T10-0l4"))
        assertEquals(CtCode("eb01", "EB01-001"), OcrParser.ctCode(Game.ONE_PIECE, "EB01 - 001"))
        assertEquals(CtCode("op02", "OP02-013"), OcrParser.ctCode(Game.ONE_PIECE, "0P02-O13"))
        assertEquals(CtCode("promo", "P-041"), OcrParser.ctCode(Game.ONE_PIECE, "P-041"))
        assertNull(OcrParser.ctCode(Game.ONE_PIECE, "7000 SPECIAL"))
    }

    @Test
    fun dragonBall() {
        assertEquals(CtCode("fb02", "FB02-075"), OcrParser.ctCode(Game.DRAGON_BALL, "Android 16 FB02-075 1 UC"))
        assertEquals(CtCode("fs03", "FS03-14"), OcrParser.ctCode(Game.DRAGON_BALL, "FS03-14"))
        assertEquals(CtCode("fb02", "FB02-075"), OcrParser.ctCode(Game.DRAGON_BALL, "F802-O75"))
    }

    @Test
    fun yugioh() {
        assertEquals(CtCode("potd", "003", "en"), OcrParser.ctCode(Game.YUGIOH, "NEO-SPACIAN AQUA DOLPHIN\nPOTD-EN003\nATK/600 DEF/800"))
        assertEquals(CtCode("ygld", "A01", "it"), OcrParser.ctCode(Game.YUGIOH, "YGLD-ITA01"))
        assertEquals(CtCode("lob", "001", null), OcrParser.ctCode(Game.YUGIOH, "LOB-001"))
        assertEquals(CtCode("potd", "003", "en"), OcrParser.ctCode(Game.YUGIOH, "POTD-ENOO3"))
        // un nome con il trattino non e' un codice
        assertNull(OcrParser.ctCode(Game.YUGIOH, "Neo-Spacian Aqua Dolphin"))
        val p = OcrParser.parse("POTD-IT003", Game.YUGIOH, namePriorityText = "Neo-Spacian Aqua Dolphin")
        assertEquals("it", p.printedLang)
        assertEquals("Neo-Spacian Aqua Dolphin", p.nameGuess)
    }

    @Test
    fun unionArena() {
        assertEquals(CtCode("ue20bt", "UE20BT-001"), OcrParser.ctCode(Game.UNION_ARENA, "2000BP UE20BT/TSK-2-001 C"))
        assertEquals(CtCode("ue20bt", "UE20BT-001"), OcrParser.ctCode(Game.UNION_ARENA, "UE20BT/TSK-1-OO1"))
    }

    @Test
    fun codeCorners() {
        // centro del codice misurato sulle immagini CardTrader (frazioni della carta)
        val centers = mapOf(
            Game.ONE_PIECE to (0.87f to 0.984f), Game.DRAGON_BALL to (0.93f to 0.90f),
            Game.YUGIOH to (0.80f to 0.73f), Game.UNION_ARENA to (0.15f to 0.97f),
        )
        for ((g, c) in centers) {
            val r = com.scantrader.app.scan.CardFrameGeometry.ctCodeRegion(g)!!
            assertTrue("$g", c.first in r[0]..r[2] && c.second in r[1]..r[3])
        }
        assertNull(com.scantrader.app.scan.CardFrameGeometry.ctCodeRegion(Game.RIFTBOUND))
    }

    @Test
    fun baseNumbers() {
        assertEquals("op02013", CtGameCatalog.base("OP02-013a"))
        assertEquals("st01004", CtGameCatalog.base("ST01-004P6"))
        assertEquals("fb02075", CtGameCatalog.base("FB02-075a"))
        assertEquals("ue20bt001", CtGameCatalog.base("UE20BT-001 ★ ★"))
        assertEquals("003", CtGameCatalog.base("003"))
        assertEquals("001", CtGameCatalog.base("001ul"))
        assertEquals("a01", CtGameCatalog.base("A01"))
    }

    // ---- catalogo con un CardTrader finto ----
    private class FakeApi : CardTraderApi {
        val calls = mutableListOf<String>()
        override suspend fun expansions() = listOf(
            CtExpansion(2, 15, "op02", "OP-02: Paramount War"), CtExpansion(3, 15, "eb-01", "Extra Booster"),
            CtExpansion(9, 4, "op02", "OTS Pack 2 (Yu-Gi-Oh)"), CtExpansion(10, 4, "ygld-2020", "Yugi's Legendary Decks"),
            CtExpansion(11, 4, "potd", "Power of the Duelist"), CtExpansion(20, 21, "ue20bt", "Slime"),
        ).also { calls += "exp" }
        private fun bp(id: Int, name: String, n: String, version: String? = null) =
            CtBlueprint(id = id, name = name, version = version, fixedProperties = mapOf("collector_number" to n), imageUrl = "/x/$id.jpg")
        override suspend fun blueprints(expansionId: Int): List<CtBlueprint> {
            calls += "bp$expansionId"
            return when (expansionId) {
                2 -> listOf(bp(1, "Portgas.D.Ace", "OP02-013"), bp(2, "Portgas.D.Ace", "OP02-013a", "Alternate Art"), bp(3, "Edward.Newgate", "OP02-001"))
                3 -> listOf(bp(4, "Luffy", "EB01-001"))
                9 -> listOf(bp(5, "Fog King", "001ul"))
                10 -> listOf(bp(6, "Black Luster Soldier", "A01"))
                11 -> listOf(bp(7, "Neo-Spacian Aqua Dolphin", "003"))
                20 -> listOf(bp(8, "Diablo", "UE20BT-001"), bp(9, "Geld", "UE20BT-001"), bp(10, "Diablo", "UE20BT-001 ★"))
                else -> emptyList()
            }
        }
        override suspend fun products(blueprintId: Int): Map<String, List<CtProduct>> = emptyMap()
    }
    private fun dir() = File.createTempFile("catalogo", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
    private fun cat(game: Game, api: FakeApi, d: File = dir()) = CtGameCatalog(game, CtGameCatalog.GAME_IDS.getValue(game), api, { "tok" }, d)

    @Test
    fun onePieceUniqueAndVariants() = runBlocking {
        val api = FakeApi(); val op = cat(Game.ONE_PIECE, api)
        // Newgate: una sola versione -> si salva da sola
        val p1 = OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "OP02-001 L")
        val one = op.identify(p1)
        assertEquals(listOf(3), one.map { it.ctBlueprintId })
        assertTrue(MatchFusion.evaluateAutoMatch(MatchFusion.fuse(one, p1).first(), p1).accepted)
        // Ace: normale e Alternate Art con lo stesso codice -> si sceglie, niente salvataggio automatico
        val p2 = OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "OP02-013 SR")
        val two = op.identify(p2)
        assertEquals(listOf(1, 2), two.map { it.ctBlueprintId })
        assertEquals("Portgas.D.Ace (Alternate Art)", two[1].name)
        assertFalse(MatchFusion.evaluateAutoMatch(MatchFusion.fuse(two, p2).first(), p2).accepted)
        // un'espansione si scarica una volta sola; mai quelle dello stesso codice di altri giochi (op02 Yu-Gi-Oh)
        assertEquals(1, api.calls.count { it == "bp2" })
        assertFalse(api.calls.contains("bp9"))
        // eb-01 su CardTrader = EB01 stampato
        assertEquals(listOf(4), op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "EB01-001")).map { it.ctBlueprintId })
    }

    @Test
    fun catalogStaysOnThePhone() = runBlocking {
        val d = dir(); val api = FakeApi()
        cat(Game.ONE_PIECE, api, d).identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "OP02-001"))
        val again = FakeApi()
        val r = CtGameCatalog(Game.ONE_PIECE, 15, again, { null }, d).identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "OP02-001"))
        assertEquals(listOf(3), r.map { it.ctBlueprintId })
        assertTrue(again.calls.isEmpty()) // senza token e senza rete: dal telefono
    }

    @Test
    fun yugiohAndUnionArena() = runBlocking {
        val api = FakeApi()
        val ygo = cat(Game.YUGIOH, api)
        assertEquals(listOf(7), ygo.identify(OcrParser.parse("POTD-EN003", Game.YUGIOH)).map { it.ctBlueprintId })
        // YGLD stampato, ygld-2020 su CardTrader
        assertEquals(listOf(6), ygo.identify(OcrParser.parse("YGLD-ENA01", Game.YUGIOH)).map { it.ctBlueprintId })
        val ua = cat(Game.UNION_ARENA, api)
        // stesso numero per Diablo e Geld (e Diablo ★): decide il nome letto in alto
        val geld = ua.identify(OcrParser.parse("UE20BT/TSK-1-001", Game.UNION_ARENA, namePriorityText = "Geld"))
        assertEquals(listOf(9), geld.map { it.ctBlueprintId })
        val diablo = ua.identify(OcrParser.parse("UE20BT/TSK-2-001", Game.UNION_ARENA, namePriorityText = "Diablo"))
        assertEquals(listOf(8, 10), diablo.map { it.ctBlueprintId }) // normale e ★: si sceglie
    }
}
