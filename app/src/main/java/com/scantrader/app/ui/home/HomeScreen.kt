package com.scantrader.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scantrader.app.data.db.CollectionWithCount
import com.scantrader.app.data.repo.RenameCollection
import com.scantrader.app.ui.HomeViewModel
import com.scantrader.app.ui.theme.scanTraderAppBarColors
import com.scantrader.app.ui.update.AppUpdateDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenCollection: (Long) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onShowOnboarding: () -> Unit,
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()
    val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<CollectionWithCount?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showOnboarding) {
        if (viewModel.consumeOnboardingLaunch()) onShowOnboarding()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("ScanTrader", fontWeight = FontWeight.SemiBold) },
                colors = scanTraderAppBarColors(),
                actions = {
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, contentDescription = "Info") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Impostazioni") }
                    IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = "Nuova collezione") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                text = { Text("Scansiona") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
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
                Text("Nessuna collezione", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Creane una e inizia a scansionare.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenCollection(collection.id) },
                                    onLongClick = {
                                        renameError = null
                                        renaming = collection
                                    },
                                )
                                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(collection.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${collection.cardCount} carte",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                renameError = null
                                renaming = collection
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rinomina")
                            }
                            IconButton(onClick = { viewModel.delete(collection.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Elimina",
                                    tint = MaterialTheme.colorScheme.error,
                                )
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
            shape = RoundedCornerShape(28.dp),
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

    renaming?.let { collection ->
        RenameCollectionDialog(
            initialName = collection.name,
            error = renameError,
            onDismiss = {
                renaming = null
                renameError = null
            },
            onConfirm = { name ->
                viewModel.rename(collection.id, name) { result ->
                    when (result) {
                        RenameCollection.Saved -> {
                            renaming = null
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

    AppUpdateDialog(
        state = appUpdate,
        onLater = viewModel::dismissAppUpdate,
        onInstall = viewModel::installAppUpdate,
        onOpenSettings = viewModel::openAppUpdateSettings,
    )
}

@Composable
fun RenameCollectionDialog(
    initialName: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Rinomina collezione") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    isError = error != null,
                )
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}
