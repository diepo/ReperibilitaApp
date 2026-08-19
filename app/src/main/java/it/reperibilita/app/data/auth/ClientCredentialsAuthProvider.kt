package it.reperibilita.app.data.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class TokenResponse(
    val access_token: String,
    val expires_in: Long,
    val token_type: String
)

/**
 * Flusso OAuth2 "client credentials" (app-only, senza login utente): usato per l'automazione
 * headless in background (worker periodico). Richiede permessi Application (non Delegated) in
 * Entra: Mail.Send, Sites.ReadWrite.All o Files.ReadWrite.All a seconda della configurazione.
 */
class ClientCredentialsAuthProvider(
    private val tenantId: String,
    private val clientId: String,
    private val clientSecret: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) : GraphAuthProvider {

    private data class CachedToken(val value: String, val expiresAtEpochSeconds: Long)

    private val cache = ConcurrentHashMap<String, CachedToken>()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tokenAdapter = moshi.adapter(TokenResponse::class.java)

    override suspend fun getAccessToken(scopes: List<String>): String = withContext(Dispatchers.IO) {
        // Per client-credentials lo scope e' sempre "<resource>/.default": i permessi effettivi
        // sono quelli configurati sulla App Registration, non quelli passati qui.
        val cacheKey = "client_credentials"
        val now = Instant.now().epochSecond
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtEpochSeconds - 60 > now) return@withContext cached.value
        }

        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("scope", "https://graph.microsoft.com/.default")
            .build()

        val request = Request.Builder()
            .url("https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GraphAuthException(
                    "Token endpoint Entra ha risposto ${response.code}: ${response.body?.string()}"
                )
            }
            val parsed = tokenAdapter.fromJson(response.body?.string().orEmpty())
                ?: throw GraphAuthException("Risposta token Entra non decodificabile")
            cache[cacheKey] = CachedToken(parsed.access_token, now + parsed.expires_in)
            parsed.access_token
        }
    }
}
