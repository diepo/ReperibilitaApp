package it.reperibilita.app.data.callforwarding

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import it.reperibilita.app.model.AppConfig
import it.reperibilita.app.model.ForwardingResult
import it.reperibilita.app.telecom.CallHolder
import it.reperibilita.app.telecom.DialerRoleHelper
import it.reperibilita.app.telecom.UssdAccessibilityBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Imposta l'inoltro di chiamata sulla SIM del device.
 *
 * Tre strategie, scelte automaticamente in base a permessi/ruolo disponibili, in ordine di
 * preferenza:
 *  1) USSD "privilegiato" (TelephonyManager.sendUssdRequest): richiede MODIFY_PHONE_STATE. Su
 *     Android 12+ questo permesso e' "gestito da ruolo" e NON concedibile nemmeno via adb senza
 *     root - funziona solo su device rootati o app privilegiate di sistema. Quando funziona, e'
 *     la via piu' affidabile: risposta della rete leggibile e verificabile.
 *  2) Dialer predefinito (TelecomManager.placeCall): se QUESTA app e' impostata come telefono
 *     predefinito del device (RoleManager.ROLE_DIALER), puo' piazzare la chiamata USSD
 *     direttamente senza il popup di conferma manuale che Android richiede quando il comando
 *     arriva da un'app terza - perche' in questo caso non c'e' nessuna "app terza", e' il dialer
 *     stesso a piazzarla. Non richiede root. L'esito viene letto (best-effort, non garantito
 *     quanto la via 1) osservando la causa di disconnessione della chiamata via
 *     ReperibilitaInCallService.
 *  3) Fallback via Intent.ACTION_CALL verso il dialer di sistema: funziona ovunque con solo
 *     CALL_PHONE, ma richiede conferma manuale dell'utente e l'esito non e' leggibile.
 *
 * Vedi docs/SETUP_DEVICE.md per i dettagli su come ottenere il percorso (1) o (2).
 */
class CallForwardingManager(private val context: Context) {

    private val privilegedStrategy = PrivilegedUssdStrategy(context)
    private val defaultDialerStrategy = DefaultDialerUssdStrategy(context)
    private val dialerStrategy = DialerUssdFallbackStrategy(context)

    fun hasModifyPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.MODIFY_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun isAppDefaultDialer(): Boolean = DialerRoleHelper.isDefaultDialer(context)

    suspend fun activateForwarding(config: AppConfig, targetNumber: String): ForwardingResult {
        val ussdCode = config.ussdActivateTemplate.replace("{number}", sanitizeNumber(targetNumber))
        return dispatch(config, ussdCode)
    }

    suspend fun deactivateForwarding(config: AppConfig): ForwardingResult =
        dispatch(config, config.ussdDeactivateCode)

    /**
     * Verifica REALE post-attivazione, leggendo lo stato attuale dalla rete (senza modificarlo)
     * e controllando che il numero configurato combaci con quello atteso.
     *
     * @return true se verificato attivo sul numero atteso, false se verificato ma NON combacia
     *         (es. comando non andato a buon fine nonostante nessun errore esplicito), null se
     *         la verifica non e' possibile su questo device (richiede controllo manuale).
     */
    suspend fun verifyForwardingNumber(config: AppConfig, expectedNumber: String): Boolean? {
        val privilegedAvailable = config.usePrivilegedUssd &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hasModifyPhoneStatePermission()

        // Il percorso "dialer predefinito" da solo (via ReperibilitaInCallService) si e'
        // dimostrato inaffidabile per leggere i risultati USSD su alcuni device: Android puo'
        // instradarli attraverso un meccanismo di sistema separato dal framework Call/
        // InCallService. Con l'Accessibility Service attivo (Impostazioni), pero', il testo del
        // popup di conferma viene letto direttamente dallo schermo, indipendentemente da come
        // Android l'ha instradato - quindi ha senso tentare la query solo se privilegiato O
        // accessibilita' sono disponibili, evitando altrimenti una chiamata USSD sprecata.
        val canQuery = privilegedAvailable ||
            (isAppDefaultDialer() && UssdAccessibilityBridge.isServiceEnabled(context))
        if (!canQuery) return null

        val result = if (privilegedAvailable) {
            privilegedStrategy.sendUssd(config.ussdStatusQueryCode)
        } else {
            defaultDialerStrategy.sendUssd(config.ussdStatusQueryCode)
        }
        return when (result) {
            is ForwardingResult.Success -> {
                // rawResponse nullo = nessuna conferma letta (es. timeout sul percorso dialer
                // predefinito): esito sconosciuto, NON un "non combacia" - altrimenti si
                // innescherebbe un ciclo di re-invio del comando di attivazione ad ogni check,
                // rifiutato dalla rete perche' l'inoltro e' gia' nello stato desiderato.
                val rawResponse = result.rawResponse ?: return null
                val expectedDigits = expectedNumber.filter { it.isDigit() }.takeLast(9)
                val responseDigits = rawResponse.filter { it.isDigit() }
                // confronto sulle ultime 9 cifre per tollerare formati diversi nella risposta
                // della rete (con/senza prefisso internazionale, spazi, trattini, ecc.)
                expectedDigits.isNotEmpty() && responseDigits.contains(expectedDigits)
            }
            is ForwardingResult.Failure -> null
        }
    }

    private suspend fun dispatch(config: AppConfig, ussdCode: String): ForwardingResult {
        val canUsePrivileged = config.usePrivilegedUssd &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hasModifyPhoneStatePermission()

        if (canUsePrivileged) {
            val result = privilegedStrategy.sendUssd(ussdCode)
            if (result !is ForwardingResult.Failure) return result
            // Se il permesso risultasse concesso ma la chiamata fallisse comunque, prova i
            // livelli successivi invece di bloccare subito l'automazione.
        }

        if (isAppDefaultDialer()) {
            val result = defaultDialerStrategy.sendUssd(ussdCode)
            if (result !is ForwardingResult.Failure) return result
        }

        return dialerStrategy.sendUssd(ussdCode)
    }

    private fun sanitizeNumber(number: String): String = number.trim()
}

/** Strategia 1: TelephonyManager.sendUssdRequest, con esito leggibile e verificato. */
private class PrivilegedUssdStrategy(private val context: Context) {

    suspend fun sendUssd(ussdCode: String): ForwardingResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ForwardingResult.Failure("sendUssdRequest richiede Android 8.0 (API 26)+")
        }
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ForwardingResult.Failure("TelephonyManager non disponibile")

        // Ne' onReceiveUssdResponse ne' onReceiveUssdResponseFailed sono garantiti dalla rete: se
        // il modem non richiama mai nessuno dei due (osservato possibile su alcune reti/device),
        // senza questo timeout la sospensione resterebbe appesa a tempo indeterminato, occupando
        // lo slot di "controllo in corso" ben oltre la durata attesa (stesso tipo di problema gia'
        // risolto per l'invio SMTP in SmtpMailSender - vedi il commento li').
        val result = withTimeoutOrNull(30_000) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    telephonyManager.sendUssdRequest(
                        ussdCode,
                        object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
                                if (cont.isActive) {
                                    cont.resume(ForwardingResult.Success(verified = true, rawResponse = response.toString()))
                                }
                            }

                            override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
                                if (cont.isActive) {
                                    cont.resume(
                                        ForwardingResult.Failure("USSD privilegiato fallito, codice errore rete: $failureCode")
                                    )
                                }
                            }
                        },
                        android.os.Handler(context.mainLooper)
                    )
                }
            }
        }
        return result ?: ForwardingResult.Failure("USSD privilegiato: nessuna risposta dalla rete entro 30s (timeout)")
    }
}

/**
 * Strategia 2: l'app e' il dialer predefinito, piazza la chiamata USSD direttamente via
 * TelecomManager.placeCall() invece che con un Intent.ACTION_CALL "da app terza" - Android non
 * richiede conferma manuale in questo caso, perche' e' il dialer stesso (noi) a piazzarla. Non
 * richiede MODIFY_PHONE_STATE ne' root.
 *
 * L'esito viene letto da DUE fonti in parallelo, usando qualunque risponda per prima:
 *  1) ReperibilitaInCallService, che osserva la causa di disconnessione della chiamata - su
 *     alcuni device contiene il messaggio di rete per i codici USSD, su altri no (Android puo'
 *     instradare i risultati USSD attraverso un meccanismo di sistema separato, invisibile a
 *     questa via - osservato in pratica).
 *  2) UssdResultAccessibilityService (opzionale, va attivato dall'utente in Impostazioni), che
 *     legge il testo del popup di conferma direttamente dallo schermo - funziona indipendentemente
 *     da come Android ha instradato il risultato, perche' legge cio' che finisce a video.
 * In entrambi i casi l'esito resta "best-effort", non verificato quanto la via 1 (USSD privilegiato).
 */
private class DefaultDialerUssdStrategy(private val context: Context) {

    suspend fun sendUssd(ussdCode: String): ForwardingResult {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return ForwardingResult.Failure("TelecomManager non disponibile")

        CallHolder.lastDisconnectCause.value = null
        UssdAccessibilityBridge.startCapture()

        // IMPORTANTE: se placeCall() non lancia un'eccezione, il comando e' stato inviato alla
        // rete - questo va sempre trattato come Success, anche se poi non riusciamo a leggerne
        // la conferma entro il timeout. Restituire Failure in quel caso farebbe reinviare lo
        // stesso comando USSD una seconda volta (vedi dispatch()): un secondo invio quando il
        // primo e' gia' andato a buon fine viene rifiutato dalla rete ("invalid MMI code"),
        // perche' lo stato e' gia' cambiato - un bug osservato in pratica, non solo teorico.
        try {
            // Stessa costruzione dell'URI gia' collaudata nel percorso fallback (Uri.parse su
            // stringa con encode esplicito) invece di Uri.fromParts: quest'ultima puo' gestire
            // in modo meno affidabile il carattere '#' nei codici USSD/MMI su alcuni stack
            // telefonici, con conseguente "invalid MMI code" anche per un codice corretto.
            val uri = Uri.parse("tel:" + Uri.encode(ussdCode))
            telecomManager.placeCall(uri, null)
        } catch (t: Throwable) {
            UssdAccessibilityBridge.stopCapture()
            return ForwardingResult.Failure("placeCall USSD fallito: ${t.message}", t)
        }

        // rawResponse resta null se il timeout scade senza aver letto nulla da nessuna delle due
        // fonti: e' importante NON sintetizzare qui un testo placeholder, perche'
        // verifyForwardingNumber() usa rawResponse == null per distinguere "nessuna conferma
        // letta" (esito sconosciuto) da "confermato che il numero non combacia" (esito negativo).
        val response = withTimeoutOrNull(20_000) {
            merge(
                CallHolder.lastDisconnectCause.filterNotNull(),
                UssdAccessibilityBridge.capturedText.filterNotNull()
            ).first()
        }
        UssdAccessibilityBridge.stopCapture()
        return ForwardingResult.Success(verified = false, rawResponse = response)
    }
}

/** Strategia 3: apre il dialer di sistema con il codice USSD. Richiede conferma manuale, nessuna conferma leggibile. */
private class DialerUssdFallbackStrategy(private val context: Context) {

    fun sendUssd(ussdCode: String): ForwardingResult {
        return try {
            val encoded = Uri.encode(ussdCode)
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encoded")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ForwardingResult.Success(
                verified = false,
                rawResponse = "Comando USSD '$ussdCode' inviato via dialer di sistema: richiede conferma manuale, esito non verificabile."
            )
        } catch (t: Throwable) {
            ForwardingResult.Failure("Invio USSD via dialer fallito: ${t.message}", t)
        }
    }
}
