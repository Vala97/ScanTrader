package com.scantrader.app.data.catalog

import com.scantrader.app.data.db.CtExpansionDao
import com.scantrader.app.data.db.CtExpansionEntity
import com.scantrader.app.data.prefs.UserPreferences
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.scan.ScanDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Downloads CardTrader expansions into Room at most once per day when a token is set.
 */
class ExpansionsSyncController(
    private val api: CardTraderApi,
    private val token: () -> String?,
    private val dao: CtExpansionDao,
    private val prefs: UserPreferences,
    private val scope: CoroutineScope,
    private val resolver: CtExpansionResolver? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun checkOnLaunch() {
        scope.launch {
            resolver?.refreshSnapshot()
            syncIfDue()
        }
    }

    suspend fun syncIfDue(force: Boolean = false): Boolean {
        if (token()?.isBlank() != false) return false
        val last = prefs.expansionsSyncedAt()
        if (!force && !shouldSync(last, now())) return false
        return runCatching {
            val list = api.expansions()
            dao.upsertAll(
                list.map {
                    CtExpansionEntity(
                        id = it.id,
                        gameId = it.gameId,
                        code = it.code.trim(),
                        name = it.name.trim(),
                    )
                },
            )
            prefs.setExpansionsSyncedAt(now())
            resolver?.refreshSnapshot()
            true
        }.onFailure {
            ScanDebug.w("CardTrader expansions sync failed", it)
        }.getOrDefault(false)
    }

    companion object {
        const val SYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L

        fun shouldSync(lastSyncAtMs: Long, nowMs: Long): Boolean {
            if (lastSyncAtMs <= 0L) return true
            return nowMs - lastSyncAtMs >= SYNC_INTERVAL_MS
        }
    }
}
