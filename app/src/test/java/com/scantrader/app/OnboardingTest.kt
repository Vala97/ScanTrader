package com.scantrader.app

import com.scantrader.app.ui.NeuralPackCatalogLine
import com.scantrader.app.ui.onboarding.CameraPrompt
import com.scantrader.app.ui.onboarding.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun showsOnceUnlessThePackPromptWasAlreadyAnswered() {
        assertTrue(Onboarding.shouldShow(onboardingCompleted = false, neuralPromptShown = false))
        assertFalse(Onboarding.shouldShow(onboardingCompleted = true, neuralPromptShown = false))
        assertFalse(Onboarding.shouldShow(onboardingCompleted = false, neuralPromptShown = true))
        assertFalse(Onboarding.shouldShow(onboardingCompleted = true, neuralPromptShown = true))
    }

    @Test
    fun fivePagesCoverWelcomeFramingAutoscanExportAndCamera() {
        val pages = Onboarding.pages(
            magicInstalled = false,
            catalogs = listOf(NeuralPackCatalogLine("Pokémon (inglese)", 13_000_000L, installed = false)),
        )
        assertEquals(Onboarding.PAGE_COUNT, pages.size)
        assertEquals(
            listOf("Benvenuto", "Come inquadrare", "Auto-scan", "Esporta", "Fotocamera e pacchetti"),
            pages.map { it.title },
        )
        assertEquals(
            "ScanTrader scansiona le carte dei principali TCG, le organizza in collezioni " +
                "e prepara un CSV da caricare su CardTrader, per vendere la tua collezione in fretta!",
            pages[0].body,
        )
        assertEquals(
            "Tieni la carta dentro la guida, con una buona luce. Devono vedersi bene il nome " +
                "e il numero di collezione con il codice del set. Di solito sono in alto e in basso, " +
                "ma la posizione cambia da gioco a gioco.",
            pages[1].body,
        )
        val autoScan = pages[2].body.split("\n\n")
        assertEquals(2, autoScan.size)
        assertEquals(
            "Puoi attivare la lettura automatica: basta mettere la carta davanti alla fotocamera. " +
                "Quando viene riconosciuta, ti viene mostrata insieme alla lingua, se hai attivato il " +
                "riconoscimento della lingua. Se il riconoscimento è sicuro, la carta si salva da sola. " +
                "La soglia si regola in Impostazioni e la stessa identica carta non viene presa due volte di fila.",
            autoScan[0],
        )
        assertEquals(
            "Tips: se una carta non viene riconosciuta, puoi sempre forzare la lettura con il pulsante " +
                "di scatto manuale, accanto a quello principale.",
            autoScan[1],
        )
        assertEquals(
            "Collezione pronta? Con un tocco generi il CSV e la porti su CardTrader. " +
                "Le tue carte sono già in vetrina!",
            pages[3].body,
        )
        assertTrue(pages[4].body.contains("\n\n"))
        assertTrue(pages[4].requestsCamera)
        assertTrue(pages[4].body.contains("fotocamera"))
        val packs = pages[4].body
        assertTrue(packs.contains("pack neurale Magic"))
        assertTrue(packs.contains("Pokémon (inglese)"))
        val magicGroup = packs.indexOf("\nMagic\n")
        val pokemonGroup = packs.indexOf("\nPokémon\n")
        assertTrue(magicGroup >= 0 && pokemonGroup > magicGroup)
        assertTrue(packs.contains("da scaricare"))
        assertFalse(pages[0].requestsCamera)
    }

    @Test
    fun skipStaysAvailableAndTheLastActionIsInizia() {
        assertEquals("Salta", Onboarding.SKIP)
        assertEquals("Avanti", Onboarding.primaryAction(0))
        assertEquals("Avanti", Onboarding.primaryAction(Onboarding.LAST_PAGE - 1))
        assertEquals("Inizia", Onboarding.primaryAction(Onboarding.LAST_PAGE))
    }

    @Test
    fun aDeniedCameraNeverBlocksLeaving() {
        assertEquals(CameraPrompt.Ask, Onboarding.cameraPrompt(granted = false, requestCount = 0, canAskAgain = false))
        assertEquals(CameraPrompt.Granted, Onboarding.cameraPrompt(granted = true, requestCount = 1, canAskAgain = false))
        assertEquals(CameraPrompt.Denied, Onboarding.cameraPrompt(granted = false, requestCount = 1, canAskAgain = true))
        assertEquals(CameraPrompt.Denied, Onboarding.cameraPrompt(granted = false, requestCount = 1, canAskAgain = false))
        assertEquals(
            CameraPrompt.DeniedForever,
            Onboarding.cameraPrompt(granted = false, requestCount = 2, canAskAgain = false),
        )
        assertNull(Onboarding.cameraMessage(CameraPrompt.Ask))
        assertTrue(Onboarding.cameraMessage(CameraPrompt.Denied)!!.contains("continuare"))
        assertTrue(Onboarding.cameraMessage(CameraPrompt.DeniedForever)!!.contains("impostazioni del telefono"))
        assertTrue(Onboarding.canLeave(CameraPrompt.Denied))
        assertTrue(Onboarding.canLeave(CameraPrompt.DeniedForever))
        assertTrue(Onboarding.canLeave(CameraPrompt.Ask))
    }

    @Test
    fun downloadIsOfferedOnlyWhenMagicOrPokemonIsMissing() {
        assertTrue(Onboarding.offerDownload(magicInstalled = false, pokemonCatalogInstalled = false))
        assertTrue(Onboarding.offerDownload(magicInstalled = true, pokemonCatalogInstalled = false))
        assertTrue(Onboarding.offerDownload(magicInstalled = false, pokemonCatalogInstalled = true))
        assertFalse(Onboarding.offerDownload(magicInstalled = true, pokemonCatalogInstalled = true))
    }

    @Test
    fun downloadLabelMatchesWhatIsStillMissing() {
        assertEquals(
            "Scarica tutto",
            Onboarding.downloadLabel(magicInstalled = false, pokemonCatalogInstalled = false),
        )
        assertEquals(
            "Scarica pack Magic",
            Onboarding.downloadLabel(magicInstalled = false, pokemonCatalogInstalled = true),
        )
        assertEquals(
            "Scarica cataloghi Pokémon",
            Onboarding.downloadLabel(magicInstalled = true, pokemonCatalogInstalled = false),
        )
        assertNull(Onboarding.downloadLabel(magicInstalled = true, pokemonCatalogInstalled = true))
    }
}
