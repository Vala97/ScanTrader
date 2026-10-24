package com.scantrader.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.scantrader.app.ui.confirm.ConfirmSaveBar
import android.app.Application
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConfirmSaveBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveButtonIsVisibleAboveTheSystemBars() {
        compose.setContent {
            MaterialTheme {
                ConfirmSaveBar(
                    label = "OK · Salva in collezione",
                    enabled = true,
                    missingCollection = false,
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Salva in collezione", substring = true).assertIsDisplayed()
    }
}
