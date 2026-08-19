package it.reperibilita.app.data.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// MSAL garantisce (da contratto SDK) che ogni chiamata asincrona richiami prima o poi onSuccess
// oppure onError - ma questo codice gira anche dal worker in background (nessuna Activity in
// primo piano), un contesto meno testato dall'SDK e dove problemi di IPC verso Play Services/
// broker (Authenticator/Company Portal) possono, in pratica, non risolversi mai. Senza un limite
// esplicito qui, un simile stallo bloccherebbe il controllo a tempo indeterminato - stesso tipo di
// problema gia' risolto per SMTP/USSD privilegiato/SMS (vedi i commenti li').
private const val MSAL_CALL_TIMEOUT_MS = 30_000L

/**
 * Flusso OAuth2 "delegato" (login utente interattivo) via MSAL. L'utente si autentica una volta
 * dalla schermata Impostazioni (signInInteractive); i token successivi vengono rinnovati in
 * silenzioso (acquireTokenSilent), riutilizzabile anche dal worker in background finche' il
 * refresh token resta valido. Richiede permessi Delegated su Mail.Send / Sites.ReadWrite.All e
 * consenso (admin o utente) sul tenant.
 *
 * Nota: se il worker gira quando nessun account e' mai stato collegato (o il refresh token e'
 * scaduto/revocato), getAccessToken lancia GraphAuthException e l'errore viene notificato
 * (SMS/mail) come da requisito, invece di bloccare silenziosamente l'automazione.
 */
class MsalDelegatedAuthProvider(
    private val context: Context,
    private val clientId: String,
    private val redirectUri: String,
    private val tenantId: String
) : GraphAuthProvider {

    @Volatile private var app: ISingleAccountPublicClientApplication? = null

    // Serializza l'inizializzazione MSAL: senza questo, due chiamate concorrenti a getApp() (es.
    // "Controlla stato login" e "Accedi" toccati ravvicinati, prima che la prima finisca)
    // riscrivevano ENTRAMBE lo stesso file di config temporaneo in parallelo - una poteva
    // leggerlo esattamente mentre l'altra lo stava riscrivendo, dando un errore SDK del tipo
    // "provided path does not exist" invece di un'inizializzazione pulita. Con il lock, la
    // seconda chiamata aspetta che la prima finisca e poi riusa il risultato gia' pronto
    // (app?.let{} sopra), invece di ripetere la corsa.
    private val initMutex = Mutex()

    private suspend fun getApp(): ISingleAccountPublicClientApplication {
        app?.let { return it }
        return initMutex.withLock {
            app?.let { return@withLock it } // un'altra chiamata potrebbe aver gia' finito mentre aspettavamo il lock
            withTimeout(MSAL_CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    PublicClientApplication.createSingleAccountPublicClientApplication(
                        context,
                        writeConfigFile(),
                        object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                            override fun onCreated(application: ISingleAccountPublicClientApplication) {
                                app = application
                                cont.resume(application)
                            }

                            override fun onError(exception: MsalException) {
                                cont.resumeWithException(GraphAuthException("Init MSAL fallita: ${describe(exception)}", exception))
                            }
                        }
                    )
                }
            }
        }
    }

    // MSAL richiede la config come file JSON: la generiamo a runtime da clientId/redirect/tenant
    // configurati in app (invece di una risorsa raw statica) cosi' l'utente non deve editarla a
    // mano. Schema esatto atteso da MSAL Android: le autorita' AAD single-tenant usano un oggetto
    // "audience" con "tenant_id", non un campo piatto "authority_url" (usarlo causa un errore di
    // validazione della configurazione, con conseguente fallimento dell'init).
    private fun writeConfigFile(): java.io.File {
        val json = """
            {
              "client_id": "$clientId",
              "authorization_user_agent": "DEFAULT",
              "redirect_uri": "$redirectUri",
              "account_mode": "SINGLE",
              "authorities": [
                {
                  "type": "AAD",
                  "audience": {
                    "type": "AzureADMyOrg",
                    "tenant_id": "$tenantId"
                  },
                  "default": true
                }
              ]
            }
        """.trimIndent()
        val file = java.io.File(context.cacheDir, "msal_config.json")
        file.writeText(json)
        return file
    }

    private suspend fun getCurrentAccountOrNull(): IAccount? {
        val pca = getApp()
        return withTimeout(MSAL_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                pca.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                    override fun onAccountLoaded(activeAccount: IAccount?) {
                        cont.resume(activeAccount)
                    }
                    override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                        // no-op: gestito da onAccountLoaded al primo caricamento
                    }
                    override fun onError(exception: MsalException) {
                        cont.resumeWithException(GraphAuthException("Lettura account MSAL fallita: ${describe(exception)}", exception))
                    }
                })
            }
        }
    }

    /** Da chiamare da una Activity (es. bottone "Accedi" nelle Impostazioni), non dal worker. */
    suspend fun signInInteractive(activity: Activity, scopes: List<String>): IAccount {
        val pca = getApp()
        return suspendCancellableCoroutine { cont ->
            val callback = object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    cont.resume(result.account)
                }
                override fun onError(exception: MsalException) {
                    cont.resumeWithException(GraphAuthException("Login interattivo fallito: ${describe(exception)}", exception))
                }
                override fun onCancel() {
                    cont.resumeWithException(GraphAuthException("Login interattivo annullato dall'utente"))
                }
            }
            pca.signIn(
                SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(scopes)
                    .withCallback(callback)
                    .build()
            )
        }
    }

    suspend fun signOut() {
        val pca = getApp()
        suspendCancellableCoroutine<Unit> { cont ->
            pca.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() = cont.resume(Unit)
                override fun onError(exception: MsalException) =
                    cont.resumeWithException(GraphAuthException("Logout MSAL fallito: ${describe(exception)}", exception))
            })
        }
    }

    suspend fun isSignedIn(): Boolean = getCurrentAccountOrNull() != null

    /** Username (UPN) dell'account attualmente collegato, o null se nessuno - usato dalla UI per mostrare "Collegato come..." e permettere di scollegarsi prima di riprovare il login. */
    suspend fun currentAccountUsername(): String? = getCurrentAccountOrNull()?.username

    /**
     * Estrae codice errore + messaggio da un'eccezione MSAL. Senza questo, il messaggio mostrato
     * in Impostazioni resta un testo generico fisso ("Init MSAL fallita" ecc.) sempre identico
     * indipendentemente dalla causa reale, rendendo impossibile capire cosa aggiustare.
     */
    private fun describe(exception: MsalException): String =
        "[${exception.errorCode}] ${exception.message}"

    override suspend fun getAccessToken(scopes: List<String>): String {
        val pca = getApp()
        val account = getCurrentAccountOrNull()
            ?: throw GraphAuthException("Nessun account collegato: eseguire il login da Impostazioni")

        return withTimeout(MSAL_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        cont.resume(authenticationResult.accessToken)
                    }
                    override fun onError(exception: MsalException) {
                        cont.resumeWithException(
                            GraphAuthException(
                                "Rinnovo token silenzioso fallito (potrebbe servire un nuovo login interattivo): ${describe(exception)}",
                                exception
                            )
                        )
                    }
                }
                pca.acquireTokenSilentAsync(
                    AcquireTokenSilentParameters.Builder()
                        .withScopes(scopes)
                        .forAccount(account)
                        .fromAuthority(account.authority)
                        .withCallback(callback)
                        .build()
                )
            }
        }
    }
}
