package it.reperibilita.app.data.log

import android.content.Context
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Punto unico di logging dell'app: ogni azione rilevante (check turno, inoltro chiamata,
 * SMS, mail, scrittura su Excel, override manuale, errori) passa da qui. Usato sia per la
 * schermata "Log" nella GUI sia per comporre le mail di riepilogo/errore.
 */
class LogRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).logDao()

    suspend fun log(
        action: LogAction,
        result: LogResult,
        message: String,
        detail: String? = null,
        personName: String? = null
    ): Long = dao.insert(
        LogEntry(
            timestampEpochMillis = Instant.now().toEpochMilli(),
            action = action,
            result = result,
            message = message,
            detail = detail,
            personName = personName
        )
    )

    fun observeRecent(limit: Int = 500): Flow<List<LogEntry>> = dao.observeRecent(limit)

    suspend fun entriesBetween(fromMillis: Long, toMillis: Long): List<LogEntry> =
        dao.findBetween(fromMillis, toMillis)

    suspend fun purgeOlderThanDays(days: Int) {
        val threshold = Instant.now().toEpochMilli() - days * 24L * 60 * 60 * 1000
        dao.deleteOlderThan(threshold)
    }
}
