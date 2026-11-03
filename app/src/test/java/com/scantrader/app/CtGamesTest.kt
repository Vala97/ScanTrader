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
        assertEquals(CtCode("eb04", "EB04-021"), OcrParser.ctCode(Game.ONE_PIECE, "Igaram EB04-021"))
        assertEquals(CtCode("eb04", "EB04-026"), OcrParser.ctCode(Game.ONE_PIECE, "Bluegrass EB04-026"))
        assertEquals(CtCode("eb04", "EB04-001"), OcrParser.ctCode(Game.ONE_PIECE, "EB04-001"))
        assertEquals(CtCode("eb05", "EB05-010"), OcrParser.ctCode(Game.ONE_PIECE, "EB05-010"))
        assertEquals(CtCode("op02", "OP02-013"), OcrParser.ctCode(Game.ONE_PIECE, "0P02-O13"))
        assertEquals(CtCode("promo", "P-041"), OcrParser.ctCode(Game.ONE_PIECE, "P-041"))
        assertEquals(CtCode("op02", "OP02-013"), OcrParser.ctCode(Game.ONE_PIECE, "OP02013"))
        assertEquals(CtCode("st10", "ST10-014"), OcrParser.ctCode(Game.ONE_PIECE, "ST10 014"))
        assertNull(OcrParser.ctCode(Game.ONE_PIECE, "7000 SPECIAL"))
        // un codice a tre cifre resta una single anche se l'effetto parla di DON!!
        assertEquals(CtCode("eb03", "EB03-053"), OcrParser.ctCode(Game.ONE_PIECE, "rested DON!! card to your Leader\nEB03-053"))
        assertNull(OcrParser.ctCode(Game.ONE_PIECE, "OP05"))
        assertEquals(CtCode("op05", "OP-05", don = true), OcrParser.ctCode(Game.ONE_PIECE, "DON!! CARD\nYour Turn +1000\nOP-05"))
        assertEquals(CtCode("eb05", "EB-05", don = true), OcrParser.ctCode(Game.ONE_PIECE, "Your Turn +1000\nEB05"))
        assertEquals(CtCode("eb05", "EB-05MG", don = true), OcrParser.ctCode(Game.ONE_PIECE, "EB-05mg"))
        assertEquals(CtCode("eb05", "EB-05M", don = true), OcrParser.ctCode(Game.ONE_PIECE, "EB-05m"))
        assertEquals(CtCode("eb03", "EB-03-NAMI-G", don = true), OcrParser.ctCode(Game.ONE_PIECE, "EB-03-NAMI-G"))
        assertEquals(CtCode("eb03", "EB-03-NAMI", don = true), OcrParser.ctCode(Game.ONE_PIECE, "EB-03-NAMI"))
        assertEquals(CtCode("prb", "PRB-LUFFY-G", don = true), OcrParser.ctCode(Game.ONE_PIECE, "PRB-LUFFY-G"))
        assertEquals(CtCode("prb", "PRB-RL-G", don = true), OcrParser.ctCode(Game.ONE_PIECE, "PRB-RL_G"))
        assertEquals(CtCode("dp09", "DP-09", don = true), OcrParser.ctCode(Game.ONE_PIECE, "DP-09"))
        assertEquals(CtCode("op14", "OP-14G", don = true), OcrParser.ctCode(Game.ONE_PIECE, "OP-14g"))
        assertEquals(CtCode("op09", "OP-09-BGY", don = true), OcrParser.ctCode(Game.ONE_PIECE, "OP-09-BGY"))
        assertEquals(CtCode("don", "DON", don = true), OcrParser.ctCode(Game.ONE_PIECE, "DON!! CARD\nYour Turn +1000"))
        val joined = OcrParser.parse("DON!! CARD\nYour Turn +1000", Game.ONE_PIECE, bottomPriorityText = "OP-05")
        assertEquals("op05", joined.setCode)
        assertEquals("OP-05", joined.collectorNumber)
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
    fun imageChoosesTheVersion() {
        fun card(bp: Int, n: String, v: Float?, ocr: Float) = com.scantrader.app.data.model.IdentifiedCard(
            game = Game.ONE_PIECE, name = "Kaido $bp", setCode = "st04", setName = "ST-04", collectorNumber = n,
            ctBlueprintId = bp, visualScore = v, ocrScore = ocr,
        )
        val withCode = OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "ST04-003 SR")
        val noCode = OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "")
        fun gate(p: com.scantrader.app.data.model.ParsedCardText, vararg c: com.scantrader.app.data.model.IdentifiedCard): MatchFusion.AutoMatchDecision {
            val fused = MatchFusion.fuse(c.toList(), p)
            return MatchFusion.evaluateAutoMatch(fused.first(), p, fused)
        }
        val v = MatchFusion.CT_VISUAL_MIN + 0.05f
        // codice letto, due versioni: l'immagine netta sceglie
        val clear = gate(withCode, card(1, "ST04-003", v, 0.91f), card(2, "ST04-003p", v - MatchFusion.CT_VISUAL_MARGIN - 0.02f, 0.9f))
        assertTrue(clear.reason, clear.accepted)
        assertTrue(MatchFusion.allowsAutoSave(clear))
        // due versioni quasi uguali all'immagine (timbro Pre-release): si sceglie a mano
        assertFalse(gate(withCode, card(1, "ST04-003", v, 0.91f), card(2, "ST04-003p", v - 0.01f, 0.9f)).accepted)
        // codice non letto: solo un'immagine molto netta
        val s = MatchFusion.CT_VISUAL_STRONG + 0.02f
        assertTrue(gate(noCode, card(1, "ST04-003", s, 0f), card(9, "OP01-001", s - MatchFusion.CT_VISUAL_MARGIN_STRONG - 0.02f, 0f)).accepted)
        assertFalse(gate(noCode, card(1, "ST04-003", MatchFusion.CT_VISUAL_STRONG - 0.03f, 0f), card(9, "OP01-001", 0.5f, 0f)).accepted)
        // codice letto che non coincide con l'immagine: mai da sola
        assertFalse(gate(withCode, card(9, "OP01-001", 0.97f, 0f)).accepted)
        // EB-03 letto sul set: Nami e Robin sono due tipi, l'immagine sceglie
        fun don(bp: Int, n: String, visual: Float) = com.scantrader.app.data.model.IdentifiedCard(
            game = Game.ONE_PIECE, name = "DON!! ($bp)", setCode = "eb03", setName = "EB-03", collectorNumber = n,
            catalogName = "DON!!", ctBlueprintId = bp, visualScore = visual, ocrScore = 0.9f,
        )
        val family = OcrParser.parse("Your Turn +1000\nEB-03", Game.ONE_PIECE)
        val picked = gate(family, don(72, "EB-03-NAMI", v), don(74, "EB-03-ROBIN", v - MatchFusion.CT_VISUAL_MARGIN - 0.02f))
        assertTrue(picked.reason, picked.accepted)
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
            CtExpansion(44, 15, "eb04", "EB-04: Egghead Crisis"), CtExpansion(14, 15, "op14", "OP-14: The Azure Sea's Seven"),
            CtExpansion(15, 15, "op15", "OP-15"),
            CtExpansion(5, 15, "op05", "OP-05"), CtExpansion(55, 15, "eb05", "EB-05"),
            CtExpansion(33, 15, "eb-03", "EB-03"), CtExpansion(45, 15, "prb01", "PRB-01"),
            CtExpansion(17, 15, "op17", "OP-17"), CtExpansion(90, 15, "oppr", "One Piece Promos"),
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
                44 -> listOf(bp(44, "Egghead Crisis Booster", ""))
                14 -> listOf(bp(21, "Igaram", "EB04-021"), bp(26, "Bluegrass", "EB04-026"), bp(11, "Other", "EB04-011"))
                15 -> listOf(bp(101, "Jewelry Bonney", "EB04-001"))
                5 -> listOf(bp(50, "DON!!", "OP-05", "Luffy vs Kaido"), bp(51, "Monkey.D.Luffy", "OP05-001"))
                55 -> listOf(
                    bp(60, "DON!!", "EB-05"), bp(61, "DON!!", "EB-05g", "Gold Foil"),
                    bp(62, "DON!!", "EB-05m", "Maid Cafè"), bp(63, "DON!!", "EB-05mg", "Maid Cafè | Gold Foil"),
                )
                33 -> listOf(
                    bp(70, "DON!!", "EB-03"), bp(71, "DON!!", "EB-03-G", "Gold Foil"),
                    bp(72, "DON!!", "EB-03-NAMI", "Nami"), bp(73, "DON!!", "EB-03-NAMI-G", "Nami | Gold Foil"),
                    bp(74, "DON!!", "EB-03-ROBIN", "Robin"),
                )
                45 -> listOf(
                    bp(80, "DON!!", "PRB-LUFFY", "Luffy"), bp(81, "DON!!", "PRB-LUFFY-G", "Luffy | Gold Foil"),
                    bp(82, "DON!!", "PRB-ZORO", "Zoro"),
                )
                17 -> listOf(
                    bp(90, "DON!!", "OP-17g", "Luffy | Gold Foil"), bp(91, "DON!!", "OP-17g", "4 Emperors| Gold Foil"),
                    bp(92, "DON!!", "OP17", "Luffy"),
                )
                90 -> listOf(bp(95, "DON!!", "DP-01", "Donquixote Rosinante | Special DON!! Card Pack"))
                9 -> listOf(bp(5, "Fog King", "001ul"))
                10 -> listOf(bp(6, "Black Luster Soldier", "A01"))
                11 -> listOf(bp(7, "Neo-Spacian Aqua Dolphin", "003"))
                20 -> listOf(bp(8, "Diablo", "UE20BT-001"), bp(9, "Geld", "UE20BT-001"), bp(10, "Diablo", "UE20BT-001 ★"))
                else -> emptyList()
            }
        }
        override suspend fun products(blueprintId: Int, language: String?, foil: Boolean?): Map<String, List<CtProduct>> = emptyMap()
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
        // la versione normale (numero esatto) resta la prima anche dopo la fusione (Kaido ST04-003 / ST04-003p)
        assertEquals(1, MatchFusion.fuse(two.reversed(), p2).first().ctBlueprintId)
        // un'espansione si scarica una volta sola; mai quelle dello stesso codice di altri giochi (op02 Yu-Gi-Oh)
        assertEquals(1, api.calls.count { it == "bp2" })
        assertFalse(api.calls.contains("bp9"))
        // EB-01 resta nella sua espansione. EB-04 è sigillato: 021/026 in OP-14, 001 in OP-15.
        assertEquals(listOf(4), op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "EB01-001")).map { it.ctBlueprintId })
        val igaram = op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "EB04-021"))
        assertEquals(listOf(21), igaram.map { it.ctBlueprintId })
        assertEquals("Igaram", igaram.first().name)
        assertFalse(api.calls.contains("bp15"))
        assertEquals(listOf(26), op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "EB04-026")).map { it.ctBlueprintId })
        assertEquals(listOf(101), op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "EB04-001")).map { it.ctBlueprintId })
        assertTrue(api.calls.contains("bp15"))
    }

    @Test
    fun onePieceDonTypes() = runBlocking {
        val api = FakeApi(); val op = cat(Game.ONE_PIECE, api)
        val setDon = op.identify(OcrParser.parse("DON!! CARD\nYour Turn +1000\nOP-05", Game.ONE_PIECE))
        assertEquals(listOf(50), setDon.map { it.ctBlueprintId })
        assertEquals("DON!! (Luffy vs Kaido)", setDon.first().name)
        assertTrue(MatchFusion.evaluateAutoMatch(MatchFusion.fuse(setDon, OcrParser.parse("OP-05", Game.ONE_PIECE)).first(), OcrParser.parse("DON!! CARD\nYour Turn +1000\nOP-05", Game.ONE_PIECE)).accepted)
        val eb05 = op.identify(OcrParser.parse("Your Turn +1000\nEB-05", Game.ONE_PIECE))
        assertEquals(setOf(60, 61, 62, 63), eb05.map { it.ctBlueprintId }.toSet())
        assertFalse(MatchFusion.evaluateAutoMatch(MatchFusion.fuse(eb05, OcrParser.parse("Your Turn +1000\nEB-05", Game.ONE_PIECE)).first(), OcrParser.parse("Your Turn +1000\nEB-05", Game.ONE_PIECE)).accepted)
        val nami = op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "EB-03-NAMI"))
        assertEquals(listOf(72, 73), nami.map { it.ctBlueprintId })
        assertEquals("DON!! (Nami | Gold Foil)", nami[1].name)
        val luffy = op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "PRB-LUFFY"))
        assertEquals(listOf(80, 81), luffy.map { it.ctBlueprintId })
        assertFalse(api.calls.contains("bp45") && luffy.any { it.ctBlueprintId == 82 })
        val golds = op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "OP-17g"))
        assertEquals(setOf(90, 91), golds.map { it.ctBlueprintId }.toSet())
        assertEquals(listOf(95), op.identify(OcrParser.parse("", Game.ONE_PIECE, bottomPriorityText = "DP-01")).map { it.ctBlueprintId })
        assertTrue(api.calls.contains("bp90"))
        assertEquals(emptyList<Int>(), op.identify(OcrParser.parse("Your Turn +1000", Game.ONE_PIECE)).map { it.ctBlueprintId })
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
