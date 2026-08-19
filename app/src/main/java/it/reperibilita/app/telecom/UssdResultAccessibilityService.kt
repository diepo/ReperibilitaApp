package it.reperibilita.app.telecom

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import it.reperibilita.app.App
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Legge il testo di QUALSIASI finestra a schermo (inclusi i popup di sistema mostrati dopo un
 * comando USSD, es. "Inoltro attivato verso...") mentre UssdAccessibilityBridge.capturing e'
 * true - cioe' solo nella breve finestra in cui CallForwardingManager sta aspettando l'esito di
 * un comando USSD appena inviato. Non clicca, non tocca, non modifica nulla: legge soltanto,
 * per registrare l'esito nel log dell'app invece di lasciarlo visibile solo all'utente.
 *
 * Necessario perche' su alcuni device Android instrada i risultati USSD/MMI attraverso un
 * meccanismo di sistema separato dal framework Call/InCallService (vedi
 * ReperibilitaInCallService), non osservabile da nessuna app tramite quelle API - l'accessibility
 * service legge invece cio' che finisce effettivamente sullo schermo, indipendentemente da come
 * ci e' arrivato.
 */
class UssdResultAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!UssdAccessibilityBridge.capturing) return
        val root = rootInActiveWindow ?: return
        val text = extractAllText(root).trim()
        if (text.isNotBlank()) {
            UssdAccessibilityBridge.capturedText.value = text
            logDiagnostic("testo catturato: $text")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun extractAllText(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 40) return "" // guardia contro alberi anomali/ricorsivi
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractAllText(child, depth + 1))
        }
        return sb.toString()
    }

    private fun logDiagnostic(message: String) {
        serviceScope.launch {
            runCatching {
                (applicationContext as App).logRepository.log(
                    LogAction.CALL_FORWARDING_SET,
                    LogResult.OK,
                    "[accessibilita] $message"
                )
            }
        }
    }
}
