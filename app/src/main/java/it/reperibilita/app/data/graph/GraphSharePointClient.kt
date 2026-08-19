package it.reperibilita.app.data.graph

import android.util.Base64
import it.reperibilita.app.model.SharePointConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class DriveItemRef(val driveId: String, val itemId: String)

/**
 * Risolve il file Excel su SharePoint con due metodi alternativi, e legge/scrive l'intervallo
 * usato del foglio "Reperibilita" tramite le API Workbook di Microsoft Graph.
 *
 * Metodo A - percorso diretto (siteUrl + driveItemPath): consigliato con autenticazione
 * CLIENT_CREDENTIALS (app-only). Accede al file tramite il suo percorso reale nella libreria
 * documenti, rispettando i permessi Application (Sites.ReadWrite.All) sull'intero tenant.
 *
 * Metodo B - link di condivisione (sharingUrl): comodo da ottenere (Condividi -> Copia
 * collegamento), ma funziona in modo affidabile solo con DELEGATED_INTERACTIVE. Con un token
 * app-only, un link condiviso "con persone specifiche" viene spesso rifiutato da Graph con
 * accesso negato, perche' l'identita' dell'app non compare nell'elenco delle persone autorizzate
 * su quel link - indipendentemente dai permessi Application concessi sul tenant.
 *
 * Riferimenti Graph:
 *  - GET /sites/{hostname}:{server-relative-path}
 *  - GET /sites/{site-id}/drive
 *  - GET /drives/{drive-id}/root:/{item-path}
 *  - GET /shares/{encoded-sharing-url}/driveItem
 *  - GET /drives/{drive-id}/items/{item-id}/workbook/worksheets('{name}')/usedRange(valuesOnly=true)
 *  - PATCH /drives/{drive-id}/items/{item-id}/workbook/worksheets('{name}')/range(address='A{r}:G{r}')
 */
class GraphSharePointClient(private val http: GraphHttpClient) {

    private var cachedRef: DriveItemRef? = null

    suspend fun resolveDriveItem(config: SharePointConfig): DriveItemRef {
        cachedRef?.let { return it }

        val ref = when {
            config.siteUrl.isNotBlank() && config.driveItemPath.isNotBlank() -> resolveViaSiteAndPath(config)
            config.sharingUrl.isNotBlank() -> resolveViaSharingUrl(config)
            else -> throw ScheduleSourceGraphException(
                "Configurare nelle Impostazioni o (URL sito + percorso file) o (link di condivisione)"
            )
        }
        cachedRef = ref
        return ref
    }

    private suspend fun resolveViaSiteAndPath(config: SharePointConfig): DriveItemRef {
        val siteUrl = URL(config.siteUrl)
        val hostname = siteUrl.host
        val serverRelativePath = siteUrl.path.trimEnd('/') // es. /sites/NomeSito, vuoto per il sito radice del tenant

        // GET /sites/{hostname}:{path} risolve un sito in un sotto-percorso (/sites/NomeSito);
        // per il sito radice del tenant (es. https://contoso.sharepoint.com senza suffisso) il
        // formato con ":" e percorso vuoto non e' valido e Graph risponde con un 404 "does not
        // represent a site" - va invece chiamato GET /sites/{hostname} senza ":".
        val siteJson = if (serverRelativePath.isBlank()) {
            http.get("/sites/$hostname")
        } else {
            http.get("/sites/$hostname:$serverRelativePath")
        }
        val siteId = siteJson.getString("id")

        val driveJson = http.get("/sites/$siteId/drive")
        val driveId = driveJson.getString("id")

        val itemId = resolveItemInDrive(driveId, config.driveItemPath)
        return DriveItemRef(driveId, itemId)
    }

    /**
     * Risolve un file dentro la libreria documenti predefinita del sito (drive.root e' gia'
     * relativo a quella libreria). Errore utente frequente: includere per errore il nome della
     * libreria stessa nel percorso (es. "/Shared Documents/..." o "/Documenti condivisi/...").
     * Se il percorso cosi' com'e' da' 404, si ritenta automaticamente togliendo il primo
     * segmento prima di arrendersi, invece di richiedere che l'utente lo scriva esattamente giusto.
     */
    private suspend fun resolveItemInDrive(driveId: String, rawPath: String): String {
        val cleanPath = rawPath.trim('/')
        if (cleanPath.isEmpty()) {
            throw ScheduleSourceGraphException("Percorso file vuoto nelle Impostazioni SharePoint")
        }

        suspend fun tryPath(path: String): JSONObject {
            val encoded = path.split("/").joinToString("/") { GraphHttpClient.encodePathSegment(it) }
            return http.get("/drives/$driveId/root:/$encoded")
        }

        return try {
            tryPath(cleanPath).getString("id")
        } catch (firstError: GraphApiException) {
            if (firstError.httpCode == 404 && cleanPath.contains("/")) {
                val withoutFirstSegment = cleanPath.substringAfter("/")
                try {
                    tryPath(withoutFirstSegment).getString("id")
                } catch (secondError: Throwable) {
                    throw ScheduleSourceGraphException(
                        "File non trovato ne' in \"/$cleanPath\" ne' in \"/$withoutFirstSegment\" " +
                            "(tentativo automatico senza il probabile nome della libreria documenti). " +
                            "Verifica il percorso nelle Impostazioni. Dettaglio: ${secondError.message}"
                    )
                }
            } else {
                throw firstError
            }
        }
    }

    private suspend fun resolveViaSharingUrl(config: SharePointConfig): DriveItemRef {
        val encoded = encodeSharingUrl(config.sharingUrl)
        val itemJson = http.get("/shares/$encoded/driveItem")
        val itemId = itemJson.getString("id")
        val driveId = itemJson.getJSONObject("parentReference").getString("driveId")
        return DriveItemRef(driveId, itemId)
    }

    /** Righe del range usato, header incluso (riga 0 = intestazione). Ogni cella e' String/Double/Boolean/null. */
    suspend fun readUsedRange(ref: DriveItemRef, worksheetName: String): List<List<Any?>> {
        val encodedSheet = GraphHttpClient.encodePathSegment(worksheetName)
        val json = http.get(
            "/drives/${ref.driveId}/items/${ref.itemId}/workbook/worksheets('$encodedSheet')/usedRange(valuesOnly=true)"
        )
        val values = json.getJSONArray("values")
        return (0 until values.length()).map { rowIdx ->
            val row = values.getJSONArray(rowIdx)
            (0 until row.length()).map { colIdx -> jsonCellToKotlin(row, colIdx) }
        }
    }

    /**
     * Scrive una riga (1-based, header = riga 1) del range A:G. Usata per il write-back
     * dello stato dopo un cambio turno (requisito: aggiornare il file sorgente).
     */
    suspend fun updateRow(ref: DriveItemRef, worksheetName: String, rowNumber1Based: Int, values: List<Any?>) {
        val encodedSheet = GraphHttpClient.encodePathSegment(worksheetName)
        val lastCol = ('A' + (values.size - 1))
        val address = "A$rowNumber1Based:$lastCol$rowNumber1Based"
        val body = JSONObject().put("values", JSONArray().put(JSONArray(values)))
        http.patch(
            "/drives/${ref.driveId}/items/${ref.itemId}/workbook/worksheets('$encodedSheet')/range(address='$address')",
            body
        )
    }

    private fun jsonCellToKotlin(row: JSONArray, colIdx: Int): Any? {
        if (row.isNull(colIdx)) return null
        val raw = row.get(colIdx)
        return when (raw) {
            is Boolean, is String, is Double, is Int, is Long -> raw
            else -> raw.toString()
        }
    }

    companion object {
        /**
         * Algoritmo Microsoft standard per trasformare un URL di condivisione nel formato
         * "sharing token" richiesto da /shares/{id}: base64 (URL-safe, senza padding) dei byte
         * UTF-8 dell'URL, prefissato da "u!".
         * https://learn.microsoft.com/en-us/graph/api/shares-get
         */
        fun encodeSharingUrl(url: String): String {
            val base64 = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.NO_PADDING)
            val urlSafe = base64.replace('/', '_').replace('+', '-')
            return "u!$urlSafe"
        }
    }
}

class ScheduleSourceGraphException(message: String) : Exception(message)
