package it.reperibilita.app.data.excel

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Le celle data possono arrivare in forme diverse a seconda della sorgente:
 *  - file locale letto come testo (getCellText): stringa formattata dall'utente in Excel
 *  - Graph Workbook API: numero seriale Excel (giorni dal 1899-12-30) se la cella e' formattata
 *    come data, oppure stringa se la cella e' testo libero
 * Questo helper prova i formati piu' comuni prima di arrendersi, cosi' il file Excel non deve
 * rispettare un formato rigido pixel-perfect.
 */
object ExcelDateUtils {

    private val CANDIDATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME
    )

    fun parseCellValue(value: Any?): LocalDateTime? {
        if (value == null) return null
        return when (value) {
            is LocalDateTime -> value
            is Number -> excelSerialToLocalDateTime(value.toDouble())
            is String -> parseString(value)
            else -> parseString(value.toString())
        }
    }

    private fun parseString(raw: String): LocalDateTime? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        trimmed.toDoubleOrNull()?.let { serial ->
            // Un numero seriale Excel plausibile (dopo il 1950 e prima del 2200 circa)
            if (serial in 18000.0..146000.0) return excelSerialToLocalDateTime(serial)
        }

        for (formatter in CANDIDATE_FORMATS) {
            runCatching { return LocalDateTime.parse(trimmed, formatter) }
        }
        // Prova anche solo data (assume 00:00)
        runCatching {
            return LocalDateTime.parse(trimmed + " 00:00", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }
        return null
    }

    /** Excel (sistema 1900): giorno 1 = 1 gennaio 1900, con il noto bug dell'anno bisestile 1900. */
    fun excelSerialToLocalDateTime(serial: Double): LocalDateTime {
        val epoch = LocalDateTime.of(1899, 12, 30, 0, 0)
        val wholeDays = serial.toLong()
        val fractionalDaySeconds = ((serial - wholeDays) * 86400.0).toLong()
        return epoch.plusDays(wholeDays).plusSeconds(fractionalDaySeconds)
    }
}
