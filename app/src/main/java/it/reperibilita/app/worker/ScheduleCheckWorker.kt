package it.reperibilita.app.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.reperibilita.app.App
import it.reperibilita.app.MainActivity
import it.reperibilita.app.R
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Worker periodico: ad ogni esecuzione chiede a ShiftChangeUseCase di controllare il calendario
 * e, se serve, eseguire il cambio turno (inoltro + SMS + mail + write-back), oppure applicare
 * l'override manuale se attivo. Gira come lavoro in foreground con notifica a bassa priorita'
 * per ridurre il rischio che il sistema lo termini (importante trattandosi di un device dedicato
 * "always on" alla reperibilità).
 */
class ScheduleCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App

        // Interruttore generale (Impostazioni -> Automazione): controllato qui, nell'UNICO punto
        // che ogni percorso di esecuzione attraversa (worker periodico, "Esegui controllo ora",
        // "Forza nuovo tentativo") - cosi' se l'utente lo spegne, DAVVERO non succede piu' nulla
        // indipendentemente da come viene invocato il worker, invece di dover replicare il
        // controllo in ogni punto che potrebbe far partire un check.
        if (!app.configRepository.current().automationEnabled) {
            app.logRepository.log(
                it.reperibilita.app.model.LogAction.SCHEDULE_CHECK,
                it.reperibilita.app.model.LogResult.WARNING,
                "Controllo saltato: automazione disattivata dall'utente (Impostazioni)"
            )
            return Result.success()
        }

        // Protezione contro esecuzioni sovrapposte: se il worker periodico e un tap manuale (o
        // piu' tap ravvicinati su "Forza nuovo tentativo"/"Esegui controllo ora") si sovrappongono,
        // SENZA questa guardia ognuno invierebbe il proprio comando di inoltro in parallelo - bug
        // osservato in pratica (raffica di inoltri ripetuti allo stesso numero).
        //
        // NON usiamo un semplice Mutex: se per qualunque motivo l'unlock non viene mai raggiunto
        // (processo terminato dal sistema a meta' esecuzione, cancellazione anomala, o qualunque
        // altra causa non prevista) un Mutex resterebbe bloccato PER SEMPRE, con il sintomo
        // osservato in pratica di "Controllo saltato" ripetuto all'infinito e nessun modo per
        // l'utente di sbloccare l'app se non forzando l'arresto. Con un timestamp invece, un
        // controllo "in corso" da piu' di MAX_CHECK_DURATION_MS viene considerato abbandonato e un
        // nuovo tentativo puo' comunque partire: nel caso normale (nessuno stallo) il
        // comportamento e' identico a un mutex, ma in caso di stallo anomalo l'app si riprende da
        // sola entro pochi minuti invece di restare bloccata a tempo indeterminato.
        if (!tryAcquireCheckSlot()) {
            app.logRepository.log(
                it.reperibilita.app.model.LogAction.SCHEDULE_CHECK,
                it.reperibilita.app.model.LogResult.WARNING,
                "Controllo saltato: un altro controllo era gia' in corso (evita esecuzioni sovrapposte)"
            )
            return Result.success()
        }

        return try {
            setForeground(createForegroundInfo())
            val nextBoundary = app.buildShiftChangeUseCase().runCheck()
            if (nextBoundary != null) {
                schedulePreciseTrigger(applicationContext, nextBoundary)
            }
            Result.success()
        } catch (t: Throwable) {
            app.logRepository.log(
                it.reperibilita.app.model.LogAction.ERROR,
                it.reperibilita.app.model.LogResult.ERROR,
                "Esecuzione worker fallita: ${t.message}",
                detail = t.stackTraceToString()
            )
            Result.retry()
        } finally {
            releaseCheckSlot()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val openAppIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_SERVICE)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Controllo turni reperibilità attivo")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        // Obbligatorio da Android 14 (targetSdk 34): un foreground service senza tipo esplicito
        // ("none") viene rifiutato dal sistema con InvalidForegroundServiceTypeException.
        // Il worker legge il calendario (rete/file) e scrive log/stato: dataSync è il tipo corretto.
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val WORK_NAME = "schedule_check_worker"
        private const val ONE_OFF_WORK_NAME = "schedule_check_worker_one_off"
        private const val PRECISE_TRIGGER_WORK_NAME = "schedule_check_worker_precise_trigger"
        private const val NOTIFICATION_ID = 42

        // Margine di sicurezza sull'orario esatto del confine: WorkManager garantisce di non
        // sparare PRIMA del delay richiesto, ma puo' farlo con un po' di ritardo (Doze, batching
        // di sistema) - mai in anticipo. Il margine serve solo a evitare di trovarsi esattamente
        // sull'istante di confine per un pelo di imprecisione dell'orologio, non a compensare
        // ritardi del sistema (quelli sono normali e innocui: il controllo comunque coprirebbe
        // correttamente il nuovo turno una volta eseguito).
        private const val PRECISE_TRIGGER_MARGIN_SECONDS = 60L

        // Companion object = condiviso tra tutte le istanze del Worker (WorkManager ne crea una
        // nuova ad ogni esecuzione): serve proprio per rilevare esecuzioni sovrapposte a livello
        // di processo, indipendentemente da come sono state schedulate (periodica o manuale).
        // 0L = nessun controllo in corso; altrimenti timestamp (epoch ms) di quando e' iniziato.
        private val checkInProgressSince = AtomicLong(0L)

        // Un vero controllo (lettura calendario + eventuale USSD + SMS + mail) impiega in pratica
        // pochi secondi; 5 minuti e' un margine ampio che non dovrebbe mai scattare in condizioni
        // normali, ma garantisce che un eventuale stallo anomalo si sblocchi da solo in tempi
        // ragionevoli invece di restare bloccato a tempo indeterminato.
        private const val MAX_CHECK_DURATION_MS = 5 * 60 * 1000L

        private fun tryAcquireCheckSlot(): Boolean {
            val now = System.currentTimeMillis()
            val current = checkInProgressSince.get()
            val staleOrFree = current == 0L || now - current >= MAX_CHECK_DURATION_MS
            if (!staleOrFree) return false
            // compareAndSet invece di get+set separati: evita che due esecuzioni che superano
            // insieme il controllo sopra (raro ma possibile) acquisiscano entrambe lo slot.
            return checkInProgressSince.compareAndSet(current, now)
        }

        private fun releaseCheckSlot() {
            checkInProgressSince.set(0L)
        }

        // Rete di sicurezza: con il trigger preciso (sotto) che si riarma da solo ad ogni
        // esecuzione, il controllo periodico non serve piu' per la puntualita' del cambio turno -
        // resta solo per il caso in cui il trigger preciso si perda per qualche motivo (es. dati
        // WorkManager cancellati, boot senza che BootReceiver riesca a farlo ripartire). Percio'
        // ha senso un default molto meno frequente di prima (era ogni 15 minuti).
        fun ensureScheduled(context: Context, intervalMinutes: Int) {
            val safeInterval = intervalMinutes.coerceAtLeast(15) // minimo imposto da WorkManager
            val request = PeriodicWorkRequestBuilder<ScheduleCheckWorker>(safeInterval.toLong(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Arma un controllo one-shot esattamente all'istante in cui il turno attivo finisce (o,
         * se in questo momento c'e' un buco nel calendario, all'istante in cui inizia il prossimo
         * turno noto) - vedi ShiftChangeUseCase.runCheck(). Funziona per qualunque durata di
         * turno, perche' l'istante arriva dai dati reali del calendario (ShiftEntry.endDateTime/
         * startDateTime), non da una cadenza fissa assunta a priori.
         *
         * REPLACE, non KEEP: ogni esecuzione (periodica, manuale, o di questo stesso trigger)
         * ricalcola e riarma il prossimo confine, convergendo sempre al valore corretto anche se
         * il calendario e' cambiato nel frattempo - la vecchia richiesta in coda va sostituita,
         * non ignorata.
         */
        fun schedulePreciseTrigger(context: Context, fireAt: LocalDateTime) {
            val delay = Duration.between(LocalDateTime.now(), fireAt).plusSeconds(PRECISE_TRIGGER_MARGIN_SECONDS)
            val delayMillis = delay.toMillis().coerceAtLeast(0)
            val request = androidx.work.OneTimeWorkRequestBuilder<ScheduleCheckWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(PRECISE_TRIGGER_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun runNowOneOff(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ScheduleCheckWorker>().build()
            // ExistingWorkPolicy.KEEP: se un tap precedente e' ancora in coda/in esecuzione,
            // i tap successivi vengono ignorati invece di accodare altre esecuzioni parallele.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_OFF_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Usato da "Forza nuovo tentativo": a differenza di runNowOneOff, usa REPLACE invece di
         * KEEP. Chi preme quel bottone si aspetta che un controllo parta DAVVERO (soprattutto
         * perche' quel bottone azzera anche lo stato locale subito prima) - con KEEP la richiesta
         * poteva essere scartata silenziosamente se un altro lavoro era gia' in coda.
         */
        fun runForceRecheckOneOff(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ScheduleCheckWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_OFF_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Permette alla UI (Dashboard) di sapere quando il controllo avviato da runNowOneOff/
         * runForceRecheckOneOff finisce davvero, invece di indovinare un tempo di attesa fisso:
         * la Dashboard puo' aggiornarsi da sola (refresh()) appena lo stato passa a
         * SUCCEEDED/FAILED, invece di restare ferma sui dati letti prima che il controllo finisse.
         */
        fun observeOneOffWork(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ONE_OFF_WORK_NAME)
    }
}
