package it.reperibilita.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.reperibilita.app.App

/** Ripristina il worker periodico dopo il riavvio del device (i job WorkManager non persistono da soli su tutti gli OEM). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as App
        ScheduleCheckWorker.ensureScheduled(context, app.configRepository.current().checkIntervalMinutes)
    }
}
