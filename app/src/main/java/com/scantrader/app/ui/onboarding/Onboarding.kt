package com.scantrader.app.ui.onboarding

import com.scantrader.app.ui.NeuralPackCatalogLine
import com.scantrader.app.ui.NeuralPackPrompt

/**
 * First-launch introduction. Shown once. Someone who already answered the
 * pack prompt is not sent through it again. «Rivedi introduzione» opens the
 * same pages without clearing that flag.
 */
object Onboarding {
    const val PAGE_COUNT = 5
    const val LAST_PAGE = PAGE_COUNT - 1
    const val SKIP = "Salta"
    const val NEXT = "Avanti"
    const val START = "Inizia"
    const val DOWNLOAD_ALL = "Scarica tutto"
    const val DOWNLOAD_MAGIC = "Scarica pack Magic"
    const val DOWNLOAD_POKEMON = "Scarica cataloghi Pokémon"
    const val RETRY_CAMERA = "Riprova"
    const val ALLOW_CAMERA = "Consenti fotocamera"

    const val CAMERA_GRANTED = "Fotocamera consentita."
    const val CAMERA_DENIED =
        "Permesso negato. Puoi riprovare o continuare: lo scan lo chiederà di nuovo."
    const val CAMERA_DENIED_FOREVER =
        "Permesso negato. Puoi attivarlo dalle impostazioni del telefono. Puoi continuare lo stesso."
    const val PACKS_READY = "Pack Magic e cataloghi Pokémon già sul telefono."
    const val DOWNLOAD_STARTED = "Download avviato. L'avanzamento è in Impostazioni."

    fun shouldShow(onboardingCompleted: Boolean, neuralPromptShown: Boolean): Boolean =
        !onboardingCompleted && !neuralPromptShown

    fun primaryAction(page: Int): String = if (page >= LAST_PAGE) START else NEXT

    fun pages(
        magicInstalled: Boolean = false,
        catalogs: List<NeuralPackCatalogLine> = emptyList(),
    ): List<OnboardingPage> = listOf(
        OnboardingPage(
            title = "Benvenuto",
            body = "ScanTrader scansiona le carte Magic e Pokémon, le organizza in collezioni " +
                "e prepara un CSV da caricare su CardTrader.",
        ),
        OnboardingPage(
            title = "Come inquadrare",
            body = "Tieni la carta dentro la guida, con una buona luce. Devono vedersi bene " +
                "sia il titolo in alto sia la parte in basso, con il numero di collezione e il codice del set.",
        ),
        OnboardingPage(
            title = "Auto-scan",
            body = "Quando il riconoscimento è sicuro, la carta si salva da sola. La soglia si regola " +
                "in Impostazioni. La stessa identica carta non viene presa due volte di fila.",
        ),
        OnboardingPage(
            title = "Esporta",
            body = "Dalla collezione generi il CSV da caricare su CardTrader.",
        ),
        OnboardingPage(
            title = "Fotocamera e pacchetti",
            body = "La fotocamera serve per inquadrare la carta durante lo scan. " +
                "Senza questo permesso non si può scansionare.\n\n" +
                NeuralPackPrompt.body(magicInstalled, catalogs),
            requestsCamera = true,
        ),
    )

    /** A denied camera permission never blocks «Inizia» or «Salta». */
    fun canLeave(prompt: CameraPrompt): Boolean = when (prompt) {
        CameraPrompt.Ask,
        CameraPrompt.Granted,
        CameraPrompt.Denied,
        CameraPrompt.DeniedForever,
        -> true
    }

    /**
     * The first refusal still offers a retry: Android often reports that the
     * permission cannot be asked again until the user has denied it once.
     * A later refusal with no rationale is treated as a permanent denial.
     */
    fun cameraPrompt(granted: Boolean, requestCount: Int, canAskAgain: Boolean): CameraPrompt = when {
        granted -> CameraPrompt.Granted
        requestCount <= 0 -> CameraPrompt.Ask
        canAskAgain || requestCount == 1 -> CameraPrompt.Denied
        else -> CameraPrompt.DeniedForever
    }

    fun cameraMessage(prompt: CameraPrompt): String? = when (prompt) {
        CameraPrompt.Ask -> null
        CameraPrompt.Granted -> CAMERA_GRANTED
        CameraPrompt.Denied -> CAMERA_DENIED
        CameraPrompt.DeniedForever -> CAMERA_DENIED_FOREVER
    }

    fun offerDownload(magicInstalled: Boolean, pokemonCatalogInstalled: Boolean): Boolean =
        !magicInstalled || !pokemonCatalogInstalled

    /** Null when nothing is missing: the page shows [PACKS_READY] and no button. */
    fun downloadLabel(magicInstalled: Boolean, pokemonCatalogInstalled: Boolean): String? = when {
        magicInstalled && pokemonCatalogInstalled -> null
        !magicInstalled && !pokemonCatalogInstalled -> DOWNLOAD_ALL
        !magicInstalled -> DOWNLOAD_MAGIC
        else -> DOWNLOAD_POKEMON
    }
}

data class OnboardingPage(
    val title: String,
    val body: String,
    val requestsCamera: Boolean = false,
)

enum class CameraPrompt {
    Ask,
    Granted,
    Denied,
    DeniedForever,
}
