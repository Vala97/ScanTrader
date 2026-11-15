package com.scantrader.app

import android.app.Application
import com.scantrader.app.data.prefs.UserPreferences
import com.scantrader.app.scan.AutoScanLive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LastCardBarPrefsTest {
    @Test
    fun collapsedChoiceSurvivesANewPreferencesRead() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = UserPreferences(context)
        prefs.setLastCardBarCollapsed(false)
        assertFalse(AutoScanLive.lastCardBarCollapsed(prefs.currentDefaults().lastCardBarCollapsed))

        prefs.setLastCardBarCollapsed(true)
        val reopened = UserPreferences(context)
        assertEquals(true, AutoScanLive.lastCardBarCollapsed(reopened.currentDefaults().lastCardBarCollapsed))

        reopened.setLastCardBarCollapsed(false)
        assertFalse(AutoScanLive.lastCardBarCollapsed(prefs.currentDefaults().lastCardBarCollapsed))
    }
}
