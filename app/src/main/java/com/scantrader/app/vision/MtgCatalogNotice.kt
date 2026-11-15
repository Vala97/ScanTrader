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
 * Background check of the published Magic catalog. At most once a day.
 * A newer index version only raises a notice. The file is downloaded when
 * the user asks, through the existing checksummed install.
 */
class MtgCatalogNoticeController(
    private val prefs: UserPreferences,
    private val scope: CoroutineScope,
    private val installed: () -> MtgCatalogStatus,
    private val fetchRemoteVersion: suspend () -> String?,
    private val download: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun checkOnLaunch() {
        scope.launch {
            val remembered = prefs.mtgCatalogRemoteVersion()
            publish(remembered)
            if (!MtgCatalogNotice.shouldCheck(prefs.mtgCatalogCheckedAt(), now())) return@launch
            val remote = try {
                fetchRemoteVersion()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ScanDebug.w(StcatIndexFailure.warnLine(error))
                null
            }
            if (remote == null) return@launch
            prefs.setMtgCatalogCheckedAt(now())
            prefs.setMtgCatalogRemoteVersion(remote)
            publish(remote)
        }
    }

    /** User asked to install the newer catalog. A failed download keeps the old file. */
    fun download() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            try {
                val ok = download.invoke()
                if (ok) publish(prefs.mtgCatalogRemoteVersion())
            } finally {
                _busy.value = false
            }
        }
    }

    private fun publish(remoteVersion: String?) {
        val status = installed()
        _visible.value = MtgCatalogNotice.noticeVisible(
            catalogInstalled = status.catalogInstalled,
            installedVersion = status.installedVersion,
            remoteVersion = remoteVersion,
        )
    }
}

object MtgCatalogNotice {
    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** First check, or at least a day after the last successful index read. */
    fun shouldCheck(lastCheckAtMs: Long, nowMs: Long): Boolean {
        if (lastCheckAtMs <= 0L) return true
        return nowMs - lastCheckAtMs >= CHECK_INTERVAL_MS
    }

    /**
     * The home notice is only for a catalog already on the phone.
     * A missing pack stays on the first-launch download, not this banner.
     */
    fun noticeVisible(
        catalogInstalled: Boolean,
        installedVersion: String?,
        remoteVersion: String?,
    ): Boolean {
        if (!catalogInstalled) return false
        val remote = remoteVersion?.takeIf { it.isNotBlank() } ?: return false
        return PokemonCatalogIndex.isUpdate(remote, installedVersion, fileExists = true)
    }
}
