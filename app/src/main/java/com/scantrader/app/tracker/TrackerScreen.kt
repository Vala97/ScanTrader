package com.scantrader.app.tracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val STATES = mapOf(
    "attesa" to "in attesa", "invio" to "in caricamento…", "verifica" to "controllo su Cardtrader…",
    "fatto" to "su Cardtrader", "lista" to "in lista d'attesa", "errore" to "da sistemare nel tracker",
)
private fun eur(v: Double) = String.format(Locale.ITALY, "%.2f €", v)
private fun copie(n: Int) = if (n == 1) "1 copia" else "$n copie"

/** DLS Tracker: collegamento, invio delle carte salvate e inventario Cardtrader (solo le mie carte). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(link: TrackerLink, onBack: () -> Unit) {
    val state by link.state.collectAsState()
    val scope = rememberCoroutineScope()
    var inv by remember { mutableStateOf<TrackerLink.Inventory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastLoad by remember { mutableStateOf(0L) }
    fun load() {
        if (!link.linked || loading) return
        lastLoad = System.currentTimeMillis()
        scope.launch {
            loading = true
            error = runCatching { inv = link.inventory() }.exceptionOrNull()?.message
            loading = false
        }
    }
    LaunchedEffect(state.linked) { load() }
    // conto alla rovescia delle carte in attesa; ricarica quando una dovrebbe essere partita
    LaunchedEffect(inv) {
        while (true) {
            delay(1000); now = System.currentTimeMillis()
            val waiting = inv?.queue?.any { it.state == "attesa" || it.state == "invio" || it.state == "verifica" } == true
            if ((waiting || state.pending > 0) && now - lastLoad > 15_000) load()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DLS Tracker") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") } },
                actions = { if (state.linked) IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, contentDescription = "Aggiorna") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (!state.linked) LinkForm(link, onLinked = { load() }) else LinkStatus(link, state)
            }
            if (state.linked) {
                if (loading && inv == null) item { Text("Leggo il tuo Cardtrader dal tracker…") }
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
                val queue = inv?.queue.orEmpty()
                val open = queue.filter { it.state != "fatto" && it.state != "lista" }
                if (open.isNotEmpty()) {
                    item { Text("In arrivo su Cardtrader (${open.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(open, key = { "q-" + it.uid }) { q ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${if (q.qty > 1) "${q.qty}× " else ""}${q.name} · ${q.exp.uppercase()} ${q.no}${if (q.foil) " · foil" else ""}", fontWeight = FontWeight.SemiBold)
                                val left = ((q.due - now) / 1000).coerceAtLeast(0)
                                Text(
                                    if (q.state == "attesa" && left > 0) "su Cardtrader tra ${left / 60}:${String.format(Locale.ROOT, "%02d", left % 60)}"
                                    else (STATES[q.state] ?: q.state) + if (q.err.isNotBlank()) " · ${q.err}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (q.state == "errore") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (q.state == "attesa" || q.state == "errore") TextButton(onClick = { scope.launch { runCatching { link.removeQueued(q.uid) }; load() } }) { Text("Togli") }
                                    if (q.state == "attesa" && left > 0) TextButton(onClick = { scope.launch { runCatching { link.uploadNow(q.uid) }; load() } }) { Text("Carica subito") }
                                }
                            }
                        }
                    }
                }
                inv?.let { iv ->
                    val copies = iv.items.sumOf { it.qty }
                    item {
                        Text("Il mio Cardtrader · ${copie(copies)} in ${iv.items.size} annunci", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Solo le tue carte. Quando una si vende sparisce anche da qui.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val byGame = iv.items.groupBy { it.game.ifBlank { "Altro" } }.toSortedMap()
                    for ((game, gItems) in byGame) {
                        val gKey = "g:$game"
                        item(key = gKey) {
                            Header("${if (gKey in collapsed) "▸" else "▾"} $game", copie(gItems.sumOf { it.qty }), big = true) { collapsed = collapsed.toggle(gKey) }
                        }
                        if (gKey in collapsed) continue
                        val byExp = gItems.groupBy { it.exp.ifBlank { it.expCode.uppercase() } }.toSortedMap()
                        for ((exp, eItems) in byExp) {
                            val eKey = "e:$game:$exp"
                            item(key = eKey) {
                                Header("${if (eKey in collapsed) "▸" else "▾"} $exp", "${copie(eItems.sumOf { it.qty })} · ${eur(eItems.sumOf { it.price * it.qty })}") { collapsed = collapsed.toggle(eKey) }
                            }
                            if (eKey in collapsed) continue
                            items(eItems.sortedWith(compareBy({ it.no.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it.no }, { it.name })), key = { "i-" + it.id }) { c ->
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, fontWeight = FontWeight.Medium)
                                        Text(listOf(c.no.takeIf { it.isNotBlank() }?.let { "N° $it" }, c.cond, c.lang.takeIf { it.isNotBlank() && it != "English" }, if (c.foil) "foil" else null).filterNotNull().joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("×${c.qty}", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(10.dp))
                                    Text(eur(c.price), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k

@Composable
private fun Header(title: String, right: String, big: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = if (big) 10.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = if (big) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkForm(link: TrackerLink, onLinked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(link.url) }
    var user by remember { mutableStateOf(link.user) }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Collega lo scanner al tuo utente del DLS Tracker", style = MaterialTheme.typography.titleMedium)
        Text("Le carte che salvi arrivano nel tuo registro e da lì su Cardtrader (dopo 10 minuti, con le tue Regole prezzi). Serve Tailscale acceso. La password resta cifrata sul telefono.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Indirizzo del tracker") }, singleLine = true,
            placeholder = { Text("https://…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Utente del tracker") }, singleLine = true)
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(enabled = !busy && url.isNotBlank() && user.isNotBlank() && pw.isNotEmpty(), onClick = {
            scope.launch {
                busy = true; msg = null
                msg = runCatching { link.link(user, pw, url); onLinked(); null }.getOrElse { it.message ?: "Collegamento non riuscito." }
                busy = false
            }
        }) { Text(if (busy) "Collego…" else "Collega") }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun LinkStatus(link: TrackerLink, s: TrackerLink.State) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Collegato come ${s.user}", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manda al tracker le carte salvate", modifier = Modifier.weight(1f))
                Switch(checked = s.sending, onCheckedChange = { link.sending = it })
            }
            Text(
                when {
                    !s.sending -> "Invio spento: le carte restano solo nello scanner."
                    s.pending > 0 -> "${s.pending} carte da mandare al tracker" + (s.lastError?.let { " · $it" } ?: "")
                    else -> "Tutto mandato al tracker."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.pending > 0 && s.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            TextButton(onClick = { link.unlink() }) { Text("Scollega") }
        }
    }
}
