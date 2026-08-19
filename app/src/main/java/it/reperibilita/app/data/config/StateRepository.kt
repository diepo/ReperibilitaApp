package it.reperibilita.app.data.config

import android.content.Context

/**
 * Stato operativo (non credenziali) persistito tra un check e l'altro del worker: chi e'
 * attualmente reperibile (per rilevare i cambi turno) e quando e' stata inviata l'ultima mail
 * di riepilogo giornaliera. Separato da ConfigRepository (che contiene solo impostazioni/segreti).
 */
class StateRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("reperibilita_state", Context.MODE_PRIVATE)

    var lastActivatedRowIndex: Int
        get() = prefs.getInt(KEY_ROW_INDEX, -1)
        set(value) = prefs.edit().putInt(KEY_ROW_INDEX, value).apply()

    var lastActivatedPersonName: String?
        get() = prefs.getString(KEY_PERSON_NAME, null)
        set(value) = prefs.edit().putString(KEY_PERSON_NAME, value).apply()

    var lastActivatedNumber: String?
        get() = prefs.getString(KEY_NUMBER, null)
        set(value) = prefs.edit().putString(KEY_NUMBER, value).apply()

    var lastSummaryEpochDay: Long
        get() = prefs.getLong(KEY_LAST_SUMMARY_DAY, -1)
        set(value) = prefs.edit().putLong(KEY_LAST_SUMMARY_DAY, value).apply()

    fun clearActivation() {
        prefs.edit()
            .remove(KEY_ROW_INDEX)
            .remove(KEY_PERSON_NAME)
            .remove(KEY_NUMBER)
            .apply()
    }

    companion object {
        private const val KEY_ROW_INDEX = "last_activated_row_index"
        private const val KEY_PERSON_NAME = "last_activated_person_name"
        private const val KEY_NUMBER = "last_activated_number"
        private const val KEY_LAST_SUMMARY_DAY = "last_summary_epoch_day"
    }
}
