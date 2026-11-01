package com.scantrader.app.ui.confirm

import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.data.model.CardLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Autosave must not sit behind a Scryfall download of every printed language.
 * Local OCR evidence starts the countdown immediately and does not launch the
 * printed-name lookup. Otherwise the wait is a real cap: the network call runs
 * in [scope], not inside the timeout, so a blocking read cannot hold the countdown.
 */
object LanguageAutosave {
    data class Result(
        val language: CardLanguage?,
        val source: String,
        val waitedMs: Long,
    )

    suspend fun resolve(
        local: CardLanguageDetector.Detection?,
        capMs: Long = AutoSaveSchedule.LANGUAGE_WAIT_CAP_MS,
        now: () -> Long = System::currentTimeMillis,
        scope: CoroutineScope,
        remote: suspend () -> CardLanguageDetector.Detection?,
        onRemote: (CardLanguageDetector.Detection) -> Unit = {},
    ): Result {
        val started = now()
        if (local?.language != null) {
            return Result(
                language = local.language,
                source = local.evidence.ifBlank { "ocr" },
                waitedMs = (now() - started).coerceAtLeast(0),
            )
        }
        val gate = CompletableDeferred<CardLanguageDetector.Detection?>()
        scope.launch(Dispatchers.IO) {
            val later = runCatching { remote() }.getOrNull()
            val won = gate.complete(later)
            if (!won && later?.language != null) onRemote(later)
        }
        val arrived = if (capMs <= 0L) {
            null
        } else {
            withTimeoutOrNull(capMs) { gate.await() }
        }
        val waited = (now() - started).coerceAtLeast(0)
        if (arrived?.language != null) {
            return Result(arrived.language, arrived.evidence.ifBlank { "remote" }, waited)
        }
        gate.complete(null)
        return Result(local?.language, "timeout", waited)
    }
}
