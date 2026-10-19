package com.scantrader.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.ui.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenCollection: (Long) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ScanTrader") },
                actions = {
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, contentDescription = "Info") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Impostazioni") }
                    IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = "Nuova collezione") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onScan) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Scan")
            }
        },
    ) { padding ->
        if (collections.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nessuna collezione. Creane una e inizia a scansionare.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(collections, key = { it.id }) { collection ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenCollection(collection.id) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(collection.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${collection.cardCount} carte",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            IconButton(onClick = { viewModel.delete(collection.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Elimina")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nuova collezione") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nome") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.create(newName)
                        newName = ""
                        showCreate = false
                    },
                ) { Text("Crea") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Annulla") }
            },
        )
    }
}
