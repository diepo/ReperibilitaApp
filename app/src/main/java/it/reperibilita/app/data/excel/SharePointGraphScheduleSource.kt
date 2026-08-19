package it.reperibilita.app.data.excel

import it.reperibilita.app.data.graph.GraphSharePointClient
import it.reperibilita.app.model.AppConfig
import it.reperibilita.app.model.ScheduleSheetSchema
import it.reperibilita.app.model.ShiftEntry

/** Legge/scrive il calendario dal file Excel su SharePoint tramite le API Workbook di Graph. */
class SharePointGraphScheduleSource(
    private val graphSharePointClient: GraphSharePointClient,
    private val configProvider: () -> AppConfig
) : ScheduleSource {

    override suspend fun readAllShifts(): List<ShiftEntry> {
        val config = configProvider()
        val ref = graphSharePointClient.resolveDriveItem(config.sharePoint)
        val rows = graphSharePointClient.readUsedRange(ref, config.sharePoint.worksheetName)
        return mapRowsToShifts(rows)
    }

    override suspend fun writeBackStatus(rowIndex: Int, statusText: String) {
        val config = configProvider()
        val ref = graphSharePointClient.resolveDriveItem(config.sharePoint)
        val rows = graphSharePointClient.readUsedRange(ref, config.sharePoint.worksheetName)
        val target = rows.getOrNull(rowIndex)
            ?: throw ScheduleSourceException("Riga $rowIndex non trovata nel foglio SharePoint per il write-back")

        val updated = target.toMutableList()
        while (updated.size <= ScheduleSheetSchema.COL_STATO) updated.add(null)
        updated[ScheduleSheetSchema.COL_STATO] = statusText

        // Graph richiede indice di riga 1-based con intestazione inclusa (rowIndex qui e' gia'
        // relativo a "values", dove 0 = intestazione), quindi il range address usa rowIndex + 1.
        graphSharePointClient.updateRow(ref, config.sharePoint.worksheetName, rowIndex + 1, updated)
    }

    private fun mapRowsToShifts(rows: List<List<Any?>>): List<ShiftEntry> {
        if (rows.isEmpty()) return emptyList()
        return rows.drop(1).mapIndexedNotNull { idx, cols ->
            val rowIndex = idx + 1
            if (cols.all { it == null || it.toString().isBlank() }) return@mapIndexedNotNull null

            val start = ExcelDateUtils.parseCellValue(cols.getOrNull(ScheduleSheetSchema.COL_DATA_INIZIO))
            val end = ExcelDateUtils.parseCellValue(cols.getOrNull(ScheduleSheetSchema.COL_DATA_FINE))
            val name = cols.getOrNull(ScheduleSheetSchema.COL_NOME_PERSONA)?.toString()?.trim().orEmpty()
            val number = cols.getOrNull(ScheduleSheetSchema.COL_NUMERO_INOLTRO)?.toString()?.trim().orEmpty()

            if (start == null || end == null || name.isEmpty() || number.isEmpty()) {
                return@mapIndexedNotNull null
            }

            ShiftEntry(
                rowIndex = rowIndex,
                startDateTime = start,
                endDateTime = end,
                personName = name,
                forwardNumber = number,
                personEmail = cols.getOrNull(ScheduleSheetSchema.COL_EMAIL_PERSONA)?.toString()?.trim()?.ifBlank { null },
                notes = cols.getOrNull(ScheduleSheetSchema.COL_NOTE)?.toString()?.trim()?.ifBlank { null }
            )
        }
    }
}
