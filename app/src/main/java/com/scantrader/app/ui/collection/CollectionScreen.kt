package com.scantrader.app.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.data.export.CsvShare
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.ui.CollectionViewModel
import com.scantrader.app.ui.components.CardImage
import com.scantrader.app.ui.components.SimpleDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    val collection by viewModel.collection.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = collection?.name ?: "Collezione"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            CsvShare.share(context, title, cards)
                        },
                        enabled = cards.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Esporta CSV")
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
            ) {
                Text("Nessuna carta. Scansiona o cerca per aggiungere.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CardImage(
                                url = card.imageUrl,
                                contentDescription = card.name,
                                modifier = Modifier.size(72.dp, 100.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(card.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${card.setCode.uppercase()} · ${card.collectorNumber} · ${card.game.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    listOfNotNull(
                                        card.language,
                                        if (card.foil) "Foil" else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val avgPrice = if (card.foil) {
                                    card.priceEurFoil?.takeIf { it.isNotBlank() } ?: card.priceEur
                                } else {
                                    card.priceEur
                                }
                                Text(
                                    text = if (!avgPrice.isNullOrBlank()) {
                                        "Prezzo medio: €$avgPrice"
                                    } else {
                                        "Prezzo medio: n/d"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                val selectedCondition = CardCondition.entries
                                    .firstOrNull { it.csvValue == card.condition }
                                    ?: CardCondition.NEAR_MINT
                                SimpleDropdown(
                                    label = "Condizione",
                                    options = CardCondition.entries.toList(),
                                    selected = selectedCondition,
                                    onSelect = { viewModel.setCondition(card, it) },
                                    labelOf = { it.label },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.setQuantity(card, card.quantity - 1) }) {
                                        Icon(Icons.Default.Remove, contentDescription = "Meno")
                                    }
                                    Text("${card.quantity}", modifier = Modifier.padding(horizontal = 8.dp))
                                    IconButton(onClick = { viewModel.setQuantity(card, card.quantity + 1) }) {
                                        Icon(Icons.Default.Add, contentDescription = "Più")
                                    }
                                    IconButton(onClick = { viewModel.delete(card) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Elimina")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
