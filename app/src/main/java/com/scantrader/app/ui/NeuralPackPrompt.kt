package com.scantrader.app.ui

import com.scantrader.app.vision.NeuralPackState

/**
 * The first-launch suggestion to download the Magic neural pack.
 * Once the user has seen it, or the pack is already on the device, it stays hidden.
 */
object NeuralPackPrompt {
    fun shouldShow(alreadyShown: Boolean, state: NeuralPackState): Boolean {
        if (alreadyShown) return false
        return when (state) {
            is NeuralPackState.Ready,
            NeuralPackState.InstalledNotLoaded,
            NeuralPackState.Loading,
            is NeuralPackState.Downloading,
            -> false
            NeuralPackState.NotInstalled,
            is NeuralPackState.Error,
            -> true
        }
    }
}
