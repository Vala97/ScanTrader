package com.scantrader.app

import android.app.Application
import com.scantrader.app.data.prefs.UserPreferences
import com.scantrader.app.update.AppUpdateController
import com.scantrader.app.update.DisabledUpdateInstaller
import com.scantrader.app.update.DisabledUpdateSource
import com.scantrader.app.update.UpdateInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PlayUpdateGuardTest {
    @Test
    fun disabledInstallerDoesNotTouchPackageInstaller() {
        val apk = File.createTempFile("scantrader", ".apk")
        apk.writeBytes(byteArrayOf(0))
        try {
            assertFalse(DisabledUpdateInstaller.canInstall())
            DisabledUpdateInstaller.install(apk)
            DisabledUpdateInstaller.openPermissionSettings()
        } finally {
            apk.delete()
        }
    }

    @Test
    fun disabledSourceNeverAsksTheInstaller() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val installer = RecordingInstaller()
        val controller = AppUpdateController(
            context = context,
            source = DisabledUpdateSource,
            prefs = UserPreferences(context),
            client = OkHttpClient(),
            installer = installer,
            versionCode = 1,
            sdkInt = 33,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        controller.checkOnLaunch()
        controller.checkNow()
        controller.downloadAndInstall()
        controller.openPermissionSettings()
        assertEquals(0, installer.installs)
        assertEquals(0, installer.settings)
    }

    private class RecordingInstaller : UpdateInstaller {
        var installs = 0
        var settings = 0
        override fun canInstall(): Boolean = true
        override fun install(apk: File) {
            installs++
        }
        override fun openPermissionSettings() {
            settings++
        }
    }
}
