package it.reperibilita.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import it.reperibilita.app.data.AppServiceFactory
import it.reperibilita.app.data.callforwarding.CallForwardingManager
import it.reperibilita.app.data.config.ConfigRepository
import it.reperibilita.app.data.config.StateRepository
import it.reperibilita.app.data.log.LogRepository
import it.reperibilita.app.data.sms.SmsSender
import it.reperibilita.app.domain.ShiftChangeUseCase
import it.reperibilita.app.worker.ScheduleCheckWorker

/** Composition root manuale (niente Hilt/Dagger per tenere il progetto leggero e ispezionabile). */
class App : Application() {

    lateinit var configRepository: ConfigRepository
        private set
    lateinit var stateRepository: StateRepository
        private set
    lateinit var logRepository: LogRepository
        private set
    lateinit var serviceFactory: AppServiceFactory
        private set
    lateinit var callForwardingManager: CallForwardingManager
        private set
    lateinit var smsSender: SmsSender
        private set

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this)
        stateRepository = StateRepository(this)
        logRepository = LogRepository(this)
        serviceFactory = AppServiceFactory(this, configRepository)
        callForwardingManager = CallForwardingManager(this)
        smsSender = SmsSender(this)

        createNotificationChannels()
        ScheduleCheckWorker.ensureScheduled(this, configRepository.current().checkIntervalMinutes)
        // Il controllo periodico ora e' solo una rete di sicurezza settimanale (vedi
        // ScheduleCheckWorker.schedulePreciseTrigger): senza questo tap immediato, un'app appena
        // avviata resterebbe senza il trigger preciso armato fino alla prima esecuzione periodica,
        // che con un intervallo di sicurezza cosi' lungo potrebbe non essere imminente.
        ScheduleCheckWorker.runNowOneOff(this)
    }

    fun buildShiftChangeUseCase(): ShiftChangeUseCase = ShiftChangeUseCase(
        serviceFactory = serviceFactory,
        configRepository = configRepository,
        stateRepository = stateRepository,
        logRepository = logRepository,
        callForwardingManager = callForwardingManager,
        smsSender = smsSender
    )

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.notif_channel_service_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.notif_channel_alerts_desc) }
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "reperibilita_service"
        const val CHANNEL_ALERTS = "reperibilita_alerts"
    }
}
