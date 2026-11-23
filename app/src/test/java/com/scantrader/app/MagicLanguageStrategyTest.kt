package com.scantrader.app

import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.data.catalog.language.LanguageInput
import com.scantrader.app.data.catalog.language.LanguageStrategies
import com.scantrader.app.data.catalog.language.MagicLanguageStrategy
import com.scantrader.app.data.catalog.language.MagicLexicon
import com.scantrader.app.data.catalog.language.PokemonLanguageStrategy
import com.scantrader.app.data.catalog.language.DefaultLanguageStrategy
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicLanguageStrategyTest {
    private fun magic(
        text: String?,
        code: String? = null,
        name: String? = null,
        english: String? = null,
        printed: Map<CardLanguage, String> = emptyMap(),
    ): CardLanguageDetector.Detection =
        MagicLanguageStrategy.explain(LanguageInput(name, english, printed, text, code))

    // --- Strategy per game ---------------------------------------------------

    @Test
    fun eachGameHasItsStrategy() {
        assertSame(MagicLanguageStrategy, LanguageStrategies.forGame(Game.MTG))
        assertSame(PokemonLanguageStrategy, LanguageStrategies.forGame(Game.POKEMON))
        assertSame(DefaultLanguageStrategy, LanguageStrategies.forGame(Game.YUGIOH))
        assertFalse(MagicLanguageStrategy.usesPrintedNameLookup)
        assertTrue(PokemonLanguageStrategy.usesPrintedNameLookup)
    }

    // --- Modern frames: collector code ----------------------------------------

    @Test
    fun italianCodeAndItalianTypeLineAgree() {
        val d = magic(
            text = "Fulmine\nIstantaneo\nIl Fulmine infligge 3 danni a un qualsiasi bersaglio.",
            code = "it",
            name = "Fulmine",
        )
        assertEquals(CardLanguage.ITALIAN, d.language)
        assertTrue(d.evidence, d.evidence.contains("collector=it"))
        assertTrue(d.evidence, d.evidence.contains("text=it"))
        assertEquals(CardLanguage.ITALIAN, CardLanguageDetector.handedLanguage(d))
    }

    @Test
    fun aCleanCodeDecidesWithoutText() {
        assertEquals(CardLanguage.ENGLISH, magic(text = null, code = "en").language)
        assertEquals(CardLanguage.ITALIAN, magic(text = "", code = "it").language)
        assertEquals(CardLanguage.SPANISH, magic(text = null, code = "es").language)
        assertEquals(CardLanguage.SPANISH, magic(text = null, code = "sp").language)
        assertEquals(CardLanguage.JAPANESE, magic(text = null, code = "ja").language)
        assertEquals(CardLanguage.JAPANESE, magic(text = null, code = "jp").language)
        assertEquals(CardLanguage.CHINESE_S, magic(text = null, code = "zhs").language)
        assertEquals(CardLanguage.CHINESE_S, magic(text = null, code = "cs").language)
        assertEquals(CardLanguage.CHINESE_T, magic(text = null, code = "zht").language)
        assertEquals(CardLanguage.CHINESE_T, magic(text = null, code = "ct").language)
        assertEquals(CardLanguage.ITALIAN, magic(text = null, code = "1t").language)
        assertTrue(magic(text = null, code = "it").evidence.contains("collector=it"))
    }

    @Test
    fun englishModernCard() {
        val d = magic(
            text = "Creature — Human Warrior\nWhenever this creature attacks, target creature you control " +
                "gets +1/+1 until end of turn.",
            code = "en",
        )
        assertEquals(CardLanguage.ENGLISH, d.language)
        assertTrue(d.evidence.contains("text=en"))
    }

    @Test
    fun spanishModernCard() {
        val d = magic(
            text = "Criatura — Humano Guerrero\nSiempre que esta criatura ataque, la criatura objetivo que " +
                "controlas obtiene +1/+1 hasta el final del turno.",
            code = "es",
        )
        assertEquals(CardLanguage.SPANISH, d.language)
        assertTrue(d.evidence.contains("collector=es"))
    }

    @Test
    fun anItCodeReadOnASpanishCardFollowsTheSpanishText() {
        val d = magic(
            text = "Conjuro\nRoba una carta. El jugador objetivo descarta una carta.",
            code = "it",
        )
        assertEquals(CardLanguage.SPANISH, d.language)
        assertTrue(d.evidence, d.evidence.contains("collector-misread=it"))
    }

    @Test
    fun anEsCodeReadOnAnItalianCardFollowsTheItalianText() {
        val d = magic(
            text = "Stregoneria\nPesca una carta. Il giocatore bersaglio scarta una carta.",
            code = "es",
        )
        assertEquals(CardLanguage.ITALIAN, d.language)
        assertEquals(CardLanguage.ITALIAN, CardLanguageDetector.handedLanguage(d))
    }

    @Test
    fun codeAgainstStrongTextIsAConflictNotALanguage() {
        val itOnEnglish = magic(
            text = "Creature — Spider\nDeathtouch\nUntil end of turn, target creature you control gets +1/+1.",
            code = "it",
        )
        assertNull(itOnEnglish.language)
        assertTrue(itOnEnglish.evidence, itOnEnglish.evidence.startsWith("conflict"))
        assertNull(CardLanguageDetector.handedLanguage(itOnEnglish))

        val enOnItalian = magic(
            text = "Creatura — Ragno\nTocco letale\nVolare\nfino alla fine del turno",
            code = "en",
        )
        assertNull(enOnItalian.language)
        assertTrue(enOnItalian.evidence.startsWith("conflict"))
    }

    @Test
    fun weakContraryTextDoesNotOverrideAHardCode() {
        // A single shared/ambiguous word does not beat an EN code.
        val d = magic(text = "Creatura", code = "en")
        // `creatura` alone is Italian text (6) but not strong (< 8): the code wins.
        assertEquals(CardLanguage.ENGLISH, d.language)
    }

    // --- The name never decides alone ------------------------------------------

    @Test
    fun englishNameAloneNeverDecides() {
        val d = magic(
            text = "Lightning Bolt",
            name = "Lightning Bolt",
            english = "Lightning Bolt",
            printed = mapOf(CardLanguage.ITALIAN to "Fulmine"),
        )
        assertNull(d.language)
        assertEquals("none", d.evidence)
        assertNull(CardLanguageDetector.handedLanguage(d))
    }

    @Test
    fun italianNameAloneNeverDecides() {
        val d = magic(
            text = "Fulmine",
            name = "Fulmine",
            english = "Lightning Bolt",
            printed = mapOf(CardLanguage.ITALIAN to "Fulmine"),
        )
        assertNull(d.language)
    }

    @Test
    fun italianPrintThatKeptTheEnglishNameIsItalian() {
        val d = magic(
            text = "Sol Ring\nArtefatto\nTAP: Aggiungi due mana incolori.",
            name = "Sol Ring",
            english = "Sol Ring",
        )
        assertEquals(CardLanguage.ITALIAN, d.language)
        assertTrue(d.evidence.contains("text=it"))
    }

    @Test
    fun titleWordsDoNotCountAsCardText() {
        // The title is Italian-looking but the printed text is English.
        val d = magic(
            text = "Stregoneria\nCreature — Spider\nDeathtouch\nWhen this creature dies, draw a card.",
            name = "Stregoneria",
        )
        assertEquals(CardLanguage.ENGLISH, d.language)
    }

    @Test
    fun nameBreaksOnlyATieTheTextLeftOpen() {
        // `Criatura` is Spanish and Portuguese: the text alone cannot choose.
        val tie = magic(text = "Criatura", name = "Duende", english = "Goblin")
        assertNull(tie.language)
        val broken = magic(
            text = "Criatura",
            name = "Duende",
            english = "Goblin",
            printed = mapOf(CardLanguage.SPANISH to "Duende"),
        )
        assertEquals(CardLanguage.SPANISH, broken.language)
        assertTrue(broken.evidence, broken.evidence.contains("name-tiebreak=es"))
        assertEquals(CardLanguage.SPANISH, CardLanguageDetector.handedLanguage(broken))
        val same = magic(
            text = "Criatura",
            name = "Duende",
            printed = mapOf(CardLanguage.SPANISH to "Duende", CardLanguage.PORTUGUESE to "Duende"),
        )
        assertNull(same.language)
    }

    // --- Ambiguous words --------------------------------------------------------

    @Test
    fun wordsSharedByEveryLanguageDecideNothing() {
        for (word in listOf("Aura", "Saga", "Planeswalker", "Incantesimo — Aura".substringAfter("— "))) {
            assertNull(word, magic(text = word).language)
        }
        assertTrue("aura" in MagicLexicon.NEUTRAL)
        assertTrue("planeswalker" in MagicLexicon.NEUTRAL)
        // `creature` is the English type and the Italian plural.
        assertNull(magic(text = "Creature").language)
        // `Land` is English and German; `Terra` alone is too weak.
        assertNull(magic(text = "Land").language)
        assertNull(magic(text = "Terra").language)
        assertEquals(CardLanguage.ITALIAN, magic(text = "Terra Base — Foresta").language)
        assertEquals(CardLanguage.ENGLISH, magic(text = "Basic Land — Forest").language)
        assertEquals(CardLanguage.ITALIAN, magic(text = "Incantesimo — Aura").language)
    }

    @Test
    fun oneItalianTypeWordIsEnough() {
        for (type in listOf("Creatura — Goblin", "Stregoneria", "Istantaneo", "Artefatto", "Incantesimo")) {
            assertEquals(type, CardLanguage.ITALIAN, magic(text = type).language)
        }
        for (type in listOf("Conjuro", "Instantáneo", "Encantamiento", "Artefacto")) {
            assertEquals(type, CardLanguage.SPANISH, magic(text = type).language)
        }
        for (type in listOf("Sorcery", "Instant", "Enchantment", "Artifact")) {
            assertEquals(type, CardLanguage.ENGLISH, magic(text = type).language)
        }
    }

    @Test
    fun oneOcrSlipOnALongWordStillCounts() {
        assertEquals(CardLanguage.ITALIAN, magic(text = "Stregonerla").language)
        assertEquals(CardLanguage.ITALIAN, magic(text = "Istantanco").language)
        // One edit from both `creature` and `creatura`: neither.
        assertNull(magic(text = "Creaturo").language)
    }

    @Test
    fun copyrightAndTheBottomBlockAreNotCardText() {
        val d = magic(
            text = "Creatura — Elfo Druido\nTAPpa: Aggiungi un mana verde.\n" +
                "™ & © 1993-2024 Wizards of the Coast\n--- bottom ---\nThe and your you Illus. Someone",
        )
        assertEquals(CardLanguage.ITALIAN, d.language)
    }

    // --- Old frames: no collector code, printed words only ------------------

    @Test
    fun oldItalianCardsWithoutCode() {
        val evoca = magic(
            text = "Evoca Goblin\nTAPpa: Il Goblin infligge 1 danno a una creatura bersaglio.\n" +
                "Illus. Pete Venters",
            name = "Goblin Balloon Brigade",
        )
        assertEquals(CardLanguage.ITALIAN, evoca.language)
        assertTrue(evoca.evidence, evoca.evidence.startsWith("text=it"))
        assertEquals(CardLanguage.ITALIAN, CardLanguageDetector.handedLanguage(evoca))

        assertEquals(
            CardLanguage.ITALIAN,
            magic(text = "Interruzione\nNeutralizza una magia bersaglio.").language,
        )
        assertEquals(
            CardLanguage.ITALIAN,
            magic(text = "Incanta Creatura\nLa creatura incantata guadagna volare.").language,
        )
        // 8th Edition – M14: fraction number, no language code.
        assertEquals(
            CardLanguage.ITALIAN,
            magic(text = "Creatura — Elfo Druido\nTAPpa: Aggiungi G alla tua riserva di mana.\n123/249 C").language,
        )
    }

    @Test
    fun oldSpanishCardsWithoutCode() {
        val invocar = magic(
            text = "Invocar Duende\nGira: El Duende hace 1 punto de daño a la criatura objetivo.",
        )
        assertEquals(CardLanguage.SPANISH, invocar.language)
        assertEquals(
            CardLanguage.SPANISH,
            magic(text = "Interrupción\nContrarresta el conjuro objetivo.").language,
        )
        assertEquals(
            CardLanguage.SPANISH,
            magic(text = "Encantar criatura\nLa criatura encantada tiene vuela.").language,
        )
    }

    @Test
    fun oldEnglishCardsWithoutCode() {
        assertEquals(
            CardLanguage.ENGLISH,
            magic(text = "Summon Goblin\nTap: Goblin deals 1 damage to target creature.").language,
        )
        assertEquals(CardLanguage.ENGLISH, magic(text = "Interrupt\nCounter target spell.").language)
        assertEquals(
            CardLanguage.ENGLISH,
            magic(text = "Enchant Creature\nEnchanted creature gains flying.").language,
        )
    }

    @Test
    fun japaneseAndChineseByScriptWithoutCode() {
        val ja = magic(text = "クリーチャー — 人間・戦士\n飛行\nターン終了時まで、+1/+1の修整を受ける。")
        assertEquals(CardLanguage.JAPANESE, ja.language)
        assertTrue(ja.evidence.contains("script=ja"))
        assertEquals(CardLanguage.JAPANESE, CardLanguageDetector.handedLanguage(ja))

        val simplified = magic(text = "生物 — 人类战士\n飞行\n直到回合结束，目标生物获得+1/+1。")
        assertEquals(CardLanguage.CHINESE_S, simplified.language)
        val traditional = magic(text = "生物 — 人類戰士\n飛行\n直到回合結束，目標生物獲得+1/+1。")
        assertEquals(CardLanguage.CHINESE_T, traditional.language)

        // Han text with no simplified or traditional form: the code decides, or nothing does.
        assertNull(magic(text = "生物生物人").language)
        assertEquals(CardLanguage.CHINESE_T, magic(text = "生物生物人", code = "ct").language)
        assertEquals(CardLanguage.JAPANESE, magic(text = "クリーチャー — 人間", code = "jp").language)
        // A traditional/simplified guess does not fight the printed Chinese code.
        assertEquals(CardLanguage.CHINESE_S, magic(text = "生物 — 人類戰士 結束", code = "cs").language)
    }

    @Test
    fun noSignalIsNotALanguage() {
        val d = magic(text = null)
        assertNull(d.language)
        assertEquals("none", d.evidence)
        assertNull(CardLanguageDetector.handedLanguage(d))
        assertNull(magic(text = "xq zv 12").language)
    }

    @Test
    fun scriptPassOnlyWhenTheLatinReadFoundNothing() {
        assertTrue(MagicLanguageStrategy.needsScriptPass(null, "", null))
        assertTrue(MagicLanguageStrategy.needsScriptPass("Fulmine", "Fulmine\n4 ~ 2", null))
        assertFalse(MagicLanguageStrategy.needsScriptPass(null, "", "it"))
        assertFalse(MagicLanguageStrategy.needsScriptPass(null, "Stregoneria", null))
        assertFalse(MagicLanguageStrategy.needsScriptPass(null, "クリーチャー — 人間", null))
    }

    // --- Copy language on the matched card --------------------------------------

    @Test
    fun copyLanguageIsStampedFromTextNotFromTheName() {
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Sol Ring",
            setCode = "cmm",
            setName = "Commander Masters",
            collectorNumber = "410",
            catalogName = "Sol Ring",
            catalogLang = "en",
        )
        val italian = CardLanguageDetector.resolveCopyLanguage(
            card,
            ocrName = "Sol Ring",
            collectorLang = null,
            ocrText = "Sol Ring\nArtefatto\nTAP: Aggiungi due mana incolori.",
        )
        assertEquals("it", italian.card.printedLang)
        assertEquals("Sol Ring", italian.card.name)

        val nameOnly = CardLanguageDetector.resolveCopyLanguage(card, ocrName = "Sol Ring", ocrText = "Sol Ring")
        assertNull(nameOnly.card.printedLang)
        assertNull(nameOnly.detection.language)

        val coded = CardLanguageDetector.resolveCopyLanguage(card, ocrName = "Sol Ring", collectorLang = "es")
        assertEquals("es", coded.card.printedLang)
    }
}
