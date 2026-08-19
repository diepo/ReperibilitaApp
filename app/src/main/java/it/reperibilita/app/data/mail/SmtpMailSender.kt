package it.reperibilita.app.data.mail

import it.reperibilita.app.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class SmtpMailSender(private val configProvider: () -> AppConfig) : MailSender {

    override suspend fun send(to: List<String>, subject: String, htmlBody: String) = withContext(Dispatchers.IO) {
        val smtp = configProvider().smtp
        if (smtp.host.isBlank()) throw MailSendException("Configurazione SMTP incompleta (host mancante)")

        val props = Properties().apply {
            put("mail.smtp.host", smtp.host)
            put("mail.smtp.port", smtp.port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", smtp.useStartTls.toString())
            if (!smtp.useStartTls) {
                put("mail.smtp.socketFactory.port", smtp.port.toString())
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            }
            // JavaMail non ha alcun timeout di default (connessione/lettura/scrittura restano
            // bloccanti ALL'INFINITO se non impostati esplicitamente qui): se il server SMTP e'
            // irraggiungibile o la connessione resta silenziosamente appesa (es. firewall che fa
            // drop invece di reset, host/porta sbagliati), Transport.send() sotto non ritornerebbe
            // mai. Dato che questa chiamata blocca (non e' una funzione suspend cooperativa), un
            // Job.cancel() del Worker che la contiene NON la interrompe: il thread resterebbe
            // occupato a tempo indeterminato anche dopo che WorkManager considera il lavoro
            // annullato - causa plausibile di controlli che risultano "sempre in corso" molto
            // oltre la durata attesa. 15s per fase e' ampiamente sufficiente per un invio SMTP
            // reale e forza comunque un'eccezione invece di un blocco permanente.
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
        }

        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(smtp.username, smtp.password)
        })

        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(smtp.fromAddress.ifBlank { smtp.username }))
                setRecipients(Message.RecipientType.TO, to.map { InternetAddress(it) }.toTypedArray())
                setSubject(subject, "UTF-8")
                setContent(htmlBody, "text/html; charset=UTF-8")
            }
            Transport.send(message)
        } catch (t: Throwable) {
            throw MailSendException("Invio mail via SMTP fallito: ${t.message}", t)
        }
    }
}
