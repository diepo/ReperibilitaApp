package it.reperibilita.app.data.mail

class MailSendException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface MailSender {
    suspend fun send(to: List<String>, subject: String, htmlBody: String)
}
