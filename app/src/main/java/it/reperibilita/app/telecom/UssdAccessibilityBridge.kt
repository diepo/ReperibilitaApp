package it.reperibilita.app.telecom

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Punto di scambio tra UssdResultAccessibilityService (che legge il testo a schermo) e chi
 * aspetta la risposta di un comando USSD (CallForwardingManager). "capturing" e' un semplice
 * interruttore: il servizio di accessibilita' ignora tutto quando e' false, per non sprecare
 * lavoro/batteria e non intercettare testo irrilevante al di fuori di un comando USSD in corso.
 */
object UssdAccessibilityBridge {
    val capturedText = MutableStateFlow<String?>(null)

    @Volatile
    var capturing: Boolean = false
        private set

    fun startCapture() {
        capturedText.value = null
        capturing = true
    }

    fun stopCapture() {
        capturing = false
    }

    fun isServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
