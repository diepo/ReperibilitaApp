package it.reperibilita.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.reperibilita.app.App
import it.reperibilita.app.worker.ScheduleCheckWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OverrideUiState(
    val active: Boolean = false,
    val personName: String = "",
    val number: String = ""
)

/**
 * Consente di forzare a mano la reperibilità su un numero specifico (bypassando il calendario),
 * come richiesto per i casi eccezionali. Disattivando l'override, il prossimo controllo del
 * worker torna automaticamente a seguire il calendario Excel.
 */
class OverrideViewModel(private val app: App) : ViewModel() {

    private val _uiState = MutableStateFlow(loadFromConfig())
    val uiState: StateFlow<OverrideUiState> = _uiState

    private fun loadFromConfig(): OverrideUiState {
        val c = app.configRepository.current()
        return OverrideUiState(c.manualOverrideActive, c.manualOverridePersonName, c.manualOverrideNumber)
    }

    fun onPersonNameChange(value: String) { _uiState.value = _uiState.value.copy(personName = value) }
    fun onNumberChange(value: String) { _uiState.value = _uiState.value.copy(number = value) }

    fun applyOverride() {
        val current = _uiState.value
        app.configRepository.update {
            it.copy(
                manualOverrideActive = true,
                manualOverridePersonName = current.personName,
                manualOverrideNumber = current.number
            )
        }
        _uiState.value = current.copy(active = true)
        viewModelScope.launch { ScheduleCheckWorker.runNowOneOff(app) }
    }

    fun clearOverride() {
        app.configRepository.update { it.copy(manualOverrideActive = false) }
        _uiState.value = _uiState.value.copy(active = false)
        viewModelScope.launch { ScheduleCheckWorker.runNowOneOff(app) }
    }
}
