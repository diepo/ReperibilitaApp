package it.reperibilita.app.data.auth

/** Astrae il metodo con cui l'app ottiene un access token per Microsoft Graph. */
interface GraphAuthProvider {
    /** Restituisce un access token valido per gli scope richiesti, rinnovandolo se scaduto. */
    suspend fun getAccessToken(scopes: List<String>): String
}

class GraphAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
