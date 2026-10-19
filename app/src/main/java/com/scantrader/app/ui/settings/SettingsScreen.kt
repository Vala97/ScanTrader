package com.scantrader.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.ui.SettingsViewModel
import com.scantrader.app.ui.components.SimpleDropdown
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.NeuralPackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val neural by viewModel.neuralPackState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadNeuralPack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SimpleDropdown(
                label = "Lingua predefinita",
                options = CardLanguage.entries.toList(),
                selected = defaults.language,
                onSelect = viewModel::setLanguage,
                labelOf = { it.label },
            )
            SimpleDropdown(
                label = "Condizione predefinita",
                options = CardCondition.entries.toList(),
                selected = defaults.condition,
                onSelect = viewModel::setCondition,
                labelOf = { it.label },
            )

            Text("Riconoscimento neurale MTG", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pack on-device Milo (AGPL): embedding di ~100k carte Scryfall. " +
                    "Riduce errori quando l’OCR è debole. Download ${NeuralPackManager.APPROX_SIZE_LABEL}, poi offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (val s = neural) {
                    is NeuralPackState.NotInstalled -> "Stato: non installato"
                    is NeuralPackState.InstalledNotLoaded -> "Stato: scaricato, non in memoria"
                    is NeuralPackState.Loading -> "Stato: caricamento…"
                    is NeuralPackState.Downloading -> "Stato: ${s.label} (${(s.progress * 100).toInt()}%)"
                    is NeuralPackState.Ready -> "Stato: pronto · ${s.cardCount} carte"
                    is NeuralPackState.Error -> "Errore: ${s.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (neural is NeuralPackState.Downloading) {
                LinearProgressIndicator(
                    progress = { (neural as NeuralPackState.Downloading).progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = neural is NeuralPackState.Downloading || neural is NeuralPackState.Loading
                Button(
                    onClick = viewModel::downloadNeuralPack,
                    enabled = !busy && neural !is NeuralPackState.Ready,
                ) {
                    Text(
                        when (neural) {
                            is NeuralPackState.Ready -> "Installato"
                            is NeuralPackState.Downloading -> "Download…"
                            else -> "Scarica pack"
                        },
                    )
                }
                if (neural is NeuralPackState.Ready || neural is NeuralPackState.InstalledNotLoaded || neural is NeuralPackState.Error) {
                    OutlinedButton(onClick = viewModel::deleteNeuralPack, enabled = !busy) {
                        Text("Rimuovi")
                    }
                }
            }

            Text(
                "I prezzi CardTrader non sono impostati in questa versione: lasciali vuoti e sistemali sul sito dopo l'import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
