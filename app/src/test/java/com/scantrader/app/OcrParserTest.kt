package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.scan.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserTest {
    @Test
    fun parsesMtgCollectorAndSet() {
        val text = """
            Mountain
            Basic Land — Mountain
            64 • BLB • EN
        """.trimIndent()
        val parsed = OcrParser.parse(text, Game.MTG)
        assertEquals("Mountain", parsed.nameGuess)
        assertEquals("64", parsed.collectorNumber)
        assertEquals("blb", parsed.setCode)
    }

    @Test
    fun parsesMtgOcrZeroAsOAndFraLang() {
        // Real device OCR: `RO162 | FRA *EN` instead of `0162 • FRA • EN`.
        val top = "Vindictive Triumph"
        val bottom = "RO162 | Story Spotlight | FRA *EN >loHAN GRENIER"
        val parsed = OcrParser.parse(
            rawText = "Exile target creature",
            game = Game.MTG,
            bottomPriorityText = bottom,
            namePriorityText = top,
        )
        assertEquals("Vindictive Triumph", parsed.nameGuess)
        assertEquals("162", parsed.collectorNumber)
        assertEquals("fra", parsed.setCode)
    }

    @Test
    fun parsesMtgSpacedRoCollector() {
        val bottom = "R O113 | FRA IT ALIX BRANWYN"
        val parsed = OcrParser.parse(
            rawText = "x",
            game = Game.MTG,
            bottomPriorityText = bottom,
            namePriorityText = "Plasmatrice del Simulacro",
        )
        assertEquals("113", parsed.collectorNumber)
        assertEquals("fra", parsed.setCode)
    }

    @Test
    fun normalizesRoCollectorOcr() {
        assertTrue(OcrParser.normalizeMtgBottomOcr("R O028").contains("0028"))
        assertTrue(OcrParser.normalizeMtgBottomOcr("RO162").contains("0162"))
    }

    @Test
    fun doesNotTreatPowerToughnessAsMtgCollector() {
        // Real failure: +1/+1 in rules became cn=1 → Emrakul instead of #028.
        val top = "Divinatrice della Vittoria"
        val bottom = """
            creatura prende +1/+1
            fino alla fine del turno.
            R O028
            FRA IT JUSTYNA DURA
        """.trimIndent()
        val parsed = OcrParser.parse(
            rawText = "Questa creatura prende +1/+1 fino alla fine del turno.",
            game = Game.MTG,
            bottomPriorityText = bottom,
            namePriorityText = top,
        )
        assertEquals("Divinatrice della Vittoria", parsed.nameGuess)
        assertEquals("28", parsed.collectorNumber)
        assertEquals("fra", parsed.setCode)
        assertTrue(parsed.setCode != "pren")
    }

    @Test
    fun parsesModernItalianBottomLineWithLeadingZeros() {
        val full = """
            Entrata Scortese
            Creatura — Orco
        """.trimIndent()
        val bottom = "0181 • DFT • IT"
        val parsed = OcrParser.parse(full, Game.MTG, bottom)
        assertEquals("Entrata Scortese", parsed.nameGuess)
        assertEquals("181", parsed.collectorNumber)
        assertEquals("dft", parsed.setCode)
    }

    @Test
    fun parsesMtgSpacedBottomLine() {
        val bottom = "0123 DSK IT"
        val parsed = OcrParser.parse("Some Name", Game.MTG, bottom)
        assertEquals("123", parsed.collectorNumber)
        assertEquals("dsk", parsed.setCode)
    }

    @Test
    fun prefersBottomStripOverNoisyBody() {
        val body = """
            Random 999 text
            50 something
        """.trimIndent()
        val bottom = "42 • MKM • EN"
        val parsed = OcrParser.parse(body, Game.MTG, bottom)
        assertEquals("42", parsed.collectorNumber)
        assertEquals("mkm", parsed.setCode)
    }

    @Test
    fun setCodeCandidatesIncludeOcrSwaps() {
        val variants = OcrParser.setCodeCandidates("0ft")
        assertTrue(variants.contains("0ft"))
        assertTrue(variants.contains("oft"))
    }

    @Test
    fun parsesPokemonCollectorFraction() {
        val text = """
            Charmander
            50 HP
            004/165
            MEW
        """.trimIndent()
        val parsed = OcrParser.parse(text, Game.POKEMON)
        assertEquals("Charmander", parsed.nameGuess)
        assertEquals("4", parsed.collectorNumber)
        assertNotNull(parsed.setCode)
    }

    @Test
    fun parsesItalianPokemonTeamRocketMuk() {
        val full = """
            Muk del Team Rocket
            Fase 1
            150 HP
        """.trimIndent()
        val bottom = "DRI 124/182"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Muk del Team Rocket", parsed.nameGuess)
        assertEquals("124", parsed.collectorNumber)
        assertEquals("dri", parsed.setCode)
        assertEquals(182, parsed.setCardCount)
    }

    @Test
    fun cleansLatinNameAndPrefersStrictFractionOverGluedSet() {
        val full = """
            Kilowattrel a Kisara1204
            Pescalampo
            Fulmine Mach
            くilowattrelaKharn120ヶ
        """.trimIndent()
        // Real-world OCR often glues set+number: Uor2/217 instead of ASC 072/217.
        val bottom = "Uor2/217\n072/217"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("72", parsed.collectorNumber)
        assertEquals(217, parsed.setCardCount)
        assertTrue(parsed.nameGuess!!.contains("Kilowattrel", ignoreCase = true))
        assertTrue(parsed.nameGuess!!.none { it.code > 0x2E80 })
    }

    @Test
    fun extractsItalianOwnerPokemonNameFromBody() {
        val full = """
            Sievolve da Wattrel di Kissara
            N. 0941 Pokemon Fregata
            Kilowattrel di Kissara
            120 HP
        """.trimIndent()
        val bottom = "ASC 072/217"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Kilowattrel di Kissara", parsed.nameGuess)
        assertEquals("72", parsed.collectorNumber)
        assertEquals("asc", parsed.setCode)
        assertEquals(217, parsed.setCardCount)
    }

    @Test
    fun italianBaseKadabraNotCostoDiRitirata() {
        val full = """
            Metti Kadabra sul Pokemon
            Evolve da Abra
            60 PV
            Kadabra
            FASE I
            Ripresa Scarta una carta Energia assegnata a Kadabra
            costo di ritirata
            Illus. Ken Sugimori
        """.trimIndent()
        val bottom = "32/102 ★"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Kadabra", parsed.nameGuess)
        assertEquals("32", parsed.collectorNumber)
        assertEquals(102, parsed.setCardCount)
    }

    @Test
    fun italianBaseKadabraNotMalDiTestaFlavor() {
        val full = """
            Pokémon psichico. Lunghezza: 1,3 m; Peso: 56,5 kg
            Ripresa Scarta una carta Energia assegnata a Kadabra per poter usare questo attacco.
            Togli tutti i segnalini danno da Kadabra.
            Emette dal corpo speciali onde alfa che causano mal di testa a chiunque gli sia vicino.
        """.trimIndent()
        val bottom = "32/102"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Kadabra", parsed.nameGuess)
        assertEquals("32", parsed.collectorNumber)
        assertEquals(102, parsed.setCardCount)
    }

    @Test
    fun doesNotUseEvolveFromParentAsName_realOcrNoise() {
        val full = """
            Sievalve da Scorbunny
            N 0814 Pokemon Conigio Aiezza
            Calciobasso
            Fuoco Continuo
            ritirata
            resistenza
            Raboot
        """.trimIndent()
        val bottom = "037/2\n217"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Raboot", parsed.nameGuess)
        assertEquals("37", parsed.collectorNumber)
        assertEquals(217, parsed.setCardCount)
    }

    @Test
    fun skipsAttackNameWhenTitleMissing_usesTruncatedFraction() {
        val full = """
            ve da Scorbunny
            Calciobasso
            Fuoco Continuo
        """.trimIndent()
        val bottom = "037/2"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        // Evolve-from / attacks must not become the title.
        assertTrue(parsed.nameGuess == null || !parsed.nameGuess!!.contains("Scorbunny", ignoreCase = true))
        assertTrue(parsed.nameGuess == null || !parsed.nameGuess!!.equals("Calciobasso", ignoreCase = true))
        assertEquals("37", parsed.collectorNumber)
    }

    @Test
    fun recoversSetCountNearTruncatedFraction() {
        val full = "Raboot\nHP 70\nsome text 217 more"
        val bottom = "x2 | 037/2"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Raboot", parsed.nameGuess)
        assertEquals("37", parsed.collectorNumber)
        assertEquals(217, parsed.setCardCount)
    }

    @Test
    fun prefersOwnerTitleOverFlavorDiPhrase() {
        val full = """
            PS
            Staravia di Ubaldo
            Sievolve da Starly di Ubaldo
            Alabattito
            Ala Tagliente
            ritirata
            resistenza
            Hal'abitudine di riunirsi in stormi e di spartire il cibo.
        """.trimIndent()
        val bottom = "169/217"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Staravia di Ubaldo", parsed.nameGuess)
        assertEquals("169", parsed.collectorNumber)
        assertEquals(217, parsed.setCardCount)
    }

    @Test
    fun extractsWeedleAfterPvWithoutFase() {
        val full = """
            40 PV
            Pokémon Base
            Weedle
            émon bruco peloso. Lunghezza: 0,3 m; Peso: 3,2 kg.
            Velenospina
            costo di ritirata
            E nutre di foglie e di erba tenera.
        """.trimIndent()
        val bottom = "69/102"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Weedle", parsed.nameGuess)
        assertEquals("69", parsed.collectorNumber)
    }

    @Test
    fun ignoresBogusNearFractionSetCode() {
        val full = "Slurpuff\nSievolve da Swirlix\nMagicolpo\nritirata"
        val bottom = "094/2 | Jmi Yoshid | D"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Slurpuff", parsed.nameGuess)
        assertEquals("94", parsed.collectorNumber)
        assertTrue(parsed.setCode == null || parsed.setCode == "asc" || parsed.setCode!!.length >= 2)
        assertTrue(parsed.setCode != "do")
    }

    @Test
    fun doesNotUseAttackPhraseAsPokemonName() {
        val full = """
            Noibat
            Pokémon Suono
            Trasporto di Cianfrusaglie
            Morso
            ritirata
            resistenza
        """.trimIndent()
        val bottom = "106/217"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Noibat", parsed.nameGuess)
        assertEquals("106", parsed.collectorNumber)
        assertEquals(217, parsed.setCardCount)
    }

    @Test
    fun topNameStripBeatsAbilityInFullCard() {
        val full = """
            Abilità
            Trasporto di Cianfrusaglie
            Una sola volta durante il tuo turno…
            Morso
            ritirata
        """.trimIndent()
        val top = "Noibat\n70 PS"
        val bottom = "ASC 106/217"
        val parsed = OcrParser.parse(
            rawText = full,
            game = Game.POKEMON,
            bottomPriorityText = bottom,
            namePriorityText = top,
        )
        assertEquals("Noibat", parsed.nameGuess)
        assertEquals("106", parsed.collectorNumber)
        assertEquals("asc", parsed.setCode)
        assertEquals(217, parsed.setCardCount)
        assertTrue(OcrParser.looksLikeAbilityOrAttackName("Trasporto di Cianfrusaglie"))
    }

    @Test
    fun splitsNameGluedToCollectorNumber() {
        val top = "Noibat106"
        val full = """
            Noibat106/217
            Abilità
            Trasporto di Cianfrusaglie
        """.trimIndent()
        val bottom = "106/217"
        val parsed = OcrParser.parse(
            rawText = full,
            game = Game.POKEMON,
            bottomPriorityText = bottom,
            namePriorityText = top,
        )
        assertEquals("Noibat", parsed.nameGuess)
        assertEquals("106", parsed.collectorNumber)
        assertEquals(217, parsed.setCardCount)
    }

    @Test
    fun splitsSmolivGluedToNumberAndSet() {
        val top = "Smoliv21 DRI"
        val bottom = "DRI 021/182"
        val parsed = OcrParser.parse(
            rawText = "Smoliv21\nAbilità\nFotosintesi",
            game = Game.POKEMON,
            bottomPriorityText = bottom,
            namePriorityText = top,
        )
        assertEquals("Smoliv", parsed.nameGuess)
        assertEquals("21", parsed.collectorNumber)
        assertEquals("dri", parsed.setCode)
        assertEquals(182, parsed.setCardCount)
    }

    @Test
    fun keepsRealOwnerTitleFromTopStrip() {
        val top = "Kilowattrel di Kissara\n120 HP"
        val bottom = "ASC 072/217"
        val parsed = OcrParser.parse(
            rawText = "Sievolve da Wattrel\nPescalampo\nritirata",
            game = Game.POKEMON,
            bottomPriorityText = bottom,
            namePriorityText = top,
        )
        assertEquals("Kilowattrel di Kissara", parsed.nameGuess)
        assertEquals("72", parsed.collectorNumber)
        assertEquals("asc", parsed.setCode)
    }

    @Test
    fun headerCutsBeforeAbilityLabel() {
        val full = """
            Smoliv
            Abilità
            Harvest
            attacco
            ritirata
        """.trimIndent()
        val parsed = OcrParser.parse(full, Game.POKEMON, "DRI 021/182")
        assertEquals("Smoliv", parsed.nameGuess)
        assertTrue(parsed.nameGuess != "Harvest")
    }

    @Test
    fun identityReadyForFastPathNeedsCnAndSet() {
        assertTrue(
            OcrParser.identityReadyForFastPath(
                com.scantrader.app.data.model.ParsedCardText(
                    nameGuess = null,
                    collectorNumber = "28",
                    setCode = "fra",
                ),
            ),
        )
        assertTrue(
            OcrParser.identityReadyForFastPath(
                com.scantrader.app.data.model.ParsedCardText(
                    nameGuess = "Smoliv",
                    collectorNumber = "21",
                    setCode = "dri",
                ),
            ),
        )
        assertTrue(
            !OcrParser.identityReadyForFastPath(
                com.scantrader.app.data.model.ParsedCardText(
                    nameGuess = "Mountain",
                    collectorNumber = "64",
                    setCode = null,
                ),
            ),
        )
        assertTrue(
            !OcrParser.identityReadyForFastPath(
                com.scantrader.app.data.model.ParsedCardText(
                    nameGuess = "Emrakul",
                    collectorNumber = null,
                    setCode = "fra",
                ),
            ),
        )
        // Old MTG fraction denominator must NOT count as a set code.
        assertTrue(
            !OcrParser.identityReadyForFastPath(
                com.scantrader.app.data.model.ParsedCardText(
                    nameGuess = "Trasformazione Raccapricciante",
                    collectorNumber = "74",
                    setCode = "165",
                ),
            ),
        )
    }

    @Test
    fun doesNotTreatMtgSetSizeAsSetCode() {
        val bottom = "Tyler Jacobson\n© 2014 Wizards of the Coast 74/165"
        val parsed = OcrParser.parse(
            rawText = "Trasformazione Raccapricciante 2",
            game = Game.MTG,
            bottomPriorityText = bottom,
            namePriorityText = "Trasformazione Raccapricciante 2",
        )
        assertEquals("Trasformazione Raccapricciante", parsed.nameGuess)
        assertEquals("74", parsed.collectorNumber)
        assertTrue(parsed.setCode == null || parsed.setCode != "165")
    }

    @Test
    fun oldFrameCopyrightIsNotASetAndMisreadCollectorIsFixed() {
        // Device OCR: copyright year glued to a digit, N read instead of 8, real cn is 83/150.
        val full = """
            Pioppo Tagliaerhacce
            Creatura — Albero
            diversa dal Pioppo Tagliaerbacce prende
        """.trimIndent()
        val bottom = "14&01993-20O8 Wizards of the Cast, Inc N3/150"
        val parsed = OcrParser.parse(
            rawText = full,
            game = Game.MTG,
            bottomPriorityText = bottom,
            namePriorityText = "Pioppo Tagliaerhacce",
        )
        assertEquals("Pioppo Tagliaerbacce", parsed.nameGuess)
        assertEquals("83", parsed.collectorNumber)
        assertNull(parsed.setCode)
        assertTrue(!OcrParser.identityReadyForFastPath(parsed))
    }

    @Test
    fun oldFrameCenterFractionIgnoresCopyrightYear() {
        val bottom = "© 1993-2008 Wizards of the Coast 83/150"
        val parsed = OcrParser.parse("Llanowar Elves", Game.MTG, bottom)
        assertEquals("Llanowar Elves", parsed.nameGuess)
        assertEquals("83", parsed.collectorNumber)
        assertNull(parsed.setCode)
    }

    @Test
    fun modernBottomLineOutranksOldFractionInTheSameBand() {
        val bottom = """
            83/150
            0181 • DFT • IT
        """.trimIndent()
        val parsed = OcrParser.parse("Entrata Scortese", Game.MTG, bottom)
        assertEquals("181", parsed.collectorNumber)
        assertEquals("dft", parsed.setCode)
    }

    @Test
    fun setCodesWithTwoDigitsStayValid() {
        val parsed = OcrParser.parse("Some Name", Game.MTG, "10 • 10E • EN")
        assertEquals("10", parsed.collectorNumber)
        assertEquals("10e", parsed.setCode)
        val double = OcrParser.parse("Some Name", Game.MTG, "2 • 2X2 • EN")
        assertEquals("2", double.collectorNumber)
        assertEquals("2x2", double.setCode)
    }

    @Test
    fun cleanMtgNameGuessStripsTrailingDigits() {
        assertEquals(
            "Trasformazione Raccapricciante",
            OcrParser.cleanMtgNameGuess("Trasformazione Raccapricciante 2"),
        )
        assertEquals("Mountain", OcrParser.cleanMtgNameGuess("Mountain"))
    }

    @Test
    fun stripsManaCostFromTheTitle() {
        assertEquals("Sol Ring", OcrParser.cleanMtgNameGuess("Sol Ring"))
        assertEquals("Llanowar Elves", OcrParser.cleanMtgNameGuess("Llanowar Elves G"))
        assertEquals("Shivan Dragon", OcrParser.cleanMtgNameGuess("Shivan Dragon 4RR"))
        assertEquals("Counterspell", OcrParser.cleanMtgNameGuess("Counterspell UU"))
        assertEquals("Pioppo Tagliaerbacce", OcrParser.cleanMtgNameGuess("Pioppo Tagliaerbacce 2G"))
        assertEquals("Counterspell", OcrParser.cleanMtgNameGuess("Counterspell {U}{U}"))
        assertEquals("Shivan Dragon", OcrParser.cleanMtgNameGuess("4RR Shivan Dragon"))
        assertEquals("Llanowar Elves", OcrParser.parse("Llanowar Elves G", Game.MTG).nameGuess)
        assertEquals("Shivan Dragon", OcrParser.parse("Shivan Dragon 4RR", Game.MTG).nameGuess)
        assertEquals("Counterspell", OcrParser.parse("Counterspell UU", Game.MTG).nameGuess)
        assertEquals(
            "Pioppo Tagliaerbacce",
            OcrParser.parse("Pioppo Tagliaerbacce 2G", Game.MTG).nameGuess,
        )
        // Italian Morningtide card: mana cost {1}{B} read as 1W / 1 / 1B on the title.
        assertEquals(
            "Stendardiere del Lancio della Rana",
            OcrParser.cleanMtgNameGuess("Stendardiere del Lancio della Rana 1W"),
        )
        assertEquals(
            "Stendardiere del Lancio della Rana",
            OcrParser.cleanMtgNameGuess("Stendardiere del Lancio della Rana 1"),
        )
        assertEquals(
            "Stendardiere del Lancio della Rana",
            OcrParser.parse("Stendardiere del Lancio della Rana 1B", Game.MTG).nameGuess,
        )
        assertEquals(listOf("Shivan Dragon"), OcrParser.mtgNameAttempts("Shivan Dragon 4RR"))
        assertEquals(listOf("Counterspell"), OcrParser.mtgNameAttempts("Counterspell UU"))
    }

    @Test
    fun ignoresPokemonHpOnTheTopRight() {
        val ps = OcrParser.parse("Pikachu PS 120", Game.POKEMON, "025/165")
        assertEquals("Pikachu", ps.nameGuess)
        val hp = OcrParser.parse("Charmander HP 120", Game.POKEMON, "004/165")
        assertEquals("Charmander", hp.nameGuess)
        val above = OcrParser.parse("PS 70\nNoibat", Game.POKEMON, "106/217")
        assertEquals("Noibat", above.nameGuess)
    }
}
