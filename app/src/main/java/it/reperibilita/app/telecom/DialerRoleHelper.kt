package it.reperibilita.app.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

object DialerRoleHelper {

    fun isDefaultDialer(context: Context): Boolean {
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return false
        return telecomManager.defaultDialerPackage == context.packageName
    }

    /**
     * Compone un numero o codice USSD/MMI a mano (per test e verifiche manuali, es. "##21#" per
     * disattivare l'inoltro senza aspettare l'automazione). Usa TelecomManager.placeCall come il
     * resto dell'app; se non siamo il dialer predefinito, l'OS potrebbe comunque richiedere
     * conferma manuale per i codici USSD - normale e atteso in quel caso.
     */
    fun placeManualCall(context: Context, numberOrCode: String) {
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return
        val uri = Uri.parse("tel:" + Uri.encode(numberOrCode.trim()))
        telecomManager.placeCall(uri, null)
    }

    /**
     * Come placeManualCall, ma attiva anche la cattura di UssdAccessibilityBridge e aspetta fino
     * a 20s una risposta (dall'accessibility service o dalla causa di disconnessione della
     * chiamata) PRIMA di ritornare. Usata dai pulsanti di "Composizione manuale" nelle
     * Impostazioni: senza questo, quei pulsanti chiamavano placeManualCall() nudo, che non avvia
     * mai la cattura - risultato osservato in pratica: il servizio di accessibilita' non
     * registrava mai nulla per quei pulsanti, perche' UssdAccessibilityBridge.capturing restava
     * sempre false (il servizio ignora ogni evento quando capturing e' false, per non sprecare
     * lavoro fuori da un comando USSD in corso).
     *
     * @return il testo catturato, oppure null se nessuna fonte ha risposto entro il timeout
     *         (l'esito resta comunque visibile a video nel popup di sistema).
     */
    suspend fun placeManualCallWithCapture(context: Context, numberOrCode: String): String? {
        val telecomManager = context.getSystemService(TelecomManager::class.java)
            ?: throw IllegalStateException("TelecomManager non disponibile")

        CallHolder.lastDisconnectCause.value = null
        UssdAccessibilityBridge.startCapture()
        try {
            val uri = Uri.parse("tel:" + Uri.encode(numberOrCode.trim()))
            telecomManager.placeCall(uri, null)
        } catch (t: Throwable) {
            UssdAccessibilityBridge.stopCapture()
            throw t
        }

        val response = withTimeoutOrNull(20_000) {
            merge(
                CallHolder.lastDisconnectCause.filterNotNull(),
                UssdAccessibilityBridge.capturedText.filterNotNull()
            ).first()
        }
        UssdAccessibilityBridge.stopCapture()
        return response
    }

    /** Da lanciare con un ActivityResultLauncher da una Activity: mostra il dialog di sistema "Imposta come app Telefono predefinita?". */
    fun buildRequestRoleIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
    }
}
