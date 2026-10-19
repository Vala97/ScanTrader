package com.scantrader.app.ui.confirm

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.ui.ConfirmViewModel
import com.scantrader.app.ui.components.CardImage
import com.scantrader.app.ui.components.SimpleDropdown

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
    var query by remember { mutableStateOf(viewModel.parsed?.nameGuess.orEmpty()) }
    var selected by remember { mutableStateOf<IdentifiedCard?>(null) }
    var quantity by remember { mutableIntStateOf(1) }
    var language by remember { mutableStateOf(defaults.language) }
    var condition by remember { mutableStateOf(defaults.condition) }
    var foil by remember { mutableStateOf(false) }
    var collectionId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { viewModel.reload() }
    LaunchedEffect(defaults) {
        language = defaults.language
        condition = defaults.condition
    }
    LaunchedEffect(candidates) {
        if (selected == null || candidates.none { it.scryfallId == selected?.scryfallId && it.setCode == selected?.setCode && it.collectorNumber == selected?.collectorNumber }) {
            selected = candidates.firstOrNull()
        }
    }
    LaunchedEffect(collections) {
        if (collectionId == null) {
            collectionId = collections.firstOrNull()?.id
        }
    }

    val displayedPrice = selected?.let { card ->
        if (foil) card.priceEurFoil?.takeIf { it.isNotBlank() } ?: card.priceEur else card.priceEur
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!viewModel.errorMessage.isNullOrBlank()) {
                item { Text(viewModel.errorMessage!!) }
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
                        Text(
                            text = when {
                                displayedPrice != null && foil -> "Prezzo Cardmarket (foil): €$displayedPrice"
                                displayedPrice != null -> "Prezzo Cardmarket: €$displayedPrice"
                                else -> "Prezzo EUR non disponibile su Scryfall"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
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
                            onSelect = { collectionId = it.id },
                            labelOf = { it.name },
                        )
                    }
                }
                item {
                    SimpleDropdown(
                        label = "Lingua",
                        options = CardLanguage.entries.toList(),
                        selected = language,
                        onSelect = { language = it },
                        labelOf = { it.label },
                    )
                }
                item {
                    SimpleDropdown(
                        label = "Condizione",
                        options = CardCondition.entries.toList(),
                        selected = condition,
                        onSelect = { condition = it },
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
                        Switch(checked = foil, onCheckedChange = { foil = it })
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (quantity > 1) quantity -= 1 }) {
                            Icon(Icons.Default.Remove, contentDescription = "Meno")
                        }
                        Text("Quantità $quantity", modifier = Modifier.padding(horizontal = 8.dp))
                        IconButton(onClick = { quantity += 1 }) {
                            Icon(Icons.Default.Add, contentDescription = "Più")
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            val card = selected
                            val target = collectionId
                            if (card != null && target != null) {
                                viewModel.save(card, target, quantity, language, condition, foil, onSaved)
                            }
                        },
                        enabled = collectionId != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("OK · Salva in collezione")
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
                    onValueChange = { query = it },
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
                    onClick = { viewModel.search(query) },
                    enabled = query.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "Ricerca…" else "Cerca nel catalogo") }
            }
            if (candidates.isEmpty()) {
                item { Text("Nessun candidato. Controlla il nome, il set o il numero collezionista.") }
            }
            items(candidates, key = { it.scryfallId ?: it.name + it.setCode + it.collectorNumber }) { card ->
                val isSelected = selected?.scryfallId == card.scryfallId &&
                    selected?.setCode == card.setCode &&
                    selected?.collectorNumber == card.collectorNumber
                Card(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { selected = card },
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
                        }
                    }
                }
            }
        }
    }
}
