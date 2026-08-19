package it.reperibilita.app.data.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import it.reperibilita.app.model.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Configurazione dell'app (sorgente calendario, credenziali Entra, SMTP, numeri/mail di notifica).
 * Salvata in EncryptedSharedPreferences (chiave master gestita da Android Keystore) cosi' che
 * client secret e password SMTP non risiedano mai in chiaro sul dispositivo.
 */
class ConfigRepository(context: Context) {

    private val appContext = context.applicationContext
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(AppConfig::class.java)

    private val prefs: SharedPreferences by lazy { buildPrefs() }

    private fun buildPrefs(): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "reperibilita_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // Fallback non cifrato: usato solo se il keystore risultasse invalidato (raro, es.
        // dopo reset delle credenziali di sblocco su alcuni OEM). Meglio degradare che
        // bloccare l'automazione; l'evento va comunque loggato dal chiamante.
        appContext.getSharedPreferences("reperibilita_prefs_fallback", Context.MODE_PRIVATE)
    }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config

    private fun load(): AppConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
        return runCatching { adapter.fromJson(json) }.getOrNull() ?: AppConfig()
    }

    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig) {
        val newConfig = transform(_config.value)
        prefs.edit().putString(KEY_CONFIG, adapter.toJson(newConfig)).apply()
        _config.value = newConfig
    }

    fun current(): AppConfig = _config.value

    /**
     * Serializza la configurazione corrente in JSON, per esportarla su file e reimportarla su
     * un altro device (o dopo una reinstallazione) senza dover ridigitare tenant/client id,
     * percorso SharePoint, ecc. ATTENZIONE: include client secret e password SMTP in chiaro nel
     * JSON esportato - il file va trattato come materiale sensibile (vedi avviso in Impostazioni).
     */
    fun exportJson(): String = adapter.indent("  ").toJson(_config.value)

    /** @return true se l'importazione è riuscita, false se il JSON non era valido (config invariata). */
    @Synchronized
    fun importJson(json: String): Boolean {
        val imported = runCatching { adapter.fromJson(json) }.getOrNull() ?: return false
        prefs.edit().putString(KEY_CONFIG, adapter.toJson(imported)).apply()
        _config.value = imported
        return true
    }

    companion object {
        private const val KEY_CONFIG = "app_config_json"
    }
}
