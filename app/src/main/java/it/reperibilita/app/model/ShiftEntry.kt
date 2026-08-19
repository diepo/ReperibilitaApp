package it.reperibilita.app.model

import java.time.LocalDateTime

/**
 * Una riga del calendario di reperibilità (foglio Excel, locale o su SharePoint).
 *
 * Colonne attese nel file Excel (in quest'ordine, prima riga = intestazione):
 * DataInizio | DataOra Fine | Nome Persona | Numero Inoltro | Email Persona | Note
 */
data class ShiftEntry(
    val rowIndex: Int,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime,
    val personName: String,
    val forwardNumber: String,
    val personEmail: String?,
    val notes: String?
) {
    fun isActiveAt(instant: LocalDateTime): Boolean =
        !instant.isBefore(startDateTime) && instant.isBefore(endDateTime)
}

/** Schema colonne del foglio Excel: usato sia dal parser locale sia dalle chiamate Graph Workbook. */
object ScheduleSheetSchema {
    const val SHEET_NAME = "Reperibilita"
    const val COL_DATA_INIZIO = 0
    const val COL_DATA_FINE = 1
    const val COL_NOME_PERSONA = 2
    const val COL_NUMERO_INOLTRO = 3
    const val COL_EMAIL_PERSONA = 4
    const val COL_NOTE = 5
    const val COL_STATO = 6 // colonna facoltativa scritta dall'app dopo l'attivazione del turno

    val HEADER = listOf(
        "DataInizio", "DataFine", "NomePersona", "NumeroInoltro", "EmailPersona", "Note", "Stato"
    )
}
