package com.scantrader.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.scantrader.app.R
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.scan.LensCatalog
import com.scantrader.app.scan.LensOption
import com.scantrader.app.ui.SettingsViewModel
import com.scantrader.app.ui.components.SimpleDropdown
import com.scantrader.app.ui.displayLabel
import com.scantrader.app.ui.theme.scanTraderAppBarColors
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.NeuralPackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val tokenSaved by viewModel.hasCardTraderToken.collectAsStateWithLifecycle()
    val neural by viewModel.neuralPackState.collectAsStateWithLifecycle()
    val pokemonNeural by viewModel.pokemonPackState.collectAsStateWithLifecycle()
    val lensOptions by viewModel.lensOptions.collectAsStateWithLifecycle()

    val importPokemon = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importPokemonPack(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.loadNeuralPack()
        viewModel.refreshLenses()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = scanTraderAppBarColors(),
                title = { Text("Impostazioni", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection(title = "Impostazioni generali") {
                SimpleDropdown(
                    label = "Lingua",
                    options = CardLanguage.entries.toList(),
                    selected = defaults.language,
                    onSelect = viewModel::setLanguage,
                    labelOf = { it.label },
                )
                SimpleDropdown(
                    label = "Condizione",
                    options = CardCondition.entries.toList(),
                    selected = defaults.condition,
                    onSelect = viewModel::setCondition,
                    labelOf = { it.label },
                )
                val waitOptions = com.scantrader.app.data.prefs.AUTO_SAVE_WAIT_OPTIONS
                val waitSelected = defaults.autoSaveWaitSeconds
                    .takeIf { it in waitOptions } ?: 0
                SimpleDropdown(
                    label = "Auto-Scan · attesa salvataggio",
                    options = waitOptions,
                    selected = waitSelected,
                    onSelect = viewModel::setAutoSaveWaitSeconds,
                    labelOf = { seconds ->
                        if (seconds == 0) "Disattivato" else "$seconds s"
                    },
                )
                Text(
                    "In auto-scan, se il riconoscimento supera la soglia forte (74%) e non tocchi la carta, " +
                        "salva in collezione dopo N secondi. I match deboli restano in conferma.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Rileva lingua dalla carta")
                        Text(
                            "In conferma, per Magic e Pokémon, preseleziona la lingua letta sulla carta. Se non è chiara, resta l’ultima scelta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = defaults.detectCardLanguage,
                        onCheckedChange = viewModel::setDetectCardLanguage,
                    )
                }
                CardTraderTokenField(
                    saved = tokenSaved,
                    onSave = viewModel::saveCardTraderToken,
                    onClear = viewModel::clearCardTraderToken,
                )
                if (lensOptions.size > 1) {
                    LensDropdown(
                        options = lensOptions,
                        lensKey = defaults.lensKey,
                        legacyCameraId = defaults.cameraId,
                        onSelect = viewModel::setLens,
                    )
                }
            }

            SettingsSection(title = "Riconoscimento MTG") {
                Text(
                    "Pack neurale on-device (~${NeuralPackManager.APPROX_SIZE_LABEL}). " +
                        "Utile quando l’OCR è debole; dopo il download resta offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    neuralStatusLabel(neural),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (neural is NeuralPackState.Downloading) {
                    LinearProgressIndicator(
                        progress = { (neural as NeuralPackState.Downloading).progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val busy = neural is NeuralPackState.Downloading || neural is NeuralPackState.Loading
                    Button(
                        onClick = viewModel::downloadNeuralPack,
                        enabled = !busy && neural !is NeuralPackState.Ready,
                    ) {
                        Text(
                            when (neural) {
                                is NeuralPackState.Ready -> "Installato"
                                is NeuralPackState.Downloading -> "Download…"
                                else -> "Scarica"
                            },
                        )
                    }
                    if (neural is NeuralPackState.Ready ||
                        neural is NeuralPackState.InstalledNotLoaded ||
                        neural is NeuralPackState.Error
                    ) {
                        OutlinedButton(onClick = viewModel::deleteNeuralPack, enabled = !busy) {
                            Text("Rimuovi")
                        }
                    }
                }
            }

            SettingsSection(title = "Riconoscimento Pokémon") {
                Text(
                    NeuralPackManager.POKEMON_PACK_HINT +
                        " Gallery reverse-image on-device; l’OCR resta per i reprint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    neuralStatusLabel(pokemonNeural),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (pokemonNeural is NeuralPackState.Downloading) {
                    LinearProgressIndicator(
                        progress = { (pokemonNeural as NeuralPackState.Downloading).progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val busy = pokemonNeural is NeuralPackState.Downloading ||
                        pokemonNeural is NeuralPackState.Loading
                    Button(
                        onClick = {
                            importPokemon.launch(
                                arrayOf("application/octet-stream", "*/*"),
                            )
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            when (pokemonNeural) {
                                is NeuralPackState.Ready -> "Reimporta .stcat"
                                is NeuralPackState.Downloading -> "Import…"
                                else -> "Importa .stcat"
                            },
                        )
                    }
                    if (pokemonNeural is NeuralPackState.Ready ||
                        pokemonNeural is NeuralPackState.InstalledNotLoaded ||
                        pokemonNeural is NeuralPackState.Error
                    ) {
                        OutlinedButton(onClick = viewModel::deletePokemonPack, enabled = !busy) {
                            Text("Rimuovi")
                        }
                    }
                }
            }

            Text(
                "Il CSV include il prezzo in centesimi (price_cents). Su CardTrader carica un file per gioco e scegli aggiungi allo stock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CardTraderTokenField(
    saved: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Text("Token API CardTrader")
    Text(
        if (saved) {
            "Token salvato sul telefono, cifrato. Il prezzo Magic è la media CardTrader. Senza token resta Scryfall in euro."
        } else {
            "Senza token il prezzo Magic è solo Scryfall in euro: nessuna chiamata a CardTrader. Con un token si usa la media delle inserzioni. Il token non esce dal telefono."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (saved) "Sostituisci token" else "Token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
            Text("Salva token")
        }
        if (saved) {
            TextButton(onClick = onClear) { Text("Rimuovi") }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LensDropdown(
    options: List<LensOption>,
    lensKey: String?,
    legacyCameraId: String?,
    onSelect: (LensOption) -> Unit,
) {
    val resources = LocalContext.current.resources
    val selected = LensCatalog.resolve(options, lensKey, legacyCameraId) ?: options.first()
    SimpleDropdown(
        label = stringResource(R.string.lens_field),
        options = options,
        selected = selected,
        onSelect = onSelect,
        labelOf = { it.displayLabel(resources) },
    )
    Text(
        stringResource(R.string.lens_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun neuralStatusLabel(state: NeuralPackState): String = when (state) {
    is NeuralPackState.NotInstalled -> "Non installato"
    is NeuralPackState.InstalledNotLoaded -> "Scaricato, non in memoria"
    is NeuralPackState.Loading -> "Caricamento…"
    is NeuralPackState.Downloading -> "${state.label} (${(state.progress * 100).toInt()}%)"
    is NeuralPackState.Ready -> "Pronto · ${state.cardCount} carte"
    is NeuralPackState.Error -> "Errore: ${state.message}"
}
