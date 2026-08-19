package it.reperibilita.app.ui.viewmodel

import android.telecom.TelecomManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import it.reperibilita.app.App
import it.reperibilita.app.data.log.LogEntry
import it.reperibilita.app.domain.OnCallScheduler
import it.reperibilita.app.worker.ScheduleCheckWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class DashboardUiState(
    val currentPersonName: String? = null,
    val currentNumber: String? = null,
    val nextPersonName: String? = null,
    val nextStart: LocalDateTime? = null,
    val overrideActive: Boolean = false,
    val isRefreshing: Boolean = false,
    // true da quando l'utente preme "Esegui controllo ora"/"Forza nuovo tentativo" fino a quando
    // WorkManager segnala che quel lavoro e' terminato (vedi observeOneOffWork) - permette alla UI
    // di mostrare un vero avanzamento invece di far sembrare che il tap non abbia fatto nulla.
    val isCheckRunning: Boolean = false,
    // Messaggio "usa e getta" mostrato una volta (Toast) e poi consumato: conferma il tap subito,
    // poi l'esito reale quando il controllo in background finisce.
    val lastActionMessage: String? = null,
    val lastError: String? = null,
    val defaultDialerLabel: String? = null,
    val defaultDialerRisky: Boolean = false,
    // Guidano la card "Setup guidato" in cima alla Dashboard: entrambi richiedono un tap esplicito
    // dell'utente su un dialog di sistema (Android non permette a nessuna app di auto-concedersi
    // ne' il ruolo di app Telefono predefinita ne' l'accesso al servizio di Accessibilita' - e'
    // una protezione di sicurezza voluta, non un limite di questa app), ma la card li rende
    // visibili e a portata di un tap invece di doverli cercare nei menu di sistema.
    val isAppDefaultDialer: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val automationEnabled: Boolean = true
)

// App telefono di terze parti note per non gestire correttamente i codici USSD (compongono la
// stringa ma non la inviano alla rete, o intercettano ACTION_CALL senza completarlo). Elenco
// best-effort, non esaustivo: se il dialer predefinito non e' qui, non significa che sia sicuro,
// solo che non abbiamo ancora un caso noto documentato.
private val KNOWN_RISKY_DIALER_PACKAGES = setOf(
    "com.truecaller",
    "com.hiya.star"
)

class DashboardViewModel(private val app: App) : ViewModel() {

    private val scheduler = OnCallScheduler()

    // Rete di sicurezza lato UI: se per qualunque motivo WorkManager non segnalasse mai il
    // completamento del lavoro one-off, i pulsanti resterebbero disabilitati per sempre - lo
    // stesso tipo di stallo permanente gia' risolto lato worker (vedi ScheduleCheckWorker). Questo
    // timeout garantisce che la UI si sblocchi comunque entro 90s.
    private var checkRunningTimeout: Job? = null

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    val recentLogs: StateFlow<List<LogEntry>> = app.logRepository.observeRecent(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
        // Osserva il lavoro one-off (runCheckNow/forceRecheck) per sapere DAVVERO quando finisce,
        // invece di lasciare la Dashboard ferma sui dati letti prima che il controllo in background
        // fosse completo - bug segnalato in pratica ("la dashboard non si aggiorna").
        viewModelScope.launch {
            ScheduleCheckWorker.observeOneOffWork(app).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        checkRunningTimeout?.cancel()
                        if (_uiState.value.isCheckRunning) {
                            _uiState.value = _uiState.value.copy(
                                isCheckRunning = false,
                                lastActionMessage = "Controllo completato"
                            )
                        }
                        refresh()
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        checkRunningTimeout?.cancel()
                        if (_uiState.value.isCheckRunning) {
                            _uiState.value = _uiState.value.copy(
                                isCheckRunning = false,
                                lastActionMessage = "Controllo fallito: vedi \"Ultime azioni\" per i dettagli"
                            )
                        }
                        refresh()
                    }
                    else -> Unit // ENQUEUED/RUNNING/BLOCKED: nessuna azione, isCheckRunning resta true
                }
            }
        }
    }

    /** Consuma lastActionMessage dopo che la UI l'ha mostrato una volta (es. in un Toast). */
    fun consumeActionMessage() {
        _uiState.value = _uiState.value.copy(lastActionMessage = null)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, lastError = null)
            val config = app.configRepository.current()
            val state = app.stateRepository
            val (dialerLabel, dialerRisky) = readDefaultDialerInfo()

            // "Reperibile ora"/"Numero attivo" vengono SEMPRE da stateRepository: dato locale,
            // istantaneo, nessuna chiamata di rete - va aggiornato subito e incondizionatamente.
            // Prima questi campi venivano scritti solo DENTRO lo stesso runCatching che legge il
            // calendario dalla rete per calcolare il "prossimo cambio": un qualunque errore di
            // rete/Graph in QUELLA lettura (secondaria, solo informativa) faceva fallire l'intero
            // blocco e cancellava anche "Reperibile ora", che invece era gia' noto e corretto - bug
            // osservato in pratica ("Nessuno" mostrato anche con l'inoltro correttamente attivo).
            _uiState.value = _uiState.value.copy(
                currentPersonName = state.lastActivatedPersonName,
                currentNumber = state.lastActivatedNumber,
                overrideActive = config.manualOverrideActive,
                defaultDialerLabel = dialerLabel,
                defaultDialerRisky = dialerRisky,
                isAppDefaultDialer = it.reperibilita.app.telecom.DialerRoleHelper.isDefaultDialer(app),
                isAccessibilityEnabled = it.reperibilita.app.telecom.UssdAccessibilityBridge.isServiceEnabled(app),
                automationEnabled = config.automationEnabled
            )

            runCatching {
                val now = LocalDateTime.now()
                val next = if (!config.manualOverrideActive) {
                    val shifts = app.serviceFactory.buildScheduleSource().readAllShifts()
                    scheduler.findNextUpcomingShift(shifts, now)
                } else null

                _uiState.value = _uiState.value.copy(
                    nextPersonName = next?.personName,
                    nextStart = next?.startDateTime,
                    isRefreshing = false
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    lastError = it.message
                )
            }
        }
    }

    /**
     * L'inoltro via fallback dialer (percorso usato quando manca MODIFY_PHONE_STATE) dipende
     * dall'app Telefono predefinita: alcuni dialer di terze parti (es. Truecaller) compongono il
     * codice USSD ma non lo inviano davvero alla rete. Segnalato qui perche' e' un problema reale
     * osservato: il comando "sembra" andato a buon fine ma l'inoltro non viene mai attivato.
     */
    private fun readDefaultDialerInfo(): Pair<String?, Boolean> {
        val telecomManager = app.getSystemService(TelecomManager::class.java) ?: return null to false
        val packageName = runCatching { telecomManager.defaultDialerPackage }.getOrNull() ?: return null to false
        val label = runCatching {
            val appInfo = app.packageManager.getApplicationInfo(packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
        return label to (packageName in KNOWN_RISKY_DIALER_PACKAGES)
    }

    fun runCheckNow() {
        // Riscontro visivo immediato al tap (prima ancora che WorkManager confermi lo stato):
        // l'utente sa subito che il pulsante ha reagito, invece di chiedersi se ha funzionato.
        _uiState.value = _uiState.value.copy(isCheckRunning = true, lastActionMessage = "Controllo avviato…")
        armCheckRunningTimeout()
        ScheduleCheckWorker.runNowOneOff(app)
    }

    /**
     * Dimentica lo stato "chi e' attualmente reperibile" tenuto sul device e forza un nuovo
     * tentativo di attivazione al prossimo check. Utile per far ripartire manualmente un
     * cambio turno dopo aver risolto un problema (es. cambiata l'app telefono predefinita) senza
     * dover aspettare il prossimo cambio naturale del calendario.
     */
    fun forceRecheck() {
        _uiState.value = _uiState.value.copy(isCheckRunning = true, lastActionMessage = "Nuovo tentativo forzato avviato…")
        armCheckRunningTimeout()
        app.stateRepository.clearActivation()
        // REPLACE, non KEEP: questo bottone azzera anche lo stato locale subito prima, quindi il
        // controllo DEVE ripartire per forza - con KEEP la richiesta poteva essere scartata se un
        // altro controllo era gia' in coda, lasciando la Dashboard vuota ("Nessuno").
        ScheduleCheckWorker.runForceRecheckOneOff(app)
    }

    private fun armCheckRunningTimeout() {
        checkRunningTimeout?.cancel()
        checkRunningTimeout = viewModelScope.launch {
            delay(90_000)
            _uiState.value = _uiState.value.copy(
                isCheckRunning = false,
                lastActionMessage = "Nessuna risposta entro 90s: riprova o controlla \"Ultime azioni\""
            )
            refresh()
        }
    }
}
