package it.reperibilita.app.data.excel

import it.reperibilita.app.model.ShiftEntry

class ScheduleSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sorgente del calendario di reperibilità: file locale oppure workbook su SharePoint via Graph. */
interface ScheduleSource {
    suspend fun readAllShifts(): List<ShiftEntry>

    /**
     * Scrive lo stato di un turno (es. "ATTIVATO 2026-08-11T08:00") nella colonna Stato della
     * riga corrispondente, se il write-back e' abilitato in configurazione. Facoltativo: non
     * tutte le implementazioni lo supportano in ogni condizione (es. permessi insufficienti).
     */
    suspend fun writeBackStatus(rowIndex: Int, statusText: String)
}
