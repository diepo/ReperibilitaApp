package it.reperibilita.app.data.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class SmsSendException(message: String) : Exception(message)

/**
 * Invia SMS di notifica cambio turno tramite la SIM del device (nessun servizio gateway esterno:
 * usa direttamente android.telephony.SmsManager sulla SIM installata nel telefono dedicato).
 */
class SmsSender(private val context: Context) {

    private val requestCodeCounter = AtomicInteger(1000)

    // Se il broadcast di conferma non arriva mai (es. modem in stato anomalo, nessuna SIM), senza
    // un limite la sospensione resterebbe appesa a tempo indeterminato - stesso tipo di problema
    // gia' risolto per SMTP (vedi SmtpMailSender) e USSD privilegiato (vedi CallForwardingManager).
    suspend fun send(phoneNumber: String, message: String) = withTimeout(20_000) { sendInternal(phoneNumber, message) }

    private suspend fun sendInternal(phoneNumber: String, message: String) = suspendCancellableCoroutine<Unit> { cont ->
        val requestCode = requestCodeCounter.incrementAndGet()
        val action = "$ACTION_SMS_SENT.$requestCode"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                context.unregisterReceiver(this)
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    else -> {
                        if (cont.isActive) cont.cancel(SmsSendException("Invio SMS a $phoneNumber fallito (codice esito $resultCode)"))
                    }
                }
            }
        }

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val sentIntent = android.app.PendingIntent.getBroadcast(context, requestCode, Intent(action), piFlags)

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        cont.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
        }

        runCatching {
            val parts = smsManager.divideMessage(message)
            if (parts.size <= 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, null)
            } else {
                val sentIntents = ArrayList<android.app.PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null)
            }
        }.onFailure {
            runCatching { context.unregisterReceiver(receiver) }
            if (cont.isActive) cont.cancel(SmsSendException("Invio SMS a $phoneNumber fallito: ${it.message}"))
        }
    }

    companion object {
        private const val ACTION_SMS_SENT = "it.reperibilita.app.SMS_SENT"
    }
}
