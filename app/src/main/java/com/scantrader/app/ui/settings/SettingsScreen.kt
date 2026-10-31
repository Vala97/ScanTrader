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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.scantrader.app.R
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.scan.LensCatalog
import com.scantrader.app.scan.LensOption
import com.scantrader.app.ui.NeuralPackPrompt
import com.scantrader.app.ui.SettingsViewModel
import com.scantrader.app.ui.components.SimpleDropdown
import com.scantrader.app.ui.update.AppUpdateDialog
import com.scantrader.app.ui.displayLabel
import com.scantrader.app.ui.theme.scanTraderAppBarColors
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.NeuralPackState
import com.scantrader.app.vision.PokemonCatalogController
import com.scantrader.app.vision.PokemonCatalogRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onReviewIntro: () -> Unit,
) {
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val tokenSaved by viewModel.hasCardTraderToken.collectAsStateWithLifecycle()
    val neural by viewModel.neuralPackState.collectAsStateWithLifecycle()
    val pokemonNeural by viewModel.pokemonPackState.collectAsStateWithLifecycle()
    val pokemonCatalogs by viewModel.pokemonCatalogs.collectAsStateWithLifecycle()
    val lensOptions by viewModel.lensOptions.collectAsStateWithLifecycle()
    val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()

    val importPokemon = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importPokemonPack(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.loadNeuralPack()
        viewModel.refreshLenses()
        viewModel.refreshPokemonCatalogs()
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
                TextButton(onClick = onReviewIntro) { Text("Rivedi introduzione") }
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
                    "In auto-scan, se il riconoscimento supera la soglia scelta in Impostazioni scan " +
                        "e non tocchi la carta, salva in collezione dopo N secondi. Sotto la soglia resta in conferma.",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Mostra informazioni aggiuntive (debug)")
                        Text(
                            "In conferma mostra il banner «Conferma a mano» e la percentuale di riconoscimento.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = defaults.showDebugInfo,
                        onCheckedChange = viewModel::setShowDebugInfo,
                    )
                }
                if (appUpdate.githubSelfUpdate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Controlla aggiornamenti automaticamente")
                            Text(
                                "All’avvio, al massimo una volta al giorno.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = defaults.autoUpdateCheck,
                            onCheckedChange = viewModel::setAutoUpdateCheck,
                        )
                    }
                    Button(
                        onClick = viewModel::checkForAppUpdate,
                        enabled = !appUpdate.checking && !appUpdate.downloading,
                    ) {
                        Text(if (appUpdate.checking) "Controllo…" else "Controlla aggiornamenti")
                    }
                    if (appUpdate.checking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (appUpdate.offer == null) {
                        appUpdate.message?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                    "Pack neurale on-device (${NeuralPackManager.APPROX_SIZE_LABEL}). " +
                        "Utile quando l’OCR è debole; dopo il download resta offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MiloLicenseNote()
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
                    "Pack neurale on-device (AGPL-3.0). " +
                        "Utile quando l’OCR è debole; dopo il download resta offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MiloLicenseNote()
                if (pokemonCatalogs.checking) {
                    Text("Controllo elenco…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                pokemonCatalogs.message?.let { banner ->
                    Text(
                        banner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (banner == PokemonCatalogController.OFFLINE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                val manualInstalled = viewModel.hasManualPokemonCatalog()
                val catalogBusy = pokemonCatalogs.rows.any { it.downloading }
                val manualCount = viewModel.manualPokemonCardCount()
                    ?: if (pokemonCatalogs.rows.none { it.installed }) {
                        (pokemonNeural as? NeuralPackState.Ready)?.cardCount
                    } else {
                        null
                    }
                if (pokemonNeural is NeuralPackState.Downloading && !catalogBusy) {
                    Text(
                        manualPokemonStatus(pokemonNeural, manualCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { (pokemonNeural as NeuralPackState.Downloading).progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (manualInstalled) {
                    Text("Importato", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        manualPokemonStatus(pokemonNeural, manualCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = viewModel::deletePokemonPack) {
                        Text("Rimuovi")
                    }
                }
                pokemonCatalogs.rows.forEach { row ->
                    PokemonCatalogRowCard(
                        row = row,
                        onDownload = { viewModel.downloadPokemonCatalog(row.id) },
                        onDelete = { viewModel.deletePokemonCatalog(row.id) },
                    )
                }
                TextButton(
                    onClick = viewModel::refreshPokemonCatalogs,
                    enabled = !pokemonCatalogs.checking,
                ) {
                    Text("Controlla aggiornamenti")
                }
                if (defaults.showDebugInfo) {
                    val importBusy = pokemonNeural is NeuralPackState.Downloading ||
                        pokemonNeural is NeuralPackState.Loading
                    TextButton(
                        onClick = {
                            importPokemon.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        enabled = !importBusy,
                    ) {
                        Text("Importa .stcat", style = MaterialTheme.typography.bodySmall)
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
    AppUpdateDialog(
        state = appUpdate,
        onLater = viewModel::dismissAppUpdate,
        onInstall = viewModel::installAppUpdate,
        onOpenSettings = viewModel::openAppUpdateSettings,
    )
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
            "Token salvato sul telefono, cifrato. One Piece e Riftbound trovano le carte solo con questo token. Il prezzo Magic è la media CardTrader."
        } else {
            "One Piece e Riftbound non trovano le carte senza questo token. Il prezzo Magic, senza token, resta Scryfall in euro. Il token non esce dal telefono."
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
private fun PokemonCatalogRowCard(
    row: PokemonCatalogRow,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.name, style = MaterialTheme.typography.bodyMedium)
        Text(row.statusLine(), style = MaterialTheme.typography.bodyMedium)
        row.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (row.downloading) {
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!row.installed) {
                Button(onClick = onDownload, enabled = !row.downloading) { Text("Scarica") }
            } else if (row.updateAvailable) {
                Button(onClick = onDownload, enabled = !row.downloading) { Text("Aggiorna") }
            }
            if (row.installed) {
                OutlinedButton(onClick = onDelete, enabled = !row.downloading) { Text("Rimuovi") }
            }
        }
    }
}

private fun manualPokemonStatus(state: NeuralPackState, cardCount: Int?): String = when (state) {
    is NeuralPackState.Downloading -> "${state.label} (${(state.progress * 100).toInt()}%)"
    is NeuralPackState.Loading -> "Caricamento…"
    is NeuralPackState.Error -> "Errore: ${state.message}"
    is NeuralPackState.Ready -> readyCount(cardCount ?: state.cardCount)
    NeuralPackState.InstalledNotLoaded,
    NeuralPackState.NotInstalled,
    -> readyCount(cardCount)
}

private fun readyCount(cardCount: Int?): String =
    if (cardCount != null && cardCount > 0) "Pronto · $cardCount carte" else "Pronto"

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

@Composable
private fun MiloLicenseNote() {
    val links = LocalUriHandler.current
    TextButton(onClick = { runCatching { links.openUri(NeuralPackPrompt.MILO_SOURCE_URL) } }) {
        Text(NeuralPackPrompt.MILO_LICENSE_NOTE)
    }
}

private fun neuralStatusLabel(state: NeuralPackState): String = when (state) {
    is NeuralPackState.NotInstalled -> "Non installato"
    is NeuralPackState.InstalledNotLoaded -> "Scaricato, non in memoria"
    is NeuralPackState.Loading -> "Caricamento…"
    is NeuralPackState.Downloading -> "${state.label} (${(state.progress * 100).toInt()}%)"
    is NeuralPackState.Ready -> "Pronto · ${state.cardCount} carte"
    is NeuralPackState.Error -> "Errore: ${state.message}"
}
