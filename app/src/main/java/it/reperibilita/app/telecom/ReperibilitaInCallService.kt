package it.reperibilita.app.telecom

import android.os.Bundle
import android.telecom.Call
import android.telecom.InCallService
import it.reperibilita.app.App
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * InCallService minimale: obbligatorio per essere idonei al ruolo di dialer predefinito
 * (RoleManager.ROLE_DIALER). Da quando l'app diventa il telefono predefinito del device, questo
 * servizio riceve OGNI chiamata (in entrata, in uscita, di emergenza) - il collegamento radio
 * effettivo resta gestito dal ConnectionService di sistema (Telephony), non da questa app: qui ci
 * limitiamo a mostrare una UI essenziale e a esporre lo stato per l'automazione USSD.
 *
 * NOTA DIAGNOSTICA: la risposta testuale di un comando USSD (es. "Servizio abilitato...") non e'
 * detto che arrivi tramite Call.Details.disconnectCause - potrebbe essere consegnata da Android
 * tramite onConnectionEvent o negli extra di onDetailsChanged, a seconda dello stack telefonico
 * del device. Per questo logghiamo TUTTO cio' che riceviamo (loggato anche su LogRepository,
 * esportabile dalla tab Log) invece di leggere solo disconnectCause: la prossima sessione di
 * test dira' quale canale porta davvero il messaggio su questo device.
 */
class ReperibilitaInCallService : InCallService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallHolder.activeCall.value = call
        CallHolder.callState.value = call.details.state
        logDiagnostic("onCallAdded numero=${call.details.handle?.schemeSpecificPart} stato=${call.details.state}")

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                CallHolder.callState.value = state
                if (state == Call.STATE_DISCONNECTED) {
                    val cause = call.details.disconnectCause
                    val text = buildString {
                        append(cause?.code ?: -1)
                        append(": ")
                        append(cause?.label ?: "")
                        append(" / ")
                        append(cause?.description ?: "")
                        append(" / reason=")
                        append(cause?.reason ?: "")
                    }
                    CallHolder.lastDisconnectCause.value = text
                    logDiagnostic("onStateChanged DISCONNECTED disconnectCause=[$text]")
                } else {
                    logDiagnostic("onStateChanged stato=$state")
                }
            }

            override fun onConnectionEvent(call: Call, event: String, extras: Bundle?) {
                logDiagnostic("onConnectionEvent event=$event extras=${dumpBundle(extras)}")
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                val extras = details.extras
                if (extras != null && !extras.isEmpty) {
                    logDiagnostic("onDetailsChanged extras=${dumpBundle(extras)}")
                }
            }
        })

        // Le chiamate USSD auto-piazzate dall'automazione (DefaultDialerUssdStrategy) si
        // risolvono da sole in pochi secondi: non ha senso mostrare una UI a tutto schermo su
        // un device non presidiato per un'operazione che dura pochi secondi.
        if (!isUssdCall(call)) {
            InCallActivity.launch(applicationContext)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (CallHolder.activeCall.value == call) {
            CallHolder.activeCall.value = null
            CallHolder.callState.value = null
        }
        logDiagnostic("onCallRemoved")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun isUssdCall(call: Call): Boolean {
        val number = call.details.handle?.schemeSpecificPart ?: return false
        return number.contains('*') || number.contains('#')
    }

    private fun dumpBundle(bundle: Bundle?): String {
        if (bundle == null || bundle.isEmpty) return "(vuoto)"
        // Bundle.get(String) è deprecato ma qui serve proprio la lettura generica non tipizzata:
        // le chiavi degli extra diagnostici sono ignote a priori (variano per stack telefonico/device).
        @Suppress("DEPRECATION")
        return bundle.keySet().joinToString(", ") { key ->
            "$key=${runCatching { bundle.get(key) }.getOrNull()}"
        }
    }

    private fun logDiagnostic(message: String) {
        serviceScope.launch {
            runCatching {
                (applicationContext as App).logRepository.log(
                    LogAction.CALL_FORWARDING_SET,
                    LogResult.OK,
                    "[diagnostica chiamata] $message"
                )
            }
        }
    }
}
