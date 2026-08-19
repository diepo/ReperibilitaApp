package it.reperibilita.app.model

/** Esito dell'invio del comando di inoltro chiamata. */
sealed class ForwardingResult {
    data class Success(val verified: Boolean, val rawResponse: String?) : ForwardingResult()
    data class Failure(val reason: String, val throwable: Throwable? = null) : ForwardingResult()
}

enum class LogAction {
    SCHEDULE_CHECK,
    SHIFT_CHANGE_DETECTED,
    CALL_FORWARDING_SET,
    SMS_SENT,
    MAIL_SENT,
    EXCEL_WRITE_BACK,
    MANUAL_OVERRIDE,
    ERROR
}

enum class LogResult { OK, WARNING, ERROR }
