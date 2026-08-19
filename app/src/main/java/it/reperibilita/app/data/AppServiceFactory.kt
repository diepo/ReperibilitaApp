package it.reperibilita.app.data

import android.content.Context
import it.reperibilita.app.data.auth.ClientCredentialsAuthProvider
import it.reperibilita.app.data.auth.GraphAuthProvider
import it.reperibilita.app.data.auth.MsalDelegatedAuthProvider
import it.reperibilita.app.data.config.ConfigRepository
import it.reperibilita.app.data.excel.LocalExcelScheduleSource
import it.reperibilita.app.data.excel.ScheduleSource
import it.reperibilita.app.data.excel.SharePointGraphScheduleSource
import it.reperibilita.app.data.graph.GraphHttpClient
import it.reperibilita.app.data.graph.GraphMailClient
import it.reperibilita.app.data.graph.GraphSharePointClient
import it.reperibilita.app.data.mail.GraphMailSender
import it.reperibilita.app.data.mail.MailSender
import it.reperibilita.app.data.mail.SmtpMailSender
import it.reperibilita.app.model.GraphAuthMode
import it.reperibilita.app.model.MailSendMode
import it.reperibilita.app.model.ScheduleSourceMode

/**
 * Composition root: costruisce le implementazioni concrete (sorgente calendario, auth Graph,
 * mail sender) in base alla configurazione corrente, cosi' che l'utente possa cambiare
 * SharePoint/locale, client-credentials/delegato, SMTP/Graph dalla GUI senza riavviare l'app.
 */
class AppServiceFactory(
    private val context: Context,
    private val configRepository: ConfigRepository
) {
    @Volatile private var msalProvider: MsalDelegatedAuthProvider? = null
    @Volatile private var msalProviderKey: String? = null

    /**
     * L'istanza MSAL va riusata (non ricreata a ogni chiamata): mantiene lo stato dell'account
     * loggato, necessario sia per il login interattivo dalle Impostazioni sia per il rinnovo
     * silenzioso del token dal worker in background.
     */
    fun getMsalProvider(): MsalDelegatedAuthProvider {
        val entra = configRepository.current().entraApp
        val key = "${entra.clientId}|${entra.redirectUri}|${entra.tenantId}"
        val existing = msalProvider
        if (existing != null && msalProviderKey == key) return existing

        return MsalDelegatedAuthProvider(context, entra.clientId, entra.redirectUri, entra.tenantId).also {
            msalProvider = it
            msalProviderKey = key
        }
    }

    fun buildAuthProvider(): GraphAuthProvider {
        val config = configRepository.current()
        return when (config.graphAuthMode) {
            GraphAuthMode.CLIENT_CREDENTIALS -> ClientCredentialsAuthProvider(
                tenantId = config.entraApp.tenantId,
                clientId = config.entraApp.clientId,
                clientSecret = config.entraApp.clientSecret
            )
            GraphAuthMode.DELEGATED_INTERACTIVE -> getMsalProvider()
        }
    }

    fun buildGraphHttpClient(): GraphHttpClient = GraphHttpClient(buildAuthProvider())

    fun buildScheduleSource(): ScheduleSource {
        val config = configRepository.current()
        return when (config.scheduleSourceMode) {
            ScheduleSourceMode.LOCAL_FILE ->
                LocalExcelScheduleSource(context) { configRepository.current() }
            ScheduleSourceMode.SHAREPOINT ->
                SharePointGraphScheduleSource(GraphSharePointClient(buildGraphHttpClient())) { configRepository.current() }
        }
    }

    fun buildMailSender(): MailSender {
        val config = configRepository.current()
        return when (config.mailSendMode) {
            MailSendMode.SMTP -> SmtpMailSender { configRepository.current() }
            MailSendMode.GRAPH_API -> GraphMailSender(GraphMailClient(buildGraphHttpClient())) { configRepository.current() }
            MailSendMode.DISABLED -> object : MailSender {
                override suspend fun send(to: List<String>, subject: String, htmlBody: String) = Unit
            }
        }
    }
}
