package com.scantrader.app

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
        val pages = Onboarding.pages(listOf("Pokémon (inglese)" to 13_000_000L))
        assertEquals(Onboarding.PAGE_COUNT, pages.size)
        assertEquals(
            listOf("Benvenuto", "Come inquadrare", "Auto-scan", "Esporta", "Fotocamera e pacchetti"),
            pages.map { it.title },
        )
        assertTrue(pages[0].body.contains("Magic"))
        assertTrue(pages[0].body.contains("Pokémon"))
        assertTrue(pages[0].body.contains("CSV"))
        assertTrue(pages[0].body.contains("CardTrader"))
        assertTrue(pages[1].body.contains("titolo"))
        assertTrue(pages[1].body.contains("numero di collezione"))
        assertTrue(pages[1].body.contains("codice del set"))
        assertTrue(pages[2].body.contains("soglia"))
        assertTrue(pages[2].body.contains("due volte"))
        assertTrue(pages[3].body.contains("CSV"))
        assertTrue(pages[3].body.contains("CardTrader"))
        assertTrue(pages[4].requestsCamera)
        assertTrue(pages[4].body.contains("fotocamera"))
        assertTrue(pages[4].body.contains("pack neurale Magic"))
        assertTrue(pages[4].body.contains("Pokémon (inglese)"))
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
}
