package com.scantrader.app.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object CollectionBackupIO {
    fun share(context: Context, backup: CollectionBackup) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, CollectionBackup.FILE_NAME)
        file.writeText(CollectionBackup.toJson(backup), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ScanTrader backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Esporta backup collezioni"))
    }

    fun read(context: Context, uri: Uri): CollectionBackup {
        val json = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("Impossibile leggere il file.")
        return CollectionBackup.parse(json)
    }
}
