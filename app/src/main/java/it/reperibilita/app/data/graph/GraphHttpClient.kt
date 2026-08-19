package it.reperibilita.app.data.graph

import android.net.Uri
import it.reperibilita.app.data.auth.GraphAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GraphApiException(val httpCode: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Wrapper HTTP minimale su Microsoft Graph. Si usa org.json invece di un modello tipizzato per
 * i payload perche' le risposte Workbook (celle Excel) hanno tipi di valore eterogenei
 * (stringa/numero/booleano/null nella stessa colonna a seconda di come e' compilato il file).
 */
class GraphHttpClient(
    private val authProvider: GraphAuthProvider,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val defaultScopes = listOf("https://graph.microsoft.com/.default")

    suspend fun get(path: String): JSONObject = request("GET", path, null)

    suspend fun patch(path: String, body: JSONObject): JSONObject = request("PATCH", path, body)

    suspend fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    /** POST che si aspetta 202/204 senza body (es. sendMail): restituisce oggetto vuoto. */
    suspend fun postNoContent(path: String, body: JSONObject) {
        requestRaw("POST", path, body)
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val responseBody = requestRaw(method, path, body)
        if (responseBody.isBlank()) return JSONObject()
        return JSONObject(responseBody)
    }

    private suspend fun requestRaw(method: String, path: String, body: JSONObject?): String =
        withContext(Dispatchers.IO) {
            val token = authProvider.getAccessToken(defaultScopes)
            val url = if (path.startsWith("http")) path else "$GRAPH_BASE$path"

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")

            when (method) {
                "GET" -> requestBuilder.get()
                "PATCH" -> requestBuilder.patch((body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE))
                "POST" -> requestBuilder.post((body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE))
                else -> error("Metodo HTTP non supportato: $method")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GraphApiException(response.code, "Graph $method $url -> ${response.code}: $text")
                }
                text
            }
        }

    companion object {
        /** Incapsula un segmento di path in modo sicuro per l'URL (spazi, accenti, ecc.). */
        fun encodePathSegment(segment: String): String = Uri.encode(segment)
    }
}
