package com.scantrader.app.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scantrader.app.BuildConfig
import com.scantrader.app.R
import com.scantrader.app.ui.theme.scanTraderAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Informazioni", fontWeight = FontWeight.SemiBold) },
                colors = scanTraderAppBarColors(),
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
                .padding(16.dp),
        ) {
            ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp)) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = "Icona di ScanTrader",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .align(Alignment.CenterHorizontally),
                    )
                    Text(
                        versionLabel(),
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
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
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun versionLabel(): String {
    val template = if (BuildConfig.DEBUG) R.string.about_version_debug else R.string.about_version
    return stringResource(template, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
}
