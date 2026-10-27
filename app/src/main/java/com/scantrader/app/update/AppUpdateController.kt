package com.scantrader.app.update

import android.content.Context
import com.scantrader.app.data.prefs.UserPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateState(
    val githubSelfUpdate: Boolean = true,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val offer: AppUpdate? = null,
    val message: String? = null,
    val needsPermission: Boolean = false,
)

class AppUpdateController(
    context: Context,
    private val source: UpdateSource,
    private val prefs: UserPreferences,
    private val client: OkHttpClient,
    private val installer: UpdateInstaller,
    private val versionCode: Int,
    private val sdkInt: Int,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val updateDir = File(appContext.cacheDir, "updates")
    val state: StateFlow<AppUpdateState> = MutableStateFlow(
        AppUpdateState(githubSelfUpdate = source.githubSelfUpdate),
    )
    private val mutable = state as MutableStateFlow<AppUpdateState>

    fun checkOnLaunch() {
        if (!source.githubSelfUpdate) return
        scope.launch {
            val pending = prefs.pendingAppUpdate()?.let { AppUpdateIndex.parse(it) }
            val visible = pending?.takeIf {
                AppUpdateIndex.isNewer(it.versionCode, versionCode) &&
                    AppUpdateIndex.deviceCanInstall(it.minSdk, sdkInt)
            }
            if (visible != null) mutable.update { it.copy(offer = visible) }
            val defaults = prefs.currentDefaults()
            val last = prefs.appUpdateCheckedAt()
            if (!AppUpdateIndex.shouldAutoCheck(defaults.autoUpdateCheck, last, now())) return@launch
            check(manual = false)
        }
    }

    fun checkNow() {
        if (!source.githubSelfUpdate) return
        scope.launch { check(manual = true) }
    }

    fun dismiss() {
        scope.launch {
            prefs.setPendingAppUpdate(null)
            mutable.update { it.copy(offer = null, needsPermission = false, message = null) }
        }
    }

    fun openPermissionSettings() {
        installer.openPermissionSettings()
    }

    fun downloadAndInstall() {
        val offer = mutable.value.offer ?: return
        if (!source.githubSelfUpdate) return
        if (!installer.canInstall()) {
            mutable.update {
                it.copy(needsPermission = true, message = UpdateMessages.NEEDS_PERMISSION)
            }
            return
        }
        scope.launch {
            mutable.update { it.copy(downloading = true, progress = null, message = null, needsPermission = false) }
            val part = File(updateDir, "scantrader-update.apk.part")
            val dest = File(updateDir, "scantrader-update.apk")
            val wrote = withContext(Dispatchers.IO) { download(offer.apkUrl, part) }
            if (!wrote) {
                part.delete()
                mutable.update {
                    it.copy(downloading = false, progress = null, message = UpdateMessages.OFFLINE)
                }
                return@launch
            }
            val verified = withContext(Dispatchers.IO) {
                AppUpdateIndex.promoteVerified(part, dest, offer.sha256)
            }
            if (verified == null) {
                mutable.update {
                    it.copy(downloading = false, progress = null, message = UpdateMessages.CHECKSUM)
                }
                return@launch
            }
            val started = runCatching { installer.install(verified) }.isSuccess
            mutable.update {
                it.copy(
                    downloading = false,
                    progress = null,
                    message = if (started) null else UpdateMessages.INSTALL_FAILED,
                )
            }
            if (started) {
                prefs.setPendingAppUpdate(null)
                mutable.update { current -> current.copy(offer = null) }
            }
        }
    }

    private suspend fun check(manual: Boolean) {
        mutable.update { it.copy(checking = true, message = if (manual) null else it.message) }
        val lookup = try {
            source.check()
        } catch (_: IOException) {
            UpdateLookup.Unavailable
        }
        prefs.setAppUpdateCheckedAt(now())
        when (lookup) {
            UpdateLookup.Unavailable -> mutable.update {
                it.copy(
                    checking = false,
                    message = if (manual) UpdateMessages.OFFLINE else it.message,
                )
            }
            UpdateLookup.Empty -> {
                prefs.setPendingAppUpdate(null)
                mutable.update {
                    it.copy(
                        checking = false,
                        offer = null,
                        message = if (manual) UpdateMessages.UP_TO_DATE else null,
                    )
                }
            }
            is UpdateLookup.Ready -> {
                val update = lookup.update
                val show = AppUpdateIndex.isNewer(update.versionCode, versionCode) &&
                    AppUpdateIndex.deviceCanInstall(update.minSdk, sdkInt)
                if (show) prefs.setPendingAppUpdate(encodePending(update)) else prefs.setPendingAppUpdate(null)
                mutable.update {
                    it.copy(
                        checking = false,
                        offer = if (show) update else null,
                        message = if (show || !manual) null else UpdateMessages.UP_TO_DATE,
                    )
                }
            }
        }
    }

    private fun download(url: String, part: File): Boolean {
        if (!AppUpdateIndex.isGithubHttpsAsset(url)) return false
        updateDir.mkdirs()
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val total = body.contentLength()
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            if (total > 0L) {
                                val progress = (readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                mutable.update { it.copy(progress = progress) }
                            }
                        }
                    }
                }
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun encodePending(update: AppUpdate): String {
        val min = update.minSdk?.let { ""","minSdk":$it""" }.orEmpty()
        return buildString {
            append("{")
            append(""""versionCode":${update.versionCode},""")
            append(""""versionName":"${jsonEscape(update.versionName)}",""")
            append(""""apkUrl":"${jsonEscape(update.apkUrl)}",""")
            append(""""sha256":"${update.sha256}",""")
            append(""""notes":"${jsonEscape(update.notes)}"""")
            append(min)
            append("}")
        }
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
