package it.reperibilita.app.data.graph

import org.json.JSONArray
import org.json.JSONObject

/**
 * Invio mail via Microsoft Graph (POST /users/{upn}/sendMail per il flusso app-only client
 * credentials, POST /me/sendMail per il flusso delegato interattivo).
 */
class GraphMailClient(private val http: GraphHttpClient) {

    suspend fun sendMail(
        senderUpnOrNullForMe: String?,
        toRecipients: List<String>,
        subject: String,
        htmlBody: String
    ) {
        if (toRecipients.isEmpty()) return

        val recipients = JSONArray()
        toRecipients.forEach { address ->
            recipients.put(JSONObject().put("emailAddress", JSONObject().put("address", address)))
        }

        val message = JSONObject()
            .put("subject", subject)
            .put("body", JSONObject().put("contentType", "HTML").put("content", htmlBody))
            .put("toRecipients", recipients)

        val body = JSONObject()
            .put("message", message)
            .put("saveToSentItems", true)

        val path = if (senderUpnOrNullForMe.isNullOrBlank()) {
            "/me/sendMail"
        } else {
            "/users/${GraphHttpClient.encodePathSegment(senderUpnOrNullForMe)}/sendMail"
        }

        http.postNoContent(path, body)
    }
}
