package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.FrameNameGate
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchFusionTest {
    private val parsed = ParsedCardText(
        nameGuess = "Lightning Bolt",
        collectorNumber = "161",
        setCode = "m21",
    )

    @Test
    fun ranksVisualWinnerAboveWeakOcrHit() {
        val wrongSet = card("Wrong Print", "znr", "161", ocr = 0.98f, visual = 0.30f)
        val rightArt = card("Lightning Bolt", "m21", "161", ocr = 0.70f, visual = 0.92f)
        val ranked = MatchFusion.fuse(listOf(wrongSet, rightArt), parsed)
        assertEquals("Lightning Bolt", ranked.first().name)
        assertEquals("m21", ranked.first().setCode)
        assertTrue((ranked.first().matchConfidence ?: 0f) > (ranked.last().matchConfidence ?: 0f))
    }

    @Test
    fun autoMatchRequiresStrongEvidence() {
        val weak = card("Maybe", "m21", "161", ocr = 0.50f, visual = 0.55f)
        assertFalse(MatchFusion.isConfidentAutoMatch(MatchFusion.score(weak, parsed)))

        val strong = card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f)
        assertTrue(MatchFusion.isConfidentAutoMatch(MatchFusion.score(strong, parsed)))
    }

    @Test
    fun exactOcrWithoutVisualCanAutoMatch() {
        val exact = card("Lightning Bolt", "m21", "161", ocr = 0.98f, visual = null)
        val scored = MatchFusion.score(exact, parsed)
        val decision = MatchFusion.evaluateAutoMatch(scored, parsed)
        assertTrue(decision.accepted)
        assertTrue(decision.strict)
        assertTrue(MatchFusion.allowsAutoSave(decision))
    }

    @Test
    fun strictPokemonHitIsRejectedWhenTheFrameNameDisagrees() {
        val machop = ParsedCardText(nameGuess = "Machop", collectorNumber = null, setCode = null, setCardCount = 102)
        val abra = IdentifiedCard(
            game = Game.POKEMON,
            name = "Abra",
            setCode = "base1",
            setName = "Base",
            collectorNumber = "43",
            tcgdexId = "base1-43",
            ocrScore = 1f,
            visualScore = null,
            matchConfidence = 1f,
        )
        val decision = MatchFusion.evaluateAutoMatch(abra, machop)
        assertFalse(decision.accepted)
        assertFalse(decision.strict)
        assertTrue(decision.nameConflict)
        assertFalse(MatchFusion.allowsAutoSave(decision))

        val agreed = MatchFusion.evaluateAutoMatch(
            abra.copy(name = "Abra"),
            ParsedCardText("Abra", "43", "base", 102),
        )
        assertTrue(agreed.accepted)
        assertTrue(agreed.strict)
        assertTrue(agreed.nameOk)
    }

    @Test
    fun strictMtgIdHitIsRejectedWhenTheNameDisagrees() {
        val parsed = ParsedCardText(
            nameGuess = "Ravening Warg",
            collectorNumber = "36",
            setCode = "hob",
        )
        val elrond = IdentifiedCard(
            game = Game.MTG,
            name = "Elrond, Moon-Reader",
            setCode = "hob",
            setName = "The Hobbit",
            collectorNumber = "36",
            scryfallId = "elrond",
            ocrScore = 0.98f,
            visualScore = null,
            matchConfidence = 0.98f,
        )
        val decision = MatchFusion.evaluateAutoMatch(elrond, parsed)
        assertFalse(decision.accepted)
        assertFalse(decision.strict)
        assertFalse(decision.nameOk)
        assertTrue(decision.nameConflict)
        assertEquals("nameConflict name=Ravening Warg top=Elrond, Moon-Reader", decision.reason)
        assertFalse(MatchFusion.allowsAutoSave(decision))

        val warg = elrond.copy(name = "Ravening Warg", collectorNumber = "80", scryfallId = "warg")
        val agreed = MatchFusion.evaluateAutoMatch(warg, parsed.copy(collectorNumber = "80"))
        assertTrue(agreed.accepted)
        assertTrue(agreed.strict)
        assertTrue(agreed.nameOk)
        assertFalse(agreed.nameConflict)
        assertTrue(MatchFusion.allowsAutoSave(agreed))
    }

    @Test
    fun goblinTownPartialAgreesAndNcharDoesNot() {
        val goblinTown = IdentifiedCard(
            game = Game.MTG,
            name = "Down, Down to Goblin-town",
            setCode = "hob",
            setName = "The Hobbit",
            collectorNumber = "65",
            scryfallId = "goblin-town",
            ocrScore = 0.98f,
            visualScore = null,
            matchConfidence = 0.98f,
        )
        val partial = ParsedCardText(
            nameGuess = "Goblir",
            collectorNumber = "65",
            setCode = "hob",
        )
        val agreed = MatchFusion.evaluateAutoMatch(goblinTown, partial)
        assertTrue(agreed.accepted)
        assertTrue(agreed.strict)
        assertTrue(agreed.nameOk)
        assertFalse(agreed.nameConflict)
        assertTrue(MatchFusion.allowsAutoSave(agreed))

        val town = MatchFusion.evaluateAutoMatch(
            goblinTown,
            partial.copy(nameGuess = "Goblin-town"),
        )
        assertTrue(town.nameOk)
        assertTrue(town.strict)

        val fragment = MatchFusion.evaluateAutoMatch(
            goblinTown,
            partial.copy(nameGuess = "nchar"),
        )
        assertFalse(fragment.accepted)
        assertFalse(fragment.nameOk)
        assertTrue(fragment.nameConflict)
        assertFalse(MatchFusion.allowsAutoSave(fragment))
    }

    @Test
    fun smittenSwordmasterItalianFaceIsNotANameConflict() {
        val swordmaster = IdentifiedCard(
            game = Game.MTG,
            name = "Smitten Swordmaster // Curry Favor",
            setCode = "eld",
            setName = "Throne of Eldraine",
            collectorNumber = "105",
            scryfallId = "eld-105",
            ocrScore = 0.98f,
            visualScore = null,
            matchConfidence = 0.98f,
            printedName = "Maestro di Spada Invaghito // Conquistare il Favore",
            printedLang = "it",
        )
        val parsed = ParsedCardText(
            nameGuess = "Maestro di Spada Invaghi",
            collectorNumber = "105",
            setCode = "eld",
            printedLang = "it",
        )
        val decision = MatchFusion.evaluateAutoMatch(swordmaster, parsed)
        assertTrue(decision.accepted)
        assertTrue(decision.strict)
        assertTrue(decision.nameOk)
        assertFalse(decision.nameConflict)
        assertTrue(MatchFusion.allowsAutoSave(decision))

        val frontOnly = swordmaster.copy(printedName = "Maestro di Spada Invaghito")
        assertTrue(FrameNameGate.agrees(frontOnly, parsed))

        val gallery = swordmaster.copy(
            printedName = null,
            ocrScore = 0.62f,
            visualScore = 0.72f,
            matchConfidence = 0.70f,
        )
        assertTrue(FrameNameGate.keep(listOf(gallery), parsed).isNotEmpty())
        val shown = MatchFusion.evaluateAutoMatch(gallery, parsed)
        assertTrue(shown.accepted)
        assertFalse(shown.strict)

        val elrond = swordmaster.copy(
            name = "Elrond, Moon-Reader",
            printedName = null,
            setCode = "hob",
            collectorNumber = "36",
            visualScore = null,
            ocrScore = 0.98f,
            matchConfidence = 0.98f,
        )
        val warg = ParsedCardText(nameGuess = "Ravening Warg", collectorNumber = "36", setCode = "hob")
        assertFalse(FrameNameGate.agrees(elrond, warg))
        assertTrue(FrameNameGate.keep(listOf(elrond), warg).isEmpty())
    }

    @Test
    fun pokemonAutoMatchWithAlignedCollectorAndCount() {
        val pokemonParsed = ParsedCardText(
            nameGuess = null,
            collectorNumber = "37",
            setCode = null,
            setCardCount = 217,
        )
        // Soft Pokémon gate: cn + setCardCount align, visual not terrible.
        val mid = IdentifiedCard(
            game = Game.POKEMON,
            name = "Raboot",
            setCode = "me02.5",
            setName = "Ascended Heroes",
            collectorNumber = "037",
            ocrScore = 0.45f,
            visualScore = 0.58f,
            matchConfidence = 0.52f,
        )
        val decision = MatchFusion.evaluateAutoMatch(mid, pokemonParsed)
        assertTrue(decision.accepted)
        assertFalse(decision.strict)
        assertFalse(MatchFusion.allowsAutoSave(decision))
        assertFalse(
            MatchFusion.isConfidentAutoMatch(mid.copy(matchConfidence = 0.40f), pokemonParsed),
        )
    }

    @Test
    fun pokemonAutoMatchWithNameAlignment() {
        val pokemonParsed = ParsedCardText(
            nameGuess = "Raboot",
            collectorNumber = null,
            setCode = null,
        )
        val mid = IdentifiedCard(
            game = Game.POKEMON,
            name = "Raboot",
            setCode = "swsh1",
            setName = "Sword & Shield",
            collectorNumber = "32",
            ocrScore = 0.50f,
            visualScore = 0.50f,
            matchConfidence = 0.50f,
        )
        assertTrue(MatchFusion.isConfidentAutoMatch(mid, pokemonParsed))
        assertFalse(MatchFusion.isConfidentAutoMatch(mid.copy(matchConfidence = 0.40f), pokemonParsed))
    }

    @Test
    fun rejectsTadbulbWhenOcrNameIsStaraviaDespiteShortCn() {
        // Classic OCR slip: 169 → 069/69 on ASC 217 cards.
        val parsed = ParsedCardText(
            nameGuess = "Staravia di Ubaldo",
            collectorNumber = "69",
            setCode = "asc",
            setCardCount = 217,
        )
        val tadbulb = IdentifiedCard(
            game = Game.POKEMON,
            name = "Iono's Tadbulb",
            setCode = "me02.5",
            setName = "Ascended Heroes",
            collectorNumber = "069",
            ocrScore = 0.55f,
            visualScore = 0.50f,
            matchConfidence = 0.52f,
        )
        val decision = MatchFusion.evaluateAutoMatch(tadbulb, parsed)
        assertFalse(decision.accepted)
        assertTrue(decision.nameConflict)
        assertEquals("nameConflict name=Staravia di Ubaldo top=Iono's Tadbulb", decision.reason)
    }

    @Test
    fun acceptsStaraviaWhenNameAndCnAlign() {
        val parsed = ParsedCardText(
            nameGuess = "Staravia di Ubaldo",
            collectorNumber = "169",
            setCode = "asc",
            setCardCount = 217,
        )
        val staravia = IdentifiedCard(
            game = Game.POKEMON,
            name = "Staravia di Ubaldo",
            setCode = "me02.5",
            setName = "Ascended Heroes",
            collectorNumber = "169",
            ocrScore = 0.55f,
            visualScore = 0.50f,
            matchConfidence = 0.52f,
        )
        assertTrue(MatchFusion.isConfidentAutoMatch(staravia, parsed))
    }

    @Test
    fun mtgSoftAcceptsSetAndCollectorWithoutNeural() {
        val mtgParsed = ParsedCardText(
            nameGuess = "Lightning Bolt",
            collectorNumber = "181",
            setCode = "dft",
        )
        // Same-language name + set/number — classic soft accept without neural pack.
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Lightning Bolt",
            setCode = "dft",
            setName = "Aetherdrift",
            collectorNumber = "181",
            ocrScore = 0.78f,
            visualScore = null,
            matchConfidence = 0.68f,
        )
        val decision = MatchFusion.evaluateAutoMatch(card, mtgParsed)
        assertTrue(decision.accepted)
        assertFalse(decision.strict)
        assertFalse(MatchFusion.allowsAutoSave(decision))
        assertTrue(decision.cnOk)
        assertTrue(decision.setOk)
        assertEquals("cn+set", decision.reason)
    }

    @Test
    fun mtgSoftRejectsWeakPartialHit() {
        val mtgParsed = ParsedCardText(
            nameGuess = "Wrong Title",
            collectorNumber = "161",
            setCode = "m21",
        )
        val weak = IdentifiedCard(
            game = Game.MTG,
            name = "Lightning Bolt",
            setCode = "m21",
            setName = "M21",
            collectorNumber = "161",
            ocrScore = 0.50f,
            visualScore = 0.55f,
            matchConfidence = 0.53f,
        )
        // cn+set present but ocr/visual/conf all below soft thresholds.
        assertFalse(MatchFusion.isConfidentAutoMatch(weak, mtgParsed))
    }

    @Test
    fun mtgSoftAcceptsNeuralWhenItalianTitleSharesCollector() {
        // Device log: OCR "Bolla Protettiva" cn=80, gallery "Protective Bubble" v=0.639 conf=0.629.
        val parsed = ParsedCardText(
            nameGuess = "Bolla Protettiva",
            collectorNumber = "80",
            setCode = null,
        )
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Protective Bubble",
            setCode = "lrw",
            setName = "Lorwyn",
            collectorNumber = "80",
            ocrScore = 0.61538464f,
            visualScore = 0.6394928f,
            matchConfidence = 0.62936735f,
        )
        val decision = MatchFusion.evaluateAutoMatch(card, parsed)
        assertTrue(decision.accepted)
        assertEquals("cn+neural", decision.reason)
        assertTrue(decision.cnOk)
        assertFalse(decision.setOk)
        assertEquals(
            "il numero coincide e il riconoscimento visivo conferma la carta",
            MatchFusion.signalPhrase(decision.reason),
        )
    }

    @Test
    fun mtgSoftRejectsNeuralBelowVisualFloor() {
        val parsed = ParsedCardText(
            nameGuess = "Bolla Protettiva",
            collectorNumber = "80",
            setCode = null,
        )
        val weak = IdentifiedCard(
            game = Game.MTG,
            name = "Protective Bubble",
            setCode = "lrw",
            setName = "Lorwyn",
            collectorNumber = "80",
            ocrScore = 0.615f,
            visualScore = 0.50f,
            matchConfidence = 0.62f,
        )
        val decision = MatchFusion.evaluateAutoMatch(weak, parsed)
        assertFalse(decision.accepted)
        assertTrue(decision.nameConflict)
    }

    @Test
    fun mtgSoftAcceptsItalianOcrAgainstEnglishGalleryWhenCollectorMatches() {
        // Device log: OCR Ostinazione Mortale cn=17, gallery Mortal Obstinacy v≈0.59.
        val parsed = ParsedCardText(
            nameGuess = "Ostinazione Mortale",
            collectorNumber = "17",
            setCode = null,
        )
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Mortal Obstinacy",
            setCode = "jou",
            setName = "Journey into Nyx",
            collectorNumber = "17",
            ocrScore = 0.60f,
            visualScore = 0.586f,
            matchConfidence = 0.598f,
        )
        val decision = MatchFusion.evaluateAutoMatch(card, parsed)
        assertTrue(decision.accepted)
        assertEquals("cn+neural", decision.reason)
        assertTrue(decision.cnOk)
    }

    @Test
    fun mtgPrintedNameClearsItalianConflictWithEnglishOracle() {
        val parsed = ParsedCardText(
            nameGuess = "Ostinazione Mortale",
            collectorNumber = "17",
            setCode = null,
        )
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Mortal Obstinacy",
            setCode = "jou",
            setName = "Journey into Nyx",
            collectorNumber = "17",
            ocrScore = 0.80f,
            visualScore = 0.59f,
            matchConfidence = 0.70f,
            printedName = "Ostinazione Mortale",
        )
        val decision = MatchFusion.evaluateAutoMatch(card, parsed)
        assertTrue(decision.accepted)
        assertTrue(decision.nameOk)
        assertFalse(decision.nameConflict)
        assertEquals("name+cn", decision.reason)
    }

    @Test
    fun mtgSoftRejectsCnSetWhenNameConflicts() {
        // +1/+1 misread as cn=1 → Emrakul, while OCR title is Divinatrice.
        val parsed = ParsedCardText(
            nameGuess = "Divinatrice della Vittoria",
            collectorNumber = "1",
            setCode = "fra",
        )
        val wrong = IdentifiedCard(
            game = Game.MTG,
            name = "Emrakul, the Exigent Doom",
            setCode = "fra",
            setName = "Final Fantasy",
            collectorNumber = "1",
            ocrScore = 0.98f,
            visualScore = 0.47f,
            matchConfidence = 0.68f,
        )
        val decision = MatchFusion.evaluateAutoMatch(wrong, parsed)
        assertFalse(decision.accepted)
        assertTrue(decision.nameConflict)
    }

    @Test
    fun mtgSoftAcceptsItalianNameSearchHitDespiteEnglishTitle() {
        val parsed = ParsedCardText(
            nameGuess = "Divinatrice della Vittoria",
            collectorNumber = "28",
            setCode = "fra",
        )
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Diviner of Victory // Unwind History",
            setCode = "fra",
            setName = "Final Fantasy",
            collectorNumber = "28",
            ocrScore = 0.88f,
            visualScore = 0.65f,
            matchConfidence = 0.78f,
        )
        val decision = MatchFusion.evaluateAutoMatch(card, parsed)
        assertTrue(decision.accepted)
        assertEquals("scryfallName", decision.reason)
    }

    @Test
    fun mtgSoftAcceptsItalianNameHitWithWeakVisual() {
        // Real failure: Trasformazione Raccapricciante → Grisly Transformation
        // catalogHits=1 ocr=0.88 visual~0.50 conf~0.66 was rejected as nameConflict.
        val parsed = ParsedCardText(
            nameGuess = "Trasformazione Raccapricciante",
            collectorNumber = null,
            setCode = null,
        )
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Grisly Transformation",
            setCode = "bng",
            setName = "Born of the Gods",
            collectorNumber = "74",
            ocrScore = 0.88f,
            visualScore = 0.50f,
            matchConfidence = 0.66f,
        )
        val decision = MatchFusion.evaluateAutoMatch(card, parsed)
        assertTrue(decision.accepted)
        assertEquals("scryfallName", decision.reason)
    }

    @Test
    fun mtgSoftRejectsCnOnlyFingerprintHit() {
        // Real failure: OCR invents cn=62 from rules, set missing, name="er. If"
        // → Arterial Flow ranked by fingerprint. Must NOT auto-accept.
        val parsed = ParsedCardText(
            nameGuess = "er. If",
            collectorNumber = "62",
            setCode = null,
        )
        val wrong = IdentifiedCard(
            game = Game.MTG,
            name = "Arterial Flow",
            setCode = "rix",
            setName = "Rivals of Ixalan",
            collectorNumber = "62",
            ocrScore = 0.62f,
            visualScore = 0.64f,
            matchConfidence = 0.63f,
        )
        val decision = MatchFusion.evaluateAutoMatch(wrong, parsed)
        assertFalse(decision.accepted)
        assertFalse(decision.setOk)
    }

    @Test
    fun mtgSoftAcceptsNameAndCollector() {
        val mtgParsed = ParsedCardText(
            nameGuess = "Lightning Bolt",
            collectorNumber = "161",
            setCode = null,
        )
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Lightning Bolt",
            setCode = "m21",
            setName = "M21",
            collectorNumber = "161",
            ocrScore = 0.65f,
            visualScore = null,
            matchConfidence = 0.58f,
        )
        val decision = MatchFusion.evaluateAutoMatch(card, mtgParsed)
        assertTrue(decision.accepted)
        assertEquals("name+cn", decision.reason)
    }

    @Test
    fun rejectsShortCollectorWithoutNameWhenSignalsWeak() {
        val parsed = ParsedCardText(
            nameGuess = null,
            collectorNumber = "69",
            setCode = null,
            setCardCount = 217,
        )
        val tadbulb = IdentifiedCard(
            game = Game.POKEMON,
            name = "Iono's Tadbulb",
            setCode = "me02.5",
            setName = "Ascended Heroes",
            collectorNumber = "069",
            ocrScore = 0.40f,
            visualScore = 0.45f,
            matchConfidence = 0.50f,
        )
        val decision = MatchFusion.evaluateAutoMatch(tadbulb, parsed)
        assertFalse(decision.accepted)
        assertTrue(decision.shortCn)
        assertEquals("cnShortWeak", decision.reason)
    }

    @Test
    fun strictAutoSaveNoteWhenWaitIsEnabled() {
        val strong = MatchFusion.score(
            card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f),
            parsed,
        )
        val decision = MatchFusion.evaluateAutoMatch(strong, parsed)
        assertTrue(decision.strict)
        assertTrue(MatchFusion.allowsAutoSave(decision))
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = true,
            autoSaveWaitSeconds = 5,
        )
        assertEquals(MatchFusion.ConfirmMatchTone.AUTO_SAVE, note.tone)
        assertEquals("Salvataggio automatico", note.title)
        assertTrue(note.body.contains("Riconoscimento forte"))
        assertTrue(note.body.contains("da sola"))
    }

    @Test
    fun softMatchNoteRequiresManualSave() {
        val mtgParsed = ParsedCardText(
            nameGuess = "Lightning Bolt",
            collectorNumber = "181",
            setCode = "dft",
        )
        val partial = IdentifiedCard(
            game = Game.MTG,
            name = "Lightning Bolt",
            setCode = "dft",
            setName = "Aetherdrift",
            collectorNumber = "181",
            ocrScore = 0.78f,
            visualScore = null,
            matchConfidence = 0.68f,
        )
        val decision = MatchFusion.evaluateAutoMatch(partial, mtgParsed)
        assertTrue(decision.accepted)
        assertFalse(MatchFusion.allowsAutoSave(decision))
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = true,
            autoSaveWaitSeconds = 5,
        )
        assertEquals(MatchFusion.ConfirmMatchTone.CONFIRM, note.tone)
        assertEquals("Conferma a mano", note.title)
        assertTrue(note.body.contains("set e numero collezionista coincidono"))
        assertTrue(note.body.contains("74%"))
        assertTrue(note.body.contains("Tocca Salva"))
        assertTrue(note.body.contains("68%"))
    }

    @Test
    fun strictNoteWhenAutoSaveWaitIsOff() {
        val strong = MatchFusion.score(
            card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f),
            parsed,
        )
        val decision = MatchFusion.evaluateAutoMatch(strong, parsed)
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = true,
            autoSaveWaitSeconds = 0,
        )
        assertEquals(MatchFusion.ConfirmMatchTone.CONFIRM, note.tone)
        assertTrue(note.body.contains("Impostazioni"))
        assertTrue(note.body.contains("Tocca Salva"))
    }

    @Test
    fun choosingAnotherPrintDropsAutomaticSave() {
        val strong = MatchFusion.score(
            card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f),
            parsed,
        )
        val decision = MatchFusion.evaluateAutoMatch(strong, parsed)
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = true,
            autoSaveWaitSeconds = 5,
            selectedMatchesGate = false,
        )
        assertEquals(MatchFusion.ConfirmMatchTone.BLOCKED, note.tone)
        assertTrue(note.body.contains("altra stampa"))
        assertFalse(note.body.contains("da sola"))
    }

    @Test
    fun editingTheCardCancelsTheAutomaticSaveNote() {
        val strong = MatchFusion.score(
            card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f),
            parsed,
        )
        val decision = MatchFusion.evaluateAutoMatch(strong, parsed)
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = true,
            autoSaveWaitSeconds = 5,
            autoSaveCancelled = true,
        )
        assertEquals(MatchFusion.ConfirmMatchTone.CONFIRM, note.tone)
        assertEquals("Salvataggio automatico annullato", note.title)
        assertTrue(note.body.contains("Hai modificato la scheda"))
        assertTrue(note.body.contains("Salva in collezione"))
        assertFalse(note.body.contains("da sola"))
    }

    @Test
    fun cancelledAutoSaveWinsOverADifferentPrint() {
        val strong = MatchFusion.score(
            card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f),
            parsed,
        )
        val decision = MatchFusion.evaluateAutoMatch(strong, parsed)
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = true,
            autoSaveWaitSeconds = 5,
            selectedMatchesGate = false,
            autoSaveCancelled = true,
        )
        assertEquals("Salvataggio automatico annullato", note.title)
        assertFalse(note.body.contains("da sola"))
    }

    @Test
    fun manualScanKeepsStrictMatchOnTheSaveButton() {
        val strong = MatchFusion.score(
            card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f),
            parsed,
        )
        val decision = MatchFusion.evaluateAutoMatch(strong, parsed)
        val note = MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = false,
            autoSaveWaitSeconds = 5,
        )
        assertEquals(MatchFusion.ConfirmMatchTone.CONFIRM, note.tone)
        assertTrue(note.body.contains("Tocca Salva"))
        assertFalse(note.body.contains("da sola"))
    }

    @Test
    fun nameConflictPhraseUsesTheReadTitle() {
        assertEquals(
            "il nome letto («Staravia di Ubaldo») non coincide con «Iono's Tadbulb»",
            MatchFusion.signalPhrase("nameConflict name=Staravia di Ubaldo top=Iono's Tadbulb"),
        )
    }

    @Test
    fun setAndCollectorWithoutAReadNameDoesNotAutoAccept() {
        // Local index and online set+number hits share this score. fra 13 with no
        // title must not autosave Kindred Judgment (cn was a truncated 130).
        val nameless = ParsedCardText(
            nameGuess = null,
            collectorNumber = "13",
            setCode = "fra",
        )
        val kindred = IdentifiedCard(
            game = Game.MTG,
            name = "Kindred Judgment",
            setCode = "fra",
            setName = "Forgotten Realms",
            collectorNumber = "13",
            ocrScore = 0.98f,
            visualScore = null,
            matchConfidence = 0.98f,
        )
        val strictHit = MatchFusion.evaluateAutoMatch(kindred, nameless)
        assertFalse(strictHit.accepted)
        assertFalse(strictHit.strict)
        assertFalse(strictHit.nameOk)
        assertFalse(MatchFusion.allowsAutoSave(strictHit))

        val online = kindred.copy(ocrScore = 0.78f, matchConfidence = 0.68f)
        val softHit = MatchFusion.evaluateAutoMatch(online, nameless)
        assertFalse(softHit.accepted)
        assertFalse(softHit.strict)
        assertFalse(MatchFusion.allowsAutoSave(softHit))

        val winter = IdentifiedCard(
            game = Game.MTG,
            name = "Winter, Team Player",
            printedName = "Winter, Giocatore di Squadra",
            setCode = "fra",
            setName = "Forgotten Realms",
            collectorNumber = "256",
            ocrScore = 0.98f,
            visualScore = null,
            matchConfidence = 0.98f,
        )
        val truncated = ParsedCardText(
            nameGuess = "Winter, Giocatore di Squadr",
            collectorNumber = "256",
            setCode = "fra",
        )
        assertTrue(FrameNameGate.agrees(winter, truncated))
        val truncatedHit = MatchFusion.evaluateAutoMatch(winter, truncated)
        assertTrue(truncatedHit.accepted)
        assertTrue(truncatedHit.strict)
        assertTrue(truncatedHit.nameOk)
        assertTrue(MatchFusion.allowsAutoSave(truncatedHit))

        val fullTitle = truncated.copy(nameGuess = "Winter, Giocatore di Squadra")
        val fullHit = MatchFusion.evaluateAutoMatch(winter, fullTitle)
        assertTrue(fullHit.accepted)
        assertTrue(fullHit.strict)
        assertTrue(MatchFusion.allowsAutoSave(fullHit))

        val otherWinter = winter.copy(
            name = "Winter, Recluso Tormentato",
            printedName = "Winter, Recluso Tormentato",
            collectorNumber = "240",
        )
        val wrongWinter = MatchFusion.evaluateAutoMatch(
            otherWinter,
            truncated.copy(collectorNumber = "240"),
        )
        assertFalse(wrongWinter.accepted)
        assertFalse(MatchFusion.allowsAutoSave(wrongWinter))

        val named = nameless.copy(nameGuess = "Kindred Judgment")
        val agreed = MatchFusion.evaluateAutoMatch(kindred, named)
        assertTrue(agreed.accepted)
        assertTrue(agreed.strict)
        assertTrue(agreed.nameOk)
        assertTrue(MatchFusion.allowsAutoSave(agreed))
    }

    private fun card(
        name: String,
        set: String,
        number: String,
        ocr: Float?,
        visual: Float?,
    ) = IdentifiedCard(
        game = Game.MTG,
        name = name,
        setCode = set,
        setName = set.uppercase(),
        collectorNumber = number,
        ocrScore = ocr,
        visualScore = visual,
    )
}
