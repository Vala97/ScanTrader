package com.scantrader.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scantrader.app.BuildConfig
import com.scantrader.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Informazioni") },
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
        ) {
            Text(
                versionLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "ScanTrader è un’app non ufficiale per scansionare carte e creare liste CSV per CardTrader. " +
                    "Non è affiliata, approvata o sponsorizzata da Wizards of the Coast, The Pokémon Company, " +
                    "Nintendo, CardTrader o dai rispettivi titolari dei marchi. Magic: The Gathering, Pokémon " +
                    "e i nomi delle carte appartengono ai rispettivi proprietari e sono usati solo per identificazione.\n\n" +
                    "Cataloghi: Scryfall (MTG) e TCGdex (Pokémon in inglese, italiano, giapponese e cinese). " +
                    "Riconoscimento ibrido: OCR on-device (ML Kit), fingerprint dell’arte e, opzionale, " +
                    "pack neurale Milo (AGPL-3.0, HanClinto/CollectorVision) con indici embedding " +
                    "Scryfall (MTG) e/o TCGdex EN (Pokémon, import .stcat). " +
                    "Le foto restano sul telefono.",
            )
        }
    }
}

@Composable
private fun versionLabel(): String {
    val template = if (BuildConfig.DEBUG) R.string.about_version_debug else R.string.about_version
    return stringResource(template, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
}
