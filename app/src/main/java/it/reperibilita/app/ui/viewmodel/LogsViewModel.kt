package it.reperibilita.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.reperibilita.app.App
import it.reperibilita.app.data.log.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LogsViewModel(private val app: App) : ViewModel() {
    val logs: StateFlow<List<LogEntry>> = app.logRepository.observeRecent(500)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage

    fun clearMessage() { _exportMessage.value = null }

    /** Formato testo semplice, una riga per voce, per copia/esportazione. */
    fun formatAsText(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return logs.value.joinToString("\n") { entry ->
            val ts = Instant.ofEpochMilli(entry.timestampEpochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
            buildString {
                append(ts.format(formatter))
                append(" [").append(entry.result).append("] ")
                append(entry.action)
                append(": ").append(entry.message)
                entry.personName?.let { append(" (persona: $it)") }
                entry.detail?.let { append("\n    dettaglio: ").append(it.replace("\n", "\n    ")) }
            }
        }
    }

    fun exportToFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = formatAsText()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Impossibile aprire il file in scrittura")
                }.isSuccess
            }
            _exportMessage.value = if (ok) "Log esportati" else "Esportazione log fallita"
        }
    }
}
