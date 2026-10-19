package com.scantrader.app.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.scantrader.app.data.db.CardEntryEntity
import java.io.File

object CsvShare {
    fun share(context: Context, collectionName: String, entries: List<CardEntryEntity>) {
        val games = entries.map { it.game }.distinct()
        if (games.isEmpty()) return
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val uris = games.map { game ->
            val file = File(dir, CardTraderCsv.filename(collectionName, game))
            file.writeText(CardTraderCsv.build(entries, game), Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_SUBJECT, "ScanTrader ${collectionName}")
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_SUBJECT, "ScanTrader ${collectionName}")
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Esporta CSV CardTrader"))
    }
}
