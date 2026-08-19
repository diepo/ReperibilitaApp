package it.reperibilita.app.data.log

import androidx.room.Entity
import androidx.room.PrimaryKey
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMillis: Long,
    val action: LogAction,
    val result: LogResult,
    val message: String,
    /** Dettaglio tecnico opzionale (stack trace, risposta USSD grezza, corpo risposta HTTP, ecc.) */
    val detail: String? = null,
    /** Persona coinvolta nell'azione, se applicabile. */
    val personName: String? = null
)
