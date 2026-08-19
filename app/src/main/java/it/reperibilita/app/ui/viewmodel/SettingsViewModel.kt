package it.reperibilita.app.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.reperibilita.app.App
import it.reperibilita.app.model.AppConfig
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult
import it.reperibilita.app.worker.ScheduleCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val app: App) : ViewModel() {

    private val _config = MutableStateFlow(app.configRepository.current())
    val config: StateFlow<AppConfig> = _config

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage

    fun update(transform: (AppConfig) -> AppConfig) {
        _config.value = transform(_config.value)
    }

    fun save() {
        app.configRepository.update { _config.value }
        ScheduleCheckWorker.ensureScheduled(app, _config.value.checkIntervalMinutes)
        _saveMessage.value = "Impostazioni salvate"
    }

    fun setLocalFileUri(uri: String) {
        update { it.copy(localFileUri = uri) }
    }

    /**
     * A differenza degli altri campi di questa schermata (che restano solo in memoria finche'
     * l'utente non preme "Salva impostazioni"), l'interruttore generale dell'automazione si
     * salva SUBITO al tocco. E' un interruttore di emergenza: un utente che lo spegne si aspetta
     * un effetto immediato, non un passaggio in piu' da ricordare - bug osservato in pratica
     * (spento in UI ma mai salvato davvero, il worker in background continuava a girare col
     * valore vecchio e a mandare notifiche di errore).
     */
    fun setAutomationEnabled(enabled: Boolean) {
        update { it.copy(automationEnabled = enabled) }
        save()
    }

    private val _delegatedAccountUsername = MutableStateFlow<String?>(null)
    val delegatedAccountUsername: StateFlow<String?> = _delegatedAccountUsername

    /**
     * Il login MSAL e' in modalita' "single account": chiamare signIn() mentre un account e'
     * gia' collegato viene rifiutato dall'SDK (osservato in pratica: "c'e' gia' un utente
     * loggato"). Prima non c'era nessun modo di vedere chi fosse collegato ne' di scollegarsi
     * per riprovare - questo va chiamato quando si apre la sezione Entra ID, cosi' la UI puo'
     * mostrare "Collegato come..." + un pulsante "Esci" invece del solo "Accedi", che altrimenti
     * fallirebbe sempre in queste condizioni.
     */
    fun refreshDelegatedAccountStatus() {
        viewModelScope.launch {
            _delegatedAccountUsername.value = runCatching {
                app.serviceFactory.getMsalProvider().currentAccountUsername()
            }.getOrNull()
        }
    }

    fun signInDelegated(activity: Activity) {
        val entra = _config.value.entraApp
        if (entra.tenantId.isBlank() || entra.clientId.isBlank() || entra.redirectUri.isBlank()) {
            // Validato PRIMA di toccare l'SDK MSAL: con questi campi vuoti l'inizializzazione
            // MSAL fallisce comunque, ma con un errore SDK poco chiaro (o peggio, secondo il
            // collega che lo testava, un crash) invece di un messaggio comprensibile.
            _saveMessage.value = "Compila Tenant ID, Client ID e Redirect URI prima di accedere"
            return
        }
        viewModelScope.launch {
            runCatching {
                app.serviceFactory.getMsalProvider().signInInteractive(
                    activity,
                    listOf("Mail.Send", "Sites.ReadWrite.All", "Files.ReadWrite.All")
                )
                _saveMessage.value = "Login Entra completato"
                refreshDelegatedAccountStatus()
            }.onFailure { error ->
                _saveMessage.value = "Login Entra fallito: ${error.message}"
                // Loggato anche in modo persistente (tab Log): il Toast sparisce in pochi
                // secondi, il dettaglio dell'errore MSAL puo' essere lungo e serve poterlo
                // rileggere con calma.
                viewModelScope.launch {
                    app.logRepository.log(
                        LogAction.ERROR,
                        LogResult.ERROR,
                        "Login Entra delegato fallito: ${error.message}",
                        detail = error.stackTraceToString()
                    )
                }
            }
        }
    }

    fun signOutDelegated() {
        viewModelScope.launch {
            runCatching { app.serviceFactory.getMsalProvider().signOut() }
                .onSuccess {
                    _saveMessage.value = "Disconnesso da Microsoft"
                    refreshDelegatedAccountStatus()
                }
                .onFailure { _saveMessage.value = "Logout fallito: ${it.message}" }
        }
    }

    fun clearMessage() { _saveMessage.value = null }

    /**
     * Esporta/importa la configurazione come file JSON, per non dover ridigitare tenant/client
     * id, percorso SharePoint, credenziali SMTP ecc. su un nuovo device o dopo una
     * reinstallazione. ATTENZIONE: il file contiene client secret e password SMTP in chiaro,
     * va trattato come materiale sensibile (cancellato dopo l'uso, non condiviso via canali non
     * sicuri) - avviso mostrato anche in Impostazioni.
     */
    fun exportConfig(context: Context, uri: Uri) {
        viewModelScope.launch {
            val json = app.configRepository.exportJson()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Impossibile aprire il file in scrittura")
                }.isSuccess
            }
            _saveMessage.value = if (ok) "Configurazione esportata" else "Esportazione configurazione fallita"
        }
    }

    fun importConfig(context: Context, uri: Uri) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            val imported = json != null && app.configRepository.importJson(json)
            if (imported) {
                _config.value = app.configRepository.current()
                ScheduleCheckWorker.ensureScheduled(app, _config.value.checkIntervalMinutes)
                _saveMessage.value = "Configurazione importata"
            } else {
                _saveMessage.value = "Importazione configurazione fallita: file non valido"
            }
        }
    }
}
