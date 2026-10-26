package com.scantrader.app

import com.scantrader.app.ui.NeuralPackPrompt
import com.scantrader.app.vision.NeuralPackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralPackPromptTest {
    @Test
    fun suggestsTheMagicPackOnlyOnceAndOnlyWhenItIsMissing() {
        assertTrue(NeuralPackPrompt.shouldShow(false, NeuralPackState.NotInstalled))
        assertTrue(NeuralPackPrompt.shouldShow(false, NeuralPackState.Error("rete")))
        assertFalse(NeuralPackPrompt.shouldShow(true, NeuralPackState.NotInstalled))
        assertFalse(NeuralPackPrompt.shouldShow(false, NeuralPackState.Ready(10)))
        assertFalse(NeuralPackPrompt.shouldShow(false, NeuralPackState.InstalledNotLoaded))
        assertFalse(NeuralPackPrompt.shouldShow(false, NeuralPackState.Downloading(0.2f, "pack")))
    }
}
