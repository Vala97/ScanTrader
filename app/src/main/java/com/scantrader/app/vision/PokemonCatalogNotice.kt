package com.scantrader.app.vision

import com.scantrader.app.data.prefs.UserPreferences
import com.scantrader.app.scan.ScanDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background check of the published English Pokémon catalog. At most once a day.
 * A newer index version only raises a notice. The file is downloaded when
 * the user asks, through the existing checksummed install.
 */
class PokemonCatalogNoticeController(
    private val prefs: UserPreferences,
    private val scope: CoroutineScope,
    private val catalogs: PokemonCatalogController,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun checkOnLaunch() {
        scope.launch {
            val remembered = prefs.pokemonCatalogRemoteVersion()
            publish(remembered)
            if (!MtgCatalogNotice.shouldCheck(prefs.pokemonCatalogCheckedAt(), now())) return@launch
            val remote = try {
                catalogs.refreshAwait()
                catalogs.remoteVersion(PokemonCatalogNotice.CATALOG_ID)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ScanDebug.w(StcatIndexFailure.warnLine(error))
                null
            }
            if (remote == null) return@launch
            prefs.setPokemonCatalogCheckedAt(now())
            prefs.setPokemonCatalogRemoteVersion(remote)
            publish(remote)
        }
    }

    fun download() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            try {
                val ok = catalogs.downloadAndAwait(PokemonCatalogNotice.CATALOG_ID)
                if (ok) publish(prefs.pokemonCatalogRemoteVersion())
            } finally {
                _busy.value = false
            }
        }
    }

    private fun publish(remoteVersion: String?) {
        val installed = catalogs.installed(PokemonCatalogNotice.CATALOG_ID)
        _visible.value = MtgCatalogNotice.noticeVisible(
            catalogInstalled = installed != null,
            installedVersion = installed?.version,
            remoteVersion = remoteVersion,
        )
    }
}

object PokemonCatalogNotice {
    const val CATALOG_ID = "pokemon-en"

    fun versionOf(entries: List<StcatCatalogEntry>): String? =
        entries.firstOrNull { it.id == CATALOG_ID }?.version?.takeIf { it.isNotBlank() }
}
