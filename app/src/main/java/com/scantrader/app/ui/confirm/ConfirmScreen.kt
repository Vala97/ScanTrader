package com.scantrader.app.ui.confirm

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.scan.ScanDebug
import com.scantrader.app.data.catalog.ListingAverage
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.ui.ConfirmViewModel
import com.scantrader.app.ui.components.CardImage
import com.scantrader.app.ui.components.SimpleDropdown
import com.scantrader.app.ui.theme.scanTraderAppBarColors
import com.scantrader.app.vision.MatchFusion
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    viewModel: ConfirmViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(viewModel.parsed?.nameGuess.orEmpty()) }
    val selectedState = remember { mutableStateOf<IdentifiedCard?>(null) }
    var selected by selectedState
    val quantityState = remember { mutableIntStateOf(1) }
    var quantity by quantityState
    var languageTouched by remember { mutableStateOf(false) }
    val languageState = remember {
        mutableStateOf(viewModel.sessionLanguage ?: defaults.language)
    }
    var language by languageState
    val conditionState = remember {
        mutableStateOf(viewModel.sessionCondition ?: defaults.condition)
    }
    var condition by conditionState
    val foilState = remember { mutableStateOf(false) }
    var foil by foilState
    val collectionIdState = remember { mutableStateOf(viewModel.preferredCollectionId) }
    var collectionId by collectionIdState
    val scanIdentity = listOf(
        viewModel.autoMatchCardKey,
        viewModel.parsed?.nameGuess,
        viewModel.parsed?.collectorNumber,
        viewModel.parsed?.setCode,
    ).joinToString("|")
    val autoSaveCancelledState = remember(scanIdentity) { mutableStateOf(false) }
    val secondsLeftState = remember(scanIdentity) { mutableIntStateOf(0) }
    val autoSaveOfferedState = remember { mutableStateOf(false) }
    val autoSaveCancelled = autoSaveCancelledState.value
    var secondsLeft by secondsLeftState
    val autoMatch = viewModel.autoMatch
    val gatedKey = viewModel.autoMatchCardKey
    val selectedKey = selected?.identityKey()
    val selectedMatchesGate = gatedKey == null || selectedKey == null || gatedKey == selectedKey
    val autoSaveOffered = viewModel.fromAutoScan &&
        defaults.autoSaveWaitSeconds > 0 &&
        MatchFusion.allowsAutoSave(autoMatch)
    autoSaveOfferedState.value = autoSaveOffered
    val saveLabels = AutoSaveControl.present(
        offered = autoSaveOffered,
        cancelled = autoSaveCancelled,
        selectedMatchesGate = selectedMatchesGate,
        secondsLeft = secondsLeft,
        saving = saving,
    )
    val autoSaveArmed = saveLabels.armed
    val matchNote = autoMatch?.let { decision ->
        MatchFusion.confirmMatchNote(
            decision = decision,
            fromAutoScan = viewModel.fromAutoScan,
            autoSaveWaitSeconds = defaults.autoSaveWaitSeconds,
            selectedMatchesGate = selectedMatchesGate,
            autoSaveCancelled = autoSaveCancelled,
        )
    }

    fun cancelAutoSave(gesture: AutoSaveControl.Gesture) {
        if (!AutoSaveControl.onUserInteraction(autoSaveOfferedState.value, gesture)) return
        if (!autoSaveCancelledState.value) {
            ScanDebug.d("autosave: cancelled(${gesture.name.lowercase()})")
        }
        autoSaveCancelledState.value = true
        secondsLeft = 0
    }

    val cancelAutoSaveRef = remember {
        object {
            var block: (AutoSaveControl.Gesture) -> Unit = {}
        }
    }
    cancelAutoSaveRef.block = { gesture -> cancelAutoSave(gesture) }

    fun doSave() {
        val card = selected ?: return
        val target = collectionId ?: return
        cancelAutoSave(AutoSaveControl.Gesture.TAP)
        viewModel.save(card, target, quantity, language, condition, foil, onSaved)
    }

    LaunchedEffect(Unit) { viewModel.reload() }
    LaunchedEffect(defaults) {
        condition = viewModel.sessionCondition ?: defaults.condition
        if (!languageTouched && !defaults.detectCardLanguage) {
            language = viewModel.sessionLanguage ?: defaults.language
        }
    }
    LaunchedEffect(selected?.identityKey(), defaults.detectCardLanguage) {
        if (languageTouched || !defaults.detectCardLanguage) return@LaunchedEffect
        val card = selected ?: return@LaunchedEffect
        val detected = viewModel.detectCardLanguage(card)
        autoSaveCancelledState.value = AutoSaveControl.onProgrammaticUpdate(
            autoSaveCancelledState.value,
            AutoSaveControl.Program.DETECTED_LANGUAGE,
        )
        language = CardLanguageDetector.choose(
            detectEnabled = true,
            detected = detected,
            lastChosen = viewModel.sessionLanguage ?: defaults.language,
        )
    }
    LaunchedEffect(selected?.identityKey(), language, condition, foil) {
        val card = selected ?: return@LaunchedEffect
        val priced = viewModel.proposePrice(card, language, condition, foil)
        autoSaveCancelledState.value = AutoSaveControl.onProgrammaticUpdate(
            autoSaveCancelledState.value,
            AutoSaveControl.Program.PRICE,
        )
        val changed = priced.priceEur != card.priceEur ||
            priced.priceEurFoil != card.priceEurFoil ||
            priced.priceUsd != card.priceUsd ||
            priced.priceSource != card.priceSource ||
            priced.priceCurrency != card.priceCurrency
        if (changed) selected = priced
    }
    LaunchedEffect(candidates) {
        if (selected == null || candidates.none { sameCard(it, selected) }) {
            selected = candidates.firstOrNull()
        }
    }
    LaunchedEffect(collections, viewModel.preferredCollectionId) {
        val preferred = viewModel.preferredCollectionId
        val currentStillValid = collectionId != null && collections.any { it.id == collectionId }
        if (currentStillValid) return@LaunchedEffect
        collectionId = when {
            preferred != null && collections.any { it.id == preferred } -> preferred
            else -> collections.firstOrNull()?.id
        }
    }
    LaunchedEffect(autoSaveArmed, selected?.identityKey(), collectionId, defaults.autoSaveWaitSeconds) {
        if (!autoSaveArmed || selectedState.value == null || collectionIdState.value == null) return@LaunchedEffect
        val wait = defaults.autoSaveWaitSeconds
        if (wait <= 0) return@LaunchedEffect
        ScanDebug.d("autosave: scheduled")
        for (left in wait downTo 1) {
            if (autoSaveCancelledState.value) return@LaunchedEffect
            secondsLeftState.intValue = left
            delay(1_000)
        }
        if (autoSaveCancelledState.value) return@LaunchedEffect
        secondsLeftState.intValue = 0
        val card = selectedState.value
        val target = collectionIdState.value
        if (card != null && target != null && !viewModel.saving.value) {
            ScanDebug.d("autosave: saved")
            viewModel.save(
                card,
                target,
                quantityState.intValue,
                languageState.value,
                conditionState.value,
                foilState.value,
                onSaved,
            )
        }
    }

    val priceLine = selected?.let { card ->
        ListingAverage.display(
            eur = card.priceEur,
            eurFoil = card.priceEurFoil,
            source = card.priceSource,
            foil = foil,
        )
    }
    val canSave = selected != null && collectionId != null && !saving
    val saveLabel = saveLabels.saveButtonLabel

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = scanTraderAppBarColors(),
                title = {
                    Column {
                        Text(selected?.name ?: "Conferma carta")
                        selected?.let { card ->
                            Text(
                                "${card.setCode.uppercase()} · ${card.collectorNumber}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        cancelAutoSave(AutoSaveControl.Gesture.TAP)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
        bottomBar = {
            ConfirmSaveBar(
                label = saveLabel,
                enabled = canSave,
                missingCollection = selected != null && collectionId == null,
                statusText = saveLabels.statusLabel,
                onSave = { doSave() },
            )
        },
    ) { padding ->
        val cancelOnScroll = remember(autoSaveCancelledState, autoSaveOfferedState, secondsLeftState) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && (available.x != 0f || available.y != 0f)) {
                        cancelAutoSaveRef.block(AutoSaveControl.Gesture.SCROLL)
                    }
                    return Offset.Zero
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(cancelOnScroll)
                .pointerInput(autoSaveCancelledState, autoSaveOfferedState, secondsLeftState) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        cancelAutoSaveRef.block(AutoSaveControl.Gesture.TAP)
                    }
                },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!viewModel.errorMessage.isNullOrBlank()) {
                item { Text(viewModel.errorMessage!!) }
            }
            matchNote?.let { note ->
                item { MatchNote(note) }
            }

            selected?.let { current ->
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CardImage(
                            url = current.imageUrl,
                            contentDescription = current.name,
                            modifier = Modifier
                                .width(200.dp)
                                .height(280.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${current.setName} (${current.setCode.uppercase()}) · ${current.collectorNumber}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        current.confidenceLabel()?.let { pct ->
                            val visual = current.visualScore?.let { v -> " · arte ${(v * 100).toInt()}%" }.orEmpty()
                            Text(
                                "Match $pct$visual",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            text = priceLine?.let { "Prezzo medio: $it" } ?: "Prezzo medio: n/d",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { cancelAutoSave(AutoSaveControl.Gesture.PRICE) },
                        )
                        if (!foil && !current.priceEurFoil.isNullOrBlank()) {
                            Text(
                                "Foil: €${current.priceEurFoil}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    if (collections.isNotEmpty()) {
                        SimpleDropdown(
                            label = "Collezione",
                            options = collections,
                            selected = collections.firstOrNull { it.id == collectionId } ?: collections.first(),
                            onSelect = {
                                cancelAutoSave(AutoSaveControl.Gesture.FIELD)
                                collectionId = it.id
                            },
                            labelOf = { it.name },
                        )
                    }
                }
                item {
                    SimpleDropdown(
                        label = "Lingua",
                        options = CardLanguage.entries.toList(),
                        selected = language,
                        onSelect = {
                            cancelAutoSave(AutoSaveControl.Gesture.LANGUAGE)
                            languageTouched = true
                            language = it
                            viewModel.setLanguage(it)
                        },
                        labelOf = { it.label },
                    )
                }
                item {
                    SimpleDropdown(
                        label = "Condizione",
                        options = CardCondition.entries.toList(),
                        selected = condition,
                        onSelect = {
                            cancelAutoSave(AutoSaveControl.Gesture.CONDITION)
                            condition = it
                            viewModel.setCondition(it)
                        },
                        labelOf = { it.label },
                    )
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Foil / holo")
                        Switch(
                            checked = foil,
                            onCheckedChange = {
                                cancelAutoSave(AutoSaveControl.Gesture.FOIL)
                                foil = it
                            },
                        )
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            cancelAutoSave(AutoSaveControl.Gesture.QUANTITY)
                            if (quantity > 1) quantity -= 1
                        }) {
                            Icon(Icons.Default.Remove, contentDescription = "Meno")
                        }
                        Text("Quantità $quantity", modifier = Modifier.padding(horizontal = 8.dp))
                        IconButton(onClick = {
                            cancelAutoSave(AutoSaveControl.Gesture.QUANTITY)
                            quantity += 1
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Più")
                        }
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Cerca / cambia stampa",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        cancelAutoSave(AutoSaveControl.Gesture.FIELD)
                        query = it
                    },
                    label = { Text("Cerca per nome") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val parsed = viewModel.parsed
                        Text(
                            "OCR: ${parsed?.nameGuess ?: "—"} · n° ${parsed?.collectorNumber ?: "—"} · set ${parsed?.setCode ?: "—"}",
                        )
                    },
                )
            }
            item {
                Button(
                    onClick = {
                        cancelAutoSave(AutoSaveControl.Gesture.FIELD)
                        viewModel.search(query)
                    },
                    enabled = query.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "Ricerca…" else "Cerca nel catalogo") }
            }
            if (candidates.isEmpty()) {
                item { Text("Nessun candidato. Controlla il nome, il set o il numero collezionista.") }
            }
            items(candidates, key = { candidateKey(it) }) { card ->
                val isSelected = sameCard(selected, card)
                ElevatedCard(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                            } else {
                                Modifier
                            },
                        )
                        .clickable {
                            cancelAutoSave(AutoSaveControl.Gesture.CANDIDATE)
                            selected = card
                        },
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardImage(
                            url = card.imageUrl,
                            contentDescription = card.name,
                            modifier = Modifier.size(72.dp, 100.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(card.name, style = MaterialTheme.typography.titleMedium)
                            Text("${card.setName} (${card.setCode.uppercase()}) · ${card.collectorNumber}")
                            val listPrice = card.priceEur
                            if (!listPrice.isNullOrBlank()) {
                                Text(
                                    "€$listPrice",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            card.confidenceLabel()?.let { pct ->
                                Text(
                                    "Match $pct",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmSaveBar(
    label: String,
    enabled: Boolean,
    missingCollection: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (!statusText.isNullOrBlank()) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (missingCollection) {
                Text(
                    "Nessuna collezione disponibile. Creane una dalla home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Button(
                onClick = onSave,
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .semantics { contentDescription = "Salva in collezione" },
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun MatchNote(note: MatchFusion.ConfirmMatchNote) {
    val (container, content) = when (note.tone) {
        MatchFusion.ConfirmMatchTone.AUTO_SAVE ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        MatchFusion.ConfirmMatchTone.CONFIRM ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        MatchFusion.ConfirmMatchTone.BLOCKED ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        color = container,
                    shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(note.title, fontWeight = FontWeight.SemiBold, color = content)
            Text(note.body, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

private fun sameCard(a: IdentifiedCard?, b: IdentifiedCard?): Boolean {
    if (a == null || b == null) return false
    return a.setCode == b.setCode &&
        a.collectorNumber == b.collectorNumber &&
        a.name == b.name &&
        (a.scryfallId ?: "") == (b.scryfallId ?: "")
}

private fun candidateKey(card: IdentifiedCard): String =
    listOf(
        card.scryfallId.orEmpty(),
        card.setCode,
        card.collectorNumber,
        card.name,
        card.imageUrl.orEmpty(),
    ).joinToString("|")
