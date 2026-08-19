package it.reperibilita.app.data.mail

import it.reperibilita.app.data.graph.GraphMailClient
import it.reperibilita.app.model.AppConfig
import it.reperibilita.app.model.GraphAuthMode

class GraphMailSender(
    private val graphMailClient: GraphMailClient,
    private val configProvider: () -> AppConfig
) : MailSender {

    override suspend fun send(to: List<String>, subject: String, htmlBody: String) {
        val config = configProvider()
        // In CLIENT_CREDENTIALS il mittente deve essere una casella specifica (graphSenderUpn);
        // in DELEGATED_INTERACTIVE si puo' lasciare vuoto per usare /me (l'utente loggato).
        val sender = when (config.graphAuthMode) {
            GraphAuthMode.CLIENT_CREDENTIALS -> config.entraApp.graphSenderUpn.ifBlank {
                throw MailSendException("Modalita' client-credentials: configurare 'Casella mittente (UPN)' nelle Impostazioni")
            }
            GraphAuthMode.DELEGATED_INTERACTIVE -> config.entraApp.graphSenderUpn.ifBlank { null }
        }
        runCatching {
            graphMailClient.sendMail(sender, to, subject, htmlBody)
        }.onFailure { throw MailSendException("Invio mail via Graph fallito: ${it.message}", it) }
    }
}
