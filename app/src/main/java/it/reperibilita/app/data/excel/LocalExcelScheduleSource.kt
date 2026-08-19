package it.reperibilita.app.data.excel

import android.content.Context
import android.net.Uri
import it.reperibilita.app.model.AppConfig
import it.reperibilita.app.model.ScheduleSheetSchema
import it.reperibilita.app.model.ShiftEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook

/**
 * Legge/scrive il calendario da un file .xlsx locale selezionato dall'utente tramite Storage
 * Access Framework (content://). fastexcel non supporta l'editing in-place di un xlsx esistente:
 * il write-back rilegge tutte le righe, modifica quella interessata e riscrive l'intero file.
 */
class LocalExcelScheduleSource(
    private val context: Context,
    private val configProvider: () -> AppConfig
) : ScheduleSource {

    private fun requireUri(): Uri {
        val raw = configProvider().localFileUri
            ?: throw ScheduleSourceException("Nessun file Excel locale selezionato nelle Impostazioni")
        return Uri.parse(raw)
    }

    override suspend fun readAllShifts(): List<ShiftEntry> = withContext(Dispatchers.IO) {
        mapRowsToShifts(readRawRows())
    }

    override suspend fun writeBackStatus(rowIndex: Int, statusText: String) = withContext(Dispatchers.IO) {
        val rows = readRawRows().toMutableList()
        if (rowIndex !in rows.indices) {
            throw ScheduleSourceException("Riga $rowIndex non trovata nel file locale per il write-back")
        }
        val row = rows[rowIndex].toMutableList()
        while (row.size <= ScheduleSheetSchema.COL_STATO) row.add("")
        row[ScheduleSheetSchema.COL_STATO] = statusText
        rows[rowIndex] = row

        val uri = requireUri()
        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "wt")?.use { output ->
            Workbook(output, "ReperibilitaApp", "1.0").use { wb ->
                val sheet = wb.newWorksheet(ScheduleSheetSchema.SHEET_NAME)
                rows.forEachIndexed { r, cols ->
                    cols.forEachIndexed { c, value -> sheet.value(r, c, value) }
                }
                sheet.finish()
            }
        } ?: throw ScheduleSourceException("Impossibile aprire il file locale in scrittura (permessi persistenti mancanti?)")
    }

    private fun readRawRows(): List<List<String>> {
        val uri = requireUri()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ScheduleSourceException("Impossibile aprire il file Excel locale: $uri")

        input.use { stream ->
            ReadableWorkbook(stream).use { workbook ->
                val sheet = workbook.findSheet(ScheduleSheetSchema.SHEET_NAME)
                    .orElseGet { workbook.firstSheet }
                    ?: throw ScheduleSourceException("Nessun foglio trovato nel file Excel locale")

                return sheet.openStream().use { rowStream ->
                    // .collect(Collectors.toList()) invece di .toList(): quest'ultimo esiste solo
                    // da Java 16 e dipende dal livello di core library desugaring configurato.
                    rowStream.map { row ->
                        (0..ScheduleSheetSchema.COL_STATO).map { col ->
                            runCatching { row.getCellText(col) }.getOrDefault("")
                        }
                    }.collect(java.util.stream.Collectors.toList())
                }
            }
        }
    }

    private fun mapRowsToShifts(rows: List<List<String>>): List<ShiftEntry> {
        if (rows.isEmpty()) return emptyList()
        // riga 0 = intestazione
        return rows.drop(1).mapIndexedNotNull { idx, cols ->
            val rowIndex = idx + 1
            if (cols.all { it.isBlank() }) return@mapIndexedNotNull null

            val start = ExcelDateUtils.parseCellValue(cols.getOrNull(ScheduleSheetSchema.COL_DATA_INIZIO))
            val end = ExcelDateUtils.parseCellValue(cols.getOrNull(ScheduleSheetSchema.COL_DATA_FINE))
            val name = cols.getOrNull(ScheduleSheetSchema.COL_NOME_PERSONA)?.trim().orEmpty()
            val number = cols.getOrNull(ScheduleSheetSchema.COL_NUMERO_INOLTRO)?.trim().orEmpty()

            if (start == null || end == null || name.isEmpty() || number.isEmpty()) {
                return@mapIndexedNotNull null
            }

            ShiftEntry(
                rowIndex = rowIndex,
                startDateTime = start,
                endDateTime = end,
                personName = name,
                forwardNumber = number,
                personEmail = cols.getOrNull(ScheduleSheetSchema.COL_EMAIL_PERSONA)?.trim()?.ifBlank { null },
                notes = cols.getOrNull(ScheduleSheetSchema.COL_NOTE)?.trim()?.ifBlank { null }
            )
        }
    }
}
