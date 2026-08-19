package it.reperibilita.app.telecom

import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stato condiviso tra ReperibilitaInCallService (che riceve gli eventi di chiamata dal sistema)
 * e chi ne ha bisogno altrove nell'app: InCallActivity (UI per chiamate normali/di emergenza) e
 * DefaultDialerUssdStrategy (che aspetta la disconnessione di una chiamata USSD auto-piazzata
 * per leggerne l'esito).
 */
object CallHolder {
    val activeCall = MutableStateFlow<Call?>(null)
    val callState = MutableStateFlow<Int?>(null)
    /** Testo della causa di disconnessione dell'ULTIMA chiamata terminata (spesso contiene il messaggio di rete per i codici USSD). */
    val lastDisconnectCause = MutableStateFlow<String?>(null)
}
