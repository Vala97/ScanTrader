package com.scantrader.app.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scantrader.app.update.AppUpdateState
import com.scantrader.app.update.UpdateMessages

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onLater: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val offer = state.offer ?: return
    AlertDialog(
        onDismissRequest = { if (!state.downloading) onLater() },
        shape = RoundedCornerShape(28.dp),
        title = { Text(UpdateMessages.title(offer.versionName)) },
        text = {
            Column {
                if (offer.notes.isNotBlank()) {
                    Text(offer.notes)
                }
                state.message?.let { message ->
                    Text(message, modifier = Modifier.padding(top = if (offer.notes.isNotBlank()) 12.dp else 0.dp))
                }
                if (state.downloading) {
                    val progress = state.progress
                    if (progress == null) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.needsPermission -> {
                    TextButton(onClick = onOpenSettings) { Text("Apri impostazioni") }
                }
                state.downloading -> {
                    TextButton(onClick = {}, enabled = false) { Text("Download…") }
                }
                else -> {
                    TextButton(onClick = onInstall) { Text("Aggiorna") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !state.downloading) { Text("Più tardi") }
        },
    )
}
