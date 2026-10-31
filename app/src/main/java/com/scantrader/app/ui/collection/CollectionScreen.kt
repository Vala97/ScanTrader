package com.scantrader.app.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.data.catalog.ListingAverage
import com.scantrader.app.data.db.CardPrice
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.repo.RenameCollection
import com.scantrader.app.ui.CollectionViewModel
import com.scantrader.app.ui.home.RenameCollectionDialog
import com.scantrader.app.ui.components.CardImage
import com.scantrader.app.ui.components.SimpleDropdown
import com.scantrader.app.ui.theme.scanTraderAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    val collection by viewModel.collection.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = collection?.name ?: "Collezione"
    var editingId by remember { mutableStateOf<Long?>(null) }
    // giochi ed espansioni chiusi a mano (restano chiusi girando il telefono)
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    // "Manda al tracker": copie non ancora mandate (le carte salvate dopo il collegamento partono gia' da sole)
    val tracker = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.scantrader.app.ScanTraderApp).tracker
    val trackerScope = androidx.compose.runtime.rememberCoroutineScope()
    var askSend by remember { mutableStateOf(false) }
    var toSend by remember { mutableStateOf(0) }
    var sendMsg by remember { mutableStateOf<String?>(null) }
    if (askSend) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askSend = false },
            title = { Text("Manda al DLS Tracker") },
            text = {
                Text(
                    sendMsg ?: if (toSend == 0) "Tutte le carte di questa collezione sono già state mandate al tracker."
                    else "$toSend copie non ancora mandate. Arrivano nel tuo registro e dopo 10 minuti su Cardtrader, con le tue Regole prezzi; nel frattempo puoi toglierle dal tracker.\n\nSe le hai già caricate su Cardtrader in un altro modo (file CSV), non mandarle: verrebbero aggiunte di nuovo."
                )
            },
            confirmButton = {
                if (toSend > 0 && sendMsg == null) {
                    androidx.compose.material3.TextButton(onClick = {
                        val id = collection?.id ?: return@TextButton
                        trackerScope.launch { sendMsg = runCatching { "Mandate ${tracker.sendCollection(id)} copie al tracker." }.getOrElse { it.message ?: "Invio non riuscito." } }
                    }) { Text("Manda $toSend copie") }
                } else androidx.compose.material3.TextButton(onClick = { askSend = false }) { Text("OK") }
            },
            dismissButton = { if (toSend > 0 && sendMsg == null) androidx.compose.material3.TextButton(onClick = { askSend = false }) { Text("Annulla") } },
        )
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val editing = cards.firstOrNull { it.id == editingId }
    val sections = browser.result.sections

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                colors = scanTraderAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (tracker.linked) {
                        IconButton(onClick = {
                            val id = collection?.id ?: return@IconButton
                            trackerScope.launch { toSend = tracker.unsent(id).sumOf { it.second }; sendMsg = null; askSend = true }
                        }, enabled = collection != null) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "Manda al DLS Tracker")
                        }
                    }
                    IconButton(onClick = {
                        renameError = null
                        renaming = true
                    }, enabled = collection != null) {
                        Icon(Icons.Default.Edit, contentDescription = "Rinomina")
                    }
                    IconButton(
                        onClick = { viewModel.share(context) },
                        enabled = cards.isNotEmpty() && !exporting,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Esporta un CSV per gioco")
                    }
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Scan")
                    }
                },
            )
        },
    ) { padding ->
        if (cards.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nessuna carta", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Scansiona o cerca per aggiungere.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                CollectionBrowseBar(
                    browser = browser,
                    onQuery = viewModel::setQuery,
                    onToggleFilters = { viewModel.setFiltersOpen(!browser.controls.filtersOpen) },
                    onToggleSet = viewModel::toggleSet,
                    onToggleLanguage = viewModel::toggleLanguage,
                    onToggleCondition = viewModel::toggleCondition,
                    onToggleFoil = viewModel::toggleFoil,
                    onSort = viewModel::setSort,
                    onClear = viewModel::clearBrowse,
                )
                if (browser.result.copies == 0) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text("Nessuna carta", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Nessun risultato con questi filtri.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        var lastGame: Game? = null
                        sections.forEach { section ->
                            if (browser.result.grouped) {
                                // un'intestazione per gioco, poi le sue espansioni; tutte e due si chiudono toccandole
                                val g = section.game
                                if (g != null && g != lastGame) {
                                    lastGame = g
                                    val gameCopies = sections.filter { it.game == g }.sumOf { it.copies }
                                    item(key = "game-${g.name}", span = { GridItemSpan(maxLineSpan) }, contentType = "game") {
                                        GameHeader(g, gameCopies, open = "g:${g.name}" !in collapsed) {
                                            collapsed = collapsed.toggle("g:${g.name}")
                                        }
                                    }
                                }
                                if (g != null && "g:${g.name}" in collapsed) return@forEach
                                item(
                                    key = "section-${section.key}",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "header",
                                ) {
                                    SectionHeader(section, open = "s:${section.key}" !in collapsed) {
                                        collapsed = collapsed.toggle("s:${section.key}")
                                    }
                                }
                                if ("s:${section.key}" in collapsed) return@forEach
                            }
                            items(section.cards, key = { it.id }, contentType = { _ -> "card" }) { card ->
                                GalleryTile(card = card, onClick = { editingId = card.id })
                            }
                        }
                    }
                }
            }
        }
    }

    if (editing != null) {
        ModalBottomSheet(
            onDismissRequest = { editingId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CardEditSheet(
                card = editing,
                onQuantity = { next ->
                    viewModel.setQuantity(editing, next)
                    if (next <= 0) editingId = null
                },
                onCondition = { viewModel.setCondition(editing, it) },
                onLanguage = { viewModel.setLanguage(editing, it) },
                onFoil = { viewModel.setFoil(editing, it) },
                onManualPrice = { raw, onDone -> viewModel.setManualPrice(editing, raw, onDone) },
                onRestorePrice = { viewModel.setManualPrice(editing, null) },
                onDelete = {
                    viewModel.delete(editing)
                    editingId = null
                },
            )
        }
    }

    if (renaming && collection != null) {
        RenameCollectionDialog(
            initialName = collection?.name.orEmpty(),
            error = renameError,
            onDismiss = {
                renaming = false
                renameError = null
            },
            onConfirm = { name ->
                viewModel.rename(name) { result ->
                    when (result) {
                        RenameCollection.Saved -> {
                            renaming = false
                            renameError = null
                        }
                        RenameCollection.Empty -> renameError = "Inserisci un nome."
                        RenameCollection.Duplicate ->
                            renameError = "Esiste già una collezione con questo nome."
                    }
                }
            },
        )
    }
}

@Composable
private fun CollectionBrowseBar(
    browser: CollectionBrowser,
    onQuery: (String) -> Unit,
    onToggleFilters: () -> Unit,
    onToggleSet: (String) -> Unit,
    onToggleLanguage: (String) -> Unit,
    onToggleCondition: (String) -> Unit,
    onToggleFoil: (Boolean) -> Unit,
    onSort: (CollectionSort) -> Unit,
    onClear: () -> Unit,
) {
    val controls = browser.controls
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = controls.query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Cerca nome o espansione") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (controls.query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancella ricerca")
                        }
                    }
                },
            )
            BadgedBox(
                badge = {
                    if (controls.filtersActive) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary)
                    }
                },
            ) {
                IconButton(onClick = onToggleFilters) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = if (controls.filtersOpen) "Nascondi filtri" else "Mostra filtri",
                        tint = if (controls.filtersOpen || controls.filtersActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (controls.filtersOpen) {
            if (browser.sets.isNotEmpty()) {
                FilterChipRow(
                    title = "Espansione",
                    choices = browser.sets,
                    selected = controls.sets,
                    onToggle = onToggleSet,
                )
            }
            if (browser.languages.isNotEmpty()) {
                FilterChipRow(
                    title = "Lingua",
                    choices = browser.languages,
                    selected = controls.languages,
                    onToggle = onToggleLanguage,
                )
            }
            if (browser.conditions.isNotEmpty()) {
                FilterChipRow(
                    title = "Condizione",
                    choices = browser.conditions,
                    selected = controls.conditions,
                    onToggle = onToggleCondition,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Finitura",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = controls.foil == true,
                        onClick = { onToggleFoil(true) },
                        label = { Text("Foil") },
                    )
                    FilterChip(
                        selected = controls.foil == false,
                        onClick = { onToggleFoil(false) },
                        label = { Text("Normale") },
                    )
                }
            }
            SortPicker(sort = controls.sort, onSort = onSort)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                collectionSummary(browser.result.copies, browser.result.total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (controls.active) {
                TextButton(onClick = onClear) { Text("Azzera") }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    title: String,
    choices: List<CollectionChoice>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems(choices, key = { it.value }) { choice ->
                FilterChip(
                    selected = choice.value in selected,
                    onClick = { onToggle(choice.value) },
                    label = { Text(choice.label) },
                )
            }
        }
    }
}

@Composable
private fun SortPicker(
    sort: CollectionSort,
    onSort: (CollectionSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Ordine",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            TextButton(onClick = { open = true }) { Text(sortLabel(sort)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                CollectionSort.entries.forEach { option ->
                    SortMenuItem(
                        label = sortLabel(option),
                        selected = sort == option,
                        onClick = {
                            onSort(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun sortLabel(sort: CollectionSort): String = when (sort) {
    CollectionSort.COLLECTOR_NUMBER -> "Numero di collezione"
    CollectionSort.NAME -> "Nome"
    CollectionSort.PRICE_ASC -> "Prezzo crescente"
    CollectionSort.PRICE -> "Prezzo decrescente"
    CollectionSort.DATE_ADDED -> "Data di aggiunta"
}

@Composable
private fun SortMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null) }
        } else {
            null
        },
    )
}

@Composable
private fun GameHeader(game: Game, copies: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = (if (open) "▾ " else "▸ ") + game.shareLabel,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (copies == 1) "1 copia" else "$copies copie",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

@Composable
private fun SectionHeader(section: CollectionSection, open: Boolean = true, onToggle: () -> Unit = {}) {
    val copies = if (section.copies == 1) "1 copia" else "${section.copies} copie"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = (if (open) "▾ " else "▸ ") + section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = copies,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GalleryTile(
    card: CardEntryEntity,
    onClick: () -> Unit,
) {
    val price = tilePrice(card)
    val setCode = card.setCode.trim().uppercase()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        CardImage(
            url = card.imageUrl,
            contentDescription = card.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(63f / 88f),
        )
        Text(
            text = card.quantity.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                    ),
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            if (setCode.isNotEmpty()) {
                Text(
                    text = setCode,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            if (price != null) {
                Text(
                    text = price,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun tilePrice(card: CardEntryEntity): String? {
    val manual = card.manualPriceEur?.takeIf { it.isNotBlank() }
    if (manual != null) {
        return ListingAverage.display(eur = manual, eurFoil = null, source = "Manuale", foil = false)
    }
    val pokemon = card.game == Game.POKEMON
    return ListingAverage.display(
        eur = card.priceEur,
        eurFoil = if (pokemon) null else card.priceEurFoil,
        source = card.priceSource,
        foil = card.foil && !pokemon,
    )
}

@Composable
private fun CardEditSheet(
    card: CardEntryEntity,
    onQuantity: (Int) -> Unit,
    onCondition: (CardCondition) -> Unit,
    onLanguage: (CardLanguage) -> Unit,
    onFoil: (Boolean) -> Unit,
    onManualPrice: (String, (Boolean) -> Unit) -> Unit,
    onRestorePrice: () -> Unit,
    onDelete: () -> Unit,
) {
    val condition = CardCondition.entries.firstOrNull { it.csvValue == card.condition }
        ?: CardCondition.NEAR_MINT
    val language = CardLanguage.entries.firstOrNull { it.csvValue == card.language }
        ?: CardLanguage.ENGLISH
    val automatic = CardPrice.automaticRaw(card)
    var priceText by remember(card.id, card.manualPriceEur, automatic) {
        mutableStateOf(card.manualPriceEur ?: automatic.orEmpty())
    }
    var priceError by remember(card.id) { mutableStateOf<String?>(null) }
    val pokemon = card.game == Game.POKEMON
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CardImage(
                url = card.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(84.dp, 118.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${card.setCode.uppercase()} · ${card.collectorNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = card.game.shareLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onQuantity(card.quantity - 1) }) {
                Icon(Icons.Default.Remove, contentDescription = "Meno")
            }
            Text(
                text = "Quantità ${card.quantity}",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            IconButton(onClick = { onQuantity(card.quantity + 1) }) {
                Icon(Icons.Default.Add, contentDescription = "Più")
            }
        }
        SimpleDropdown(
            label = "Condizione",
            options = CardCondition.entries.toList(),
            selected = condition,
            onSelect = onCondition,
            labelOf = { it.label },
        )
        SimpleDropdown(
            label = "Lingua",
            options = CardLanguage.entries.toList(),
            selected = language,
            onSelect = onLanguage,
            labelOf = { it.label },
        )
        if (!pokemon) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Foil")
                Switch(checked = card.foil, onCheckedChange = onFoil)
            }
        }
        OutlinedTextField(
            value = priceText,
            onValueChange = {
                priceText = it
                priceError = null
            },
            label = { Text(if (!pokemon && card.foil) "Prezzo foil (EUR)" else "Prezzo (EUR)") },
            singleLine = true,
            isError = priceError != null,
            supportingText = {
                val hint = when {
                    priceError != null -> priceError
                    card.manualPriceEur != null -> "Prezzo manuale. L’automatico resta ${automatic ?: "n/d"}."
                    else -> "Prezzo automatico. Un valore qui lo sostituisce anche nell’export."
                }
                Text(hint.orEmpty())
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                onManualPrice(priceText) { saved ->
                    priceError = if (saved) null else "Inserisci un prezzo maggiore di zero."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Salva prezzo")
        }
        if (card.manualPriceEur != null) {
            TextButton(onClick = onRestorePrice, modifier = Modifier.fillMaxWidth()) {
                Text("Ripristina prezzo automatico")
            }
        }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Elimina")
        }
    }
}
