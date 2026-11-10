package com.scantrader.app.ui.home

/** Confirmation before a collection is deleted from the home list. */
object CollectionDeletePrompt {
    enum class Action { Confirm, Cancel, Dismiss }

    fun message(name: String, rows: Int, copies: Int): String {
        val lead = "Eliminare \"$name\"?"
        val body = if (rows <= 0) {
            "La collezione è vuota."
        } else {
            val cards = if (rows == 1) "1 carta" else "$rows carte"
            val copyLabel = if (copies == 1) "1 copia" else "$copies copie"
            "Contiene $cards ($copyLabel)."
        }
        return "$lead $body L'operazione non si può annullare."
    }

    /** Only «Elimina» deletes. «Annulla» and dismissing the dialog do not. */
    fun deletes(action: Action): Boolean = action == Action.Confirm
}
