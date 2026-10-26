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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.CollectionViewModel
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
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = collection?.name ?: "Collezione"
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    val sections = remember(cards, sort) { collectionSections(cards, sort) }
    val editing = cards.firstOrNull { it.id == editingId }

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
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Ordina")
                        }
                        DropdownMenu(
                            expanded = sortMenu,
                            onDismissRequest = { sortMenu = false },
                        ) {
                            SortMenuItem(
                                label = "Numero di collezione",
                                selected = sort == CollectionSort.COLLECTOR_NUMBER,
                                onClick = {
                                    viewModel.setSort(CollectionSort.COLLECTOR_NUMBER)
                                    sortMenu = false
                                },
                            )
                            SortMenuItem(
                                label = "Prezzo",
                                selected = sort == CollectionSort.PRICE,
                                onClick = {
                                    viewModel.setSort(CollectionSort.PRICE)
                                    sortMenu = false
                                },
                            )
                        }
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    item(
                        key = "section-${section.key}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) {
                        SectionHeader(section)
                    }
                    items(section.cards, key = { it.id }, contentType = { _ -> "card" }) { card ->
                        GalleryTile(card = card, onClick = { editingId = card.id })
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
                onDelete = {
                    viewModel.delete(editing)
                    editingId = null
                },
            )
        }
    }
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
private fun SectionHeader(section: CollectionSection) {
    val copies = if (section.copies == 1) "1 copia" else "${section.copies} copie"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = section.title,
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
    onDelete: () -> Unit,
) {
    val condition = CardCondition.entries.firstOrNull { it.csvValue == card.condition }
        ?: CardCondition.NEAR_MINT
    val language = CardLanguage.entries.firstOrNull { it.csvValue == card.language }
        ?: CardLanguage.ENGLISH
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
        if (card.game != Game.POKEMON) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Foil")
                Switch(checked = card.foil, onCheckedChange = onFoil)
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
