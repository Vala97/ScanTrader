package com.scantrader.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.scantrader.app.ScanTraderApp
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.factoryOf
import com.scantrader.app.vision.NeuralPackState
import com.scantrader.app.vision.PokemonCatalogUi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(private val app: ScanTraderApp) : ViewModel() {
    val magic: StateFlow<NeuralPackState> = app.neuralPack.mtgState
    val catalogs: StateFlow<PokemonCatalogUi> = app.pokemonCatalogs.ui
    val downloadStarted = MutableStateFlow(false)
    private val finishing = AtomicBoolean(false)
    private val downloadGate = AtomicBoolean(false)

    init {
        app.pokemonCatalogs.refresh()
    }

    fun finish(onDone: () -> Unit) {
        if (!finishing.compareAndSet(false, true)) return
        viewModelScope.launch {
            app.prefs.completeOnboarding()
            onDone()
        }
    }

    /** Starts the Magic pack (if missing) and any Pokémon catalog that is missing or older. */
    fun downloadPacks() {
        if (!downloadGate.compareAndSet(false, true)) return
        downloadStarted.value = true
        viewModelScope.launch {
            if (!app.neuralPack.isInstalled(Game.MTG)) {
                launch { app.neuralPack.downloadAndInstall() }
            }
            app.pokemonCatalogs.downloadMissing()
        }
    }

    companion object {
        fun factory(app: ScanTraderApp): ViewModelProvider.Factory = factoryOf { OnboardingViewModel(app) }
    }
}
