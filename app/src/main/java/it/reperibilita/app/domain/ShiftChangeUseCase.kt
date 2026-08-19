package it.reperibilita.app.domain

import it.reperibilita.app.data.AppServiceFactory
import it.reperibilita.app.data.callforwarding.CallForwardingManager
import it.reperibilita.app.data.config.ConfigRepository
import it.reperibilita.app.data.config.StateRepository
import it.reperibilita.app.data.log.LogEntry
import it.reperibilita.app.data.log.LogRepository
import it.reperibilita.app.data.sms.SmsSender
import it.reperibilita.app.model.AppConfig
import it.reperibilita.app.model.ForwardingResult
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult
import it.reperibilita.app.model.ShiftEntry
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val OVERRIDE_ROW_SENTINEL = -2

/**
 * Orchestra l'intero flusso: legge il calendario, rileva un cambio turno (o applica un override
 * manuale), imposta l'inoltro chiamata, invia SMS alle due persone coinvolte, invia la mail
 * informativa, scrive lo stato sul file Excel sorgente e, in caso di errore in una qualsiasi
 * fase, notifica al numero di servizio e alle mail di errore configurate.
 */
class ShiftChangeUseCase(
    private val serviceFactory: AppServiceFactory,
    private val configRepository: ConfigRepository,
    private val stateRepository: StateRepository,
    private val logRepository: LogRepository,
    private val callForwardingManager: CallForwardingManager,
    private val smsSender: SmsSender,
    private val scheduler: OnCallScheduler = OnCallScheduler()
) {

    /**
     * @return l'istante del prossimo confine di turno noto (fine del turno attivo, o inizio del
     *         prossimo turno se in questo momento non c'e' nessuno attivo per un buco nel
     *         calendario), da usare per riarmare un trigger one-shot preciso (vedi
     *         ScheduleCheckWorker.schedulePreciseTrigger) - null se non c'e' un confine noto da
     *         riarmare (lettura calendario fallita, o override manuale attivo, che non ha una
     *         "prossima scadenza" legata al calendario). Calcolato dai dati REALI del turno
     *         attivo (endDateTime), non da una durata assunta a priori: funziona identico se un
     *         turno dura 7 giorni, 5 giorni, o qualunque altra durata - cambia solo cosa c'e'
     *         scritto nel file Excel, non questa logica.
     */
    suspend fun runCheck(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val config = configRepository.current()
        logRepository.log(LogAction.SCHEDULE_CHECK, LogResult.OK, "Controllo turno eseguito alle $now")

        val nextBoundary = if (config.manualOverrideActive) {
            handleOverride(config, now)
            null
        } else {
            handleScheduledCheck(config, now)
        }

        sendDailySummaryIfDue(now)
        return nextBoundary
    }

    private suspend fun handleScheduledCheck(config: AppConfig, now: LocalDateTime): LocalDateTime? {
        val shifts = try {
            serviceFactory.buildScheduleSource().readAllShifts()
        } catch (t: Throwable) {
            val msg = "Lettura calendario reperibilità fallita: ${t.message}"
            logRepository.log(LogAction.ERROR, LogResult.ERROR, msg, detail = t.stackTraceToString())
            notifyError(msg)
            return null
        }

        val active = scheduler.findActiveShift(shifts, now)
        if (active == null) {
            if (stateRepository.lastActivatedRowIndex != -1) {
                val msg = "Nessun turno di reperibilità coperto per l'orario corrente ($now): controllare il calendario"
                logRepository.log(LogAction.ERROR, LogResult.WARNING, msg)
                notifyError(msg)
            }
            // Buco nel calendario: arma comunque il trigger preciso sull'inizio del prossimo
            // turno noto, cosi' il cambio parte puntuale appena il buco finisce invece di
            // aspettare il prossimo controllo periodico (di sicurezza, ora molto meno frequente).
            return scheduler.findNextUpcomingShift(shifts, now)?.startDateTime
        }

        if (active.rowIndex == stateRepository.lastActivatedRowIndex) return active.endDateTime // nessun cambio, ma riarma comunque il prossimo confine

        performShiftChange(
            config = config,
            previousPersonName = stateRepository.lastActivatedPersonName,
            previousNumber = stateRepository.lastActivatedNumber,
            incoming = active,
            writeBackRowIndex = active.rowIndex
        )
        return active.endDateTime
    }

    private suspend fun handleOverride(config: AppConfig, now: LocalDateTime) {
        if (stateRepository.lastActivatedRowIndex == OVERRIDE_ROW_SENTINEL &&
            stateRepository.lastActivatedNumber == config.manualOverrideNumber
        ) {
            return // override gia' attivo con lo stesso numero, nulla da fare
        }

        val syntheticShift = ShiftEntry(
            rowIndex = OVERRIDE_ROW_SENTINEL,
            startDateTime = now,
            endDateTime = now.plusYears(100),
            personName = config.manualOverridePersonName.ifBlank { "Override manuale" },
            forwardNumber = config.manualOverrideNumber,
            personEmail = null,
            notes = "Impostato manualmente da GUI"
        )

        logRepository.log(LogAction.MANUAL_OVERRIDE, LogResult.OK, "Override manuale attivato verso ${syntheticShift.forwardNumber}")

        performShiftChange(
            config = config,
            previousPersonName = stateRepository.lastActivatedPersonName,
            previousNumber = stateRepository.lastActivatedNumber,
            incoming = syntheticShift,
            writeBackRowIndex = null // un override manuale non corrisponde a nessuna riga del file sorgente
        )
    }

    private suspend fun performShiftChange(
        config: AppConfig,
        previousPersonName: String?,
        previousNumber: String?,
        incoming: ShiftEntry,
        writeBackRowIndex: Int?
    ) {
        // Validazione PRIMA di qualunque azione: un numero non valido (es. "0", vuoto, o troppo
        // corto per essere un numero reale) non deve MAI arrivare a inviare un comando USSD ne'
        // un SMS - osservato in pratica un tentativo di inoltro verso "0", causato da una cella
        // vuota nel tab "Dati" del calendario (INDEX su una cella vuota restituisce 0 in Excel,
        // non testo vuoto, quindi IFERROR non lo intercetta - vedi anche il fix nella formula del
        // template). Qui e' la barriera che conta davvero, indipendente da cosa produce il dato.
        if (!isValidForwardNumber(incoming.forwardNumber)) {
            val msg = "Numero di inoltro non valido per ${incoming.personName}: \"${incoming.forwardNumber}\" " +
                "- controllare la colonna NumeroInoltro (o il tab Dati) nel calendario. Nessuna azione eseguita " +
                "(nessun inoltro, nessun SMS)."
            logRepository.log(LogAction.ERROR, LogResult.ERROR, msg, personName = incoming.personName)
            notifyError(msg)
            return
        }

        // Pre-check: se riusciamo a verificare che l'inoltro e' GIA' impostato correttamente sul
        // numero atteso (query alla rete, non modifica nulla), non rifare tutto da capo. Evita
        // di re-inviare comandi USSD e, soprattutto, SMS di notifica quando in realta' non e'
        // cambiato nulla - utile perche' lo stato locale dell'app (stateRepository) puo' andare
        // fuori sincrono rispetto allo stato reale della SIM (es. dopo una reinstallazione o
        // passando a un altro device di test), mentre la rete resta la fonte di verita'.
        // Funziona solo dove la verifica e' possibile (USSD privilegiato o dialer predefinito);
        // sul fallback dialer si procede sempre con il flusso completo, come prima.
        val alreadyActive = runCatching {
            callForwardingManager.verifyForwardingNumber(config, incoming.forwardNumber)
        }.getOrNull()

        if (alreadyActive == true) {
            logRepository.log(
                LogAction.CALL_FORWARDING_SET, LogResult.OK,
                "Inoltro già correttamente attivo su ${incoming.forwardNumber} (${incoming.personName}): " +
                    "nessuna azione necessaria (stato locale risincronizzato)",
                personName = incoming.personName
            )
            stateRepository.lastActivatedRowIndex = incoming.rowIndex
            stateRepository.lastActivatedPersonName = incoming.personName
            stateRepository.lastActivatedNumber = incoming.forwardNumber
            return
        }

        val forwardingResult = runCatching { callForwardingManager.activateForwarding(config, incoming.forwardNumber) }
            .getOrElse { ForwardingResult.Failure("Eccezione durante l'inoltro chiamata: ${it.message}", it) }

        when (forwardingResult) {
            is ForwardingResult.Success -> {
                logRepository.log(
                    LogAction.CALL_FORWARDING_SET,
                    if (forwardingResult.verified) LogResult.OK else LogResult.WARNING,
                    "Comando inoltro inviato su ${incoming.forwardNumber} (${incoming.personName})" +
                        if (!forwardingResult.verified) " - esito non ancora verificato" else "",
                    detail = forwardingResult.rawResponse,
                    personName = incoming.personName
                )

                // Verifica REALE post-attivazione (rilettura dello stato dalla rete): possibile
                // solo sul percorso USSD privilegiato. true = confermato sul numero atteso,
                // false = interrogato ma NON combacia (comando non riuscito nonostante nessun
                // errore esplicito), null = non verificabile su questo device (fallback dialer).
                val verification = runCatching {
                    callForwardingManager.verifyForwardingNumber(config, incoming.forwardNumber)
                }.getOrElse {
                    logRepository.log(LogAction.ERROR, LogResult.WARNING, "Verifica post-attivazione fallita con eccezione: ${it.message}")
                    null
                }

                when (verification) {
                    false -> {
                        val msg = "Verifica post-attivazione: l'inoltro NON risulta impostato su " +
                            "${incoming.forwardNumber} (${incoming.personName}) nonostante il comando sia stato " +
                            "inviato senza errori. Il cambio turno NON viene considerato completato: si " +
                            "riprovera' al prossimo controllo."
                        logRepository.log(LogAction.ERROR, LogResult.ERROR, msg, personName = incoming.personName)
                        notifyError(msg)
                        // Stato NON aggiornato di proposito: al prossimo giro il turno risultera'
                        // ancora "cambiato" e il tentativo verra' ripetuto automaticamente.
                        return
                    }
                    true -> logRepository.log(
                        LogAction.CALL_FORWARDING_SET, LogResult.OK,
                        "Inoltro verificato attivo su ${incoming.forwardNumber} (interrogazione rete)",
                        personName = incoming.personName
                    )
                    null -> logRepository.log(
                        LogAction.CALL_FORWARDING_SET, LogResult.WARNING,
                        "Verifica automatica non disponibile su questo device (richiede il percorso USSD " +
                            "privilegiato, vedi docs/SETUP_DEVICE.md): controllare manualmente l'inoltro",
                        personName = incoming.personName
                    )
                }

                notifySms(incoming, previousPersonName, previousNumber)
                notifyInfoMail(config, incoming, previousPersonName)

                if (config.writeBackToExcel && writeBackRowIndex != null) {
                    writeBackExcelStatus(writeBackRowIndex, incoming, now = LocalDateTime.now(), verified = verification == true)
                }

                stateRepository.lastActivatedRowIndex = incoming.rowIndex
                stateRepository.lastActivatedPersonName = incoming.personName
                stateRepository.lastActivatedNumber = incoming.forwardNumber
            }

            is ForwardingResult.Failure -> {
                val msg = "Impostazione inoltro chiamata fallita per ${incoming.personName} (${incoming.forwardNumber}): ${forwardingResult.reason}"
                logRepository.log(
                    LogAction.ERROR,
                    LogResult.ERROR,
                    msg,
                    detail = forwardingResult.throwable?.stackTraceToString(),
                    personName = incoming.personName
                )
                notifyError(msg)
            }
        }
    }

    /**
     * Filtra i numeri chiaramente non validi (vuoto, "0"/tutti zeri, o troppo corto per essere
     * un numero di telefono reale) PRIMA che possano innescare un comando USSD o un SMS. Non e'
     * una validazione E.164 completa di proposito: l'obiettivo e' scartare dati palesemente
     * sbagliati (es. una cella vuota che Excel ha convertito in 0), non rifiutare formati
     * legittimi ma insoliti.
     */
    private fun isValidForwardNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        if (digits.all { it == '0' }) return false
        if (digits.length < 6) return false
        return true
    }

    private suspend fun notifySms(incoming: ShiftEntry, previousPersonName: String?, previousNumber: String?) {
        runCatching {
            smsSender.send(
                incoming.forwardNumber,
                "Ciao ${incoming.personName}, da ora sei la persona reperibile. Le chiamate ti verranno inoltrate automaticamente."
            )
            logRepository.log(LogAction.SMS_SENT, LogResult.OK, "SMS di attivazione inviato a ${incoming.personName}", personName = incoming.personName)
        }.onFailure {
            logRepository.log(LogAction.ERROR, LogResult.ERROR, "SMS di attivazione a ${incoming.personName} fallito: ${it.message}", personName = incoming.personName)
            notifyError("Invio SMS di attivazione a ${incoming.personName} fallito: ${it.message}")
        }

        if (!previousNumber.isNullOrBlank() && previousNumber != incoming.forwardNumber) {
            runCatching {
                smsSender.send(
                    previousNumber,
                    "Ciao ${previousPersonName.orEmpty()}, il tuo turno di reperibilità è terminato. Da ora è reperibile ${incoming.personName}. Grazie!"
                )
                logRepository.log(LogAction.SMS_SENT, LogResult.OK, "SMS di fine turno inviato a ${previousPersonName}", personName = previousPersonName)
            }.onFailure {
                logRepository.log(LogAction.ERROR, LogResult.ERROR, "SMS di fine turno a $previousPersonName fallito: ${it.message}", personName = previousPersonName)
                notifyError("Invio SMS di fine turno a $previousPersonName fallito: ${it.message}")
            }
        }
    }

    private suspend fun notifyInfoMail(config: AppConfig, incoming: ShiftEntry, previousPersonName: String?) {
        if (config.notifications.infoEmailRecipients.isEmpty()) return
        runCatching {
            val subject = "Cambio reperibilità: ora attivo ${incoming.personName}"
            val body = """
                <p>La reperibilità è stata trasferita.</p>
                <ul>
                  <li><b>Persona uscente:</b> ${previousPersonName ?: "-"}</li>
                  <li><b>Persona entrante:</b> ${incoming.personName} (${incoming.forwardNumber})</li>
                  <li><b>Data/ora cambio:</b> ${LocalDateTime.now()}</li>
                  <li><b>Note turno:</b> ${incoming.notes ?: "-"}</li>
                </ul>
            """.trimIndent()
            serviceFactory.buildMailSender().send(config.notifications.infoEmailRecipients, subject, body)
            logRepository.log(LogAction.MAIL_SENT, LogResult.OK, "Mail informativa cambio turno inviata")
        }.onFailure {
            logRepository.log(LogAction.ERROR, LogResult.ERROR, "Invio mail informativa fallito: ${it.message}")
            notifyError("Invio mail informativa cambio turno fallito: ${it.message}")
        }
    }

    private suspend fun writeBackExcelStatus(rowIndex: Int, incoming: ShiftEntry, now: LocalDateTime, verified: Boolean) {
        runCatching {
            val statusText = if (verified) {
                "ATTIVATO E VERIFICATO ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (${incoming.personName})"
            } else {
                "INVIATO - VERIFICA MANUALE NECESSARIA ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (${incoming.personName})"
            }
            serviceFactory.buildScheduleSource().writeBackStatus(rowIndex, statusText)
            logRepository.log(LogAction.EXCEL_WRITE_BACK, LogResult.OK, "Stato scritto sul file Excel sorgente (riga $rowIndex)")
        }.onFailure {
            // Il write-back e' un requisito "se possibile": un suo fallimento non deve bloccare
            // il cambio turno gia' avvenuto, ma va comunque segnalato.
            logRepository.log(LogAction.ERROR, LogResult.WARNING, "Write-back su Excel fallito (riga $rowIndex): ${it.message}")
            notifyError("Write-back su Excel fallito per la riga $rowIndex: ${it.message}")
        }
    }

    private suspend fun notifyError(message: String) {
        val config = configRepository.current()
        // Controllo ridondante (l'interruttore generale gia' impedisce a runCheck() di arrivare
        // fin qui - vedi ScheduleCheckWorker.doWork()): tenuto qui apposta come seconda barriera
        // indipendente, cosi' un eventuale futuro percorso che chiami notifyError() senza passare
        // da li' non riesce comunque a mandare notifiche mentre l'automazione e' spenta.
        if (!config.automationEnabled) return
        if (config.notifications.serviceErrorPhoneNumber.isNotBlank()) {
            runCatching { smsSender.send(config.notifications.serviceErrorPhoneNumber, "ERRORE reperibilità: $message") }
        }
        if (config.notifications.errorEmailRecipients.isNotEmpty()) {
            runCatching {
                serviceFactory.buildMailSender().send(
                    config.notifications.errorEmailRecipients,
                    "Errore automazione reperibilità",
                    "<p>${message}</p><p>Orario: ${LocalDateTime.now()}</p>"
                )
            }
        }
    }

    private suspend fun sendDailySummaryIfDue(now: LocalDateTime) {
        val config = configRepository.current()
        if (config.notifications.summaryEmailRecipients.isEmpty()) return

        val today = now.toLocalDate().toEpochDay()
        if (stateRepository.lastSummaryEpochDay == today) return

        val zone = ZoneId.systemDefault()
        val periodStart = now.toLocalDate().minusDays(1).atStartOfDay()
        val periodEnd = now.toLocalDate().atStartOfDay()

        runCatching {
            val entries = logRepository.entriesBetween(
                periodStart.atZone(zone).toInstant().toEpochMilli(),
                periodEnd.atZone(zone).toInstant().toEpochMilli()
            )
            val body = buildSummaryHtml(entries, periodStart, periodEnd)
            serviceFactory.buildMailSender().send(
                config.notifications.summaryEmailRecipients,
                "Riepilogo reperibilità ${periodStart.toLocalDate()}",
                body
            )
            logRepository.log(LogAction.MAIL_SENT, LogResult.OK, "Mail di riepilogo giornaliero inviata")
            stateRepository.lastSummaryEpochDay = today
        }.onFailure {
            logRepository.log(LogAction.ERROR, LogResult.ERROR, "Invio mail di riepilogo fallito: ${it.message}")
        }
    }

    private fun buildSummaryHtml(entries: List<LogEntry>, from: LocalDateTime, to: LocalDateTime): String {
        val rows = entries.joinToString("\n") { e ->
            val ts = Instant.ofEpochMilli(e.timestampEpochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
            "<tr><td>$ts</td><td>${e.action}</td><td>${e.result}</td><td>${e.personName ?: ""}</td><td>${e.message}</td></tr>"
        }
        return """
            <p>Riepilogo azioni reperibilità dal $from al $to.</p>
            <table border="1" cellpadding="4" cellspacing="0">
              <tr><th>Data/ora</th><th>Azione</th><th>Esito</th><th>Persona</th><th>Dettaglio</th></tr>
              $rows
            </table>
        """.trimIndent()
    }
}
