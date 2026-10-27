package com.scantrader.app.update

object UpdateMessages {
    const val NEEDS_PERMISSION =
        "Per installare l'aggiornamento consenti le app di questa origine nelle impostazioni del telefono."
    const val CHECKSUM = "File danneggiato (checksum). L'aggiornamento non è stato installato."
    const val UP_TO_DATE = "Sei già aggiornato."
    const val OFFLINE = "Connessione assente o elenco non raggiungibile."
    const val INSTALL_FAILED = "Installazione non riuscita."

    fun title(versionName: String) = "Nuova versione $versionName disponibile"
}
