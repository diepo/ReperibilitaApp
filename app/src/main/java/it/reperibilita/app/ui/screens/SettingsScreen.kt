package it.reperibilita.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.reperibilita.app.App
import it.reperibilita.app.model.GraphAuthMode
import it.reperibilita.app.model.MailSendMode
import it.reperibilita.app.model.ScheduleSourceMode
import it.reperibilita.app.telecom.DialerRoleHelper
import it.reperibilita.app.ui.components.OptionSelector
import it.reperibilita.app.ui.components.SectionCard
import it.reperibilita.app.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: App) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory {
        initializer { SettingsViewModel(app) }
    })
    val config by viewModel.config.collectAsState()
    val message by viewModel.saveMessage.collectAsState()
    val context = LocalContext.current

    // Toast invece di un testo in cima alla pagina: cosi' e' visibile anche se l'utente ha
    // scorso in basso per premere "Salva impostazioni" (bug segnalato: il testo in cima non si
    // vedeva più dopo lo scroll).
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportConfig(context, uri)
    }
    val importConfigLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importConfig(context, uri)
    }

    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setLocalFileUri(uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Sorgente calendario") {
            OptionSelector(
                label = "Origine file Excel",
                options = listOf(ScheduleSourceMode.LOCAL_FILE to "File locale", ScheduleSourceMode.SHAREPOINT to "SharePoint"),
                selected = config.scheduleSourceMode,
                onSelect = { mode -> viewModel.update { it.copy(scheduleSourceMode = mode) } }
            )
            if (config.scheduleSourceMode == ScheduleSourceMode.LOCAL_FILE) {
                Text("File selezionato: ${config.localFileUri ?: "nessuno"}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pickFileLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }) {
                    Text("Seleziona file .xlsx locale")
                }
            } else {
                Text(
                    "Consigliato con autenticazione app-only: URL sito + percorso file. Con il " +
                        "solo link di condivisione, l'accesso app-only puo' essere rifiutato da " +
                        "Graph se il link e' condiviso con persone specifiche.",
                    style = MaterialTheme.typography.bodySmall
                )
                LabeledField("URL sito SharePoint", config.sharePoint.siteUrl) {
                    viewModel.update { c -> c.copy(sharePoint = c.sharePoint.copy(siteUrl = it)) }
                }
                LabeledField("Percorso file nella libreria documenti", config.sharePoint.driveItemPath) {
                    viewModel.update { c -> c.copy(sharePoint = c.sharePoint.copy(driveItemPath = it)) }
                }
                Text(
                    "Percorso relativo DENTRO la libreria \"Documenti condivisi\": non ripetere " +
                        "il nome della libreria. Se il file e' nella radice: /NomeFile.xlsx. Se e' " +
                        "in una sottocartella: /Sottocartella/NomeFile.xlsx.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "In alternativa (funziona meglio con login utente delegato): link copiato con " +
                        "\"Condividi\" -> \"Copia collegamento\".",
                    style = MaterialTheme.typography.bodySmall
                )
                LabeledField("Link di condivisione file SharePoint", config.sharePoint.sharingUrl) {
                    viewModel.update { c -> c.copy(sharePoint = c.sharePoint.copy(sharingUrl = it)) }
                }
                LabeledField("Nome foglio", config.sharePoint.worksheetName) {
                    viewModel.update { c -> c.copy(sharePoint = c.sharePoint.copy(worksheetName = it)) }
                }
            }
        }

        SectionCard("Autenticazione Entra ID (Microsoft Graph)") {
            OptionSelector(
                label = "Metodo",
                options = listOf(
                    GraphAuthMode.CLIENT_CREDENTIALS to "App-only (client secret)",
                    GraphAuthMode.DELEGATED_INTERACTIVE to "Login utente delegato"
                ),
                selected = config.graphAuthMode,
                onSelect = { mode -> viewModel.update { it.copy(graphAuthMode = mode) } }
            )
            LabeledField("Tenant ID", config.entraApp.tenantId) {
                viewModel.update { c -> c.copy(entraApp = c.entraApp.copy(tenantId = it)) }
            }
            LabeledField("Client ID (Application ID)", config.entraApp.clientId) {
                viewModel.update { c -> c.copy(entraApp = c.entraApp.copy(clientId = it)) }
            }
            if (config.graphAuthMode == GraphAuthMode.CLIENT_CREDENTIALS) {
                LabeledField("Client secret", config.entraApp.clientSecret, isSecret = true) {
                    viewModel.update { c -> c.copy(entraApp = c.entraApp.copy(clientSecret = it)) }
                }
            } else {
                LabeledField("Redirect URI (msauth://...)", config.entraApp.redirectUri) {
                    viewModel.update { c -> c.copy(entraApp = c.entraApp.copy(redirectUri = it)) }
                }
                val accountUsername by viewModel.delegatedAccountUsername.collectAsState()
                // NON automatico (niente LaunchedEffect): controllare lo stato account richiede
                // di inizializzare l'SDK MSAL, e farlo incondizionatamente ad ogni apertura di
                // questa sezione si e' rivelato instabile in pratica su alcuni device (crash
                // immediato osservato). Meglio un pulsante esplicito, stesso approccio gia' usato
                // per lo stato del servizio di accessibilita' qui sotto.
                val activity = context as? androidx.activity.ComponentActivity
                OutlinedButton(
                    onClick = { viewModel.refreshDelegatedAccountStatus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Controlla stato login Microsoft")
                }
                if (accountUsername != null) {
                    // Il login MSAL e' "single account": provare ad accedere di nuovo mentre un
                    // account e' gia' collegato viene rifiutato dall'SDK (o peggio) - mostrare
                    // qui chi e' collegato ed "Esci" evita di riprovare "Accedi" alla cieca.
                    Text("✓ Collegato come: $accountUsername", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { viewModel.signOutDelegated() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Esci (per cambiare account)")
                    }
                } else {
                    Button(onClick = { activity?.let { viewModel.signInDelegated(it) } }, enabled = activity != null) {
                        Text("Accedi con Microsoft (login interattivo)")
                    }
                }
            }
            LabeledField("Casella mittente mail (UPN)", config.entraApp.graphSenderUpn) {
                viewModel.update { c -> c.copy(entraApp = c.entraApp.copy(graphSenderUpn = it)) }
            }
        }

        SectionCard("Invio email") {
            OptionSelector(
                label = "Metodo",
                options = listOf(
                    MailSendMode.GRAPH_API to "Microsoft Graph",
                    MailSendMode.SMTP to "SMTP",
                    MailSendMode.DISABLED to "Disabilitato"
                ),
                selected = config.mailSendMode,
                onSelect = { mode -> viewModel.update { it.copy(mailSendMode = mode) } }
            )
            if (config.mailSendMode == MailSendMode.SMTP) {
                LabeledField("Host SMTP", config.smtp.host) { viewModel.update { c -> c.copy(smtp = c.smtp.copy(host = it)) } }
                LabeledField("Porta", config.smtp.port.toString()) {
                    viewModel.update { c -> c.copy(smtp = c.smtp.copy(port = it.toIntOrNull() ?: c.smtp.port)) }
                }
                LabeledField("Utente", config.smtp.username) { viewModel.update { c -> c.copy(smtp = c.smtp.copy(username = it)) } }
                LabeledField("Password", config.smtp.password, isSecret = true) {
                    viewModel.update { c -> c.copy(smtp = c.smtp.copy(password = it)) }
                }
                LabeledField("Mittente (From)", config.smtp.fromAddress) {
                    viewModel.update { c -> c.copy(smtp = c.smtp.copy(fromAddress = it)) }
                }
                ToggleRow("Usa STARTTLS", config.smtp.useStartTls) {
                    viewModel.update { c -> c.copy(smtp = c.smtp.copy(useStartTls = it)) }
                }
            }
        }

        SectionCard("Notifiche") {
            LabeledField("Numero SMS per errori (numero di servizio)", config.notifications.serviceErrorPhoneNumber) {
                viewModel.update { c -> c.copy(notifications = c.notifications.copy(serviceErrorPhoneNumber = it)) }
            }
            LabeledField("Mail informative (separate da virgola)", config.notifications.infoEmailRecipients.joinToString(",")) {
                viewModel.update { c -> c.copy(notifications = c.notifications.copy(infoEmailRecipients = splitEmails(it))) }
            }
            LabeledField("Mail riepilogo giornaliero (separate da virgola)", config.notifications.summaryEmailRecipients.joinToString(",")) {
                viewModel.update { c -> c.copy(notifications = c.notifications.copy(summaryEmailRecipients = splitEmails(it))) }
            }
            LabeledField("Mail di errore (separate da virgola)", config.notifications.errorEmailRecipients.joinToString(",")) {
                viewModel.update { c -> c.copy(notifications = c.notifications.copy(errorEmailRecipients = splitEmails(it))) }
            }
        }

        SectionCard("Automazione") {
            ToggleRow("Automazione attiva", config.automationEnabled) {
                // setAutomationEnabled salva subito (non serve premere "Salva impostazioni"):
                // e' un interruttore di emergenza, deve avere effetto immediato.
                viewModel.setAutomationEnabled(it)
            }
            if (!config.automationEnabled) {
                Text(
                    "⚠ Automazione disattivata: nessun controllo verrà eseguito (né periodico né " +
                        "manuale), quindi nessun inoltro, SMS, mail o scrittura su Excel. L'inoltro " +
                        "eventualmente già attivo sulla SIM NON viene toccato: resta impostato " +
                        "com'era finché non lo disattivi tu manualmente o riattivi l'automazione.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            LabeledField("Intervallo controllo di sicurezza (minuti, minimo 15)", config.checkIntervalMinutes.toString()) {
                viewModel.update { c -> c.copy(checkIntervalMinutes = it.toIntOrNull() ?: c.checkIntervalMinutes) }
            }
            Text(
                "Il cambio turno vero e proprio parte puntuale da solo, esattamente all'orario " +
                    "scritto nel calendario (funziona con qualunque durata di turno, non solo " +
                    "settimanale). Questo intervallo è solo una rete di sicurezza in caso quel " +
                    "meccanismo si perda per qualche motivo: default una volta a settimana.",
                style = MaterialTheme.typography.bodySmall
            )
            ToggleRow("Scrivi lo stato sul file Excel sorgente", config.writeBackToExcel) {
                viewModel.update { c -> c.copy(writeBackToExcel = it) }
            }
            ToggleRow("Usa USSD privilegiato se disponibile (altrimenti fallback dialer)", config.usePrivilegedUssd) {
                viewModel.update { c -> c.copy(usePrivilegedUssd = it) }
            }
            LabeledField("Codice USSD attivazione (usa {number})", config.ussdActivateTemplate) {
                viewModel.update { c -> c.copy(ussdActivateTemplate = it) }
            }
            LabeledField("Codice USSD disattivazione", config.ussdDeactivateCode) {
                viewModel.update { c -> c.copy(ussdDeactivateCode = it) }
            }
            LabeledField("Codice USSD interrogazione stato (verifica post-attivazione)", config.ussdStatusQueryCode) {
                viewModel.update { c -> c.copy(ussdStatusQueryCode = it) }
            }
            Text(
                "La verifica post-attivazione (rilettura dello stato dalla rete prima di scrivere " +
                    "\"ATTIVATO\" su Excel) funziona solo con USSD privilegiato o con questa app " +
                    "impostata come telefono predefinito (vedi sotto). Con il fallback dialer " +
                    "l'app scrive comunque un esito, ma etichettato come da verificare manualmente.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard("Telefono predefinito") {
            var isDefaultDialer by remember { mutableStateOf(DialerRoleHelper.isDefaultDialer(context)) }
            val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                isDefaultDialer = DialerRoleHelper.isDefaultDialer(context)
            }
            val activity = context as? androidx.activity.ComponentActivity

            Text(
                "Alternativa a MODIFY_PHONE_STATE per inviare USSD senza root e senza conferma " +
                    "manuale: l'app diventa il telefono predefinito del device e piazza i comandi " +
                    "direttamente. Attenzione: da quel momento l'app gestisce TUTTE le chiamate del " +
                    "telefono, incluse quelle di emergenza - da usare solo su un device dedicato " +
                    "esclusivamente alla reperibilità, mai su un telefono personale.",
                style = MaterialTheme.typography.bodySmall
            )
            if (isDefaultDialer) {
                Text("✓ Reperibilità è già il telefono predefinito", color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Telefono predefinito attuale: non Reperibilità")
            }
            Button(
                onClick = {
                    val intent = DialerRoleHelper.buildRequestRoleIntent(context)
                    if (activity != null) roleLauncher.launch(intent)
                },
                enabled = !isDefaultDialer && activity != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Imposta Reperibilità come telefono predefinito")
            }
        }

        SectionCard("Verifica automatica esito USSD (Accessibilità)") {
            var accessibilityEnabled by remember {
                mutableStateOf(it.reperibilita.app.telecom.UssdAccessibilityBridge.isServiceEnabled(context))
            }
            Text(
                "Facoltativo, ma consigliato: legge il testo del popup di conferma che Android " +
                    "mostra dopo un comando USSD (es. \"Inoltro attivato verso...\"), per verificare " +
                    "automaticamente l'esito quando il solo dialer predefinito non basta (dipende dal " +
                    "device). Non clicca né modifica nulla, legge solo il testo per il log.",
                style = MaterialTheme.typography.bodySmall
            )
            if (accessibilityEnabled) {
                Text("✓ Servizio di accessibilità attivo", color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Servizio di accessibilità non attivo")
            }
            Button(
                onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apri Impostazioni Accessibilità")
            }
            OutlinedButton(
                onClick = {
                    accessibilityEnabled = it.reperibilita.app.telecom.UssdAccessibilityBridge.isServiceEnabled(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Aggiorna stato")
            }
            Text(
                "Dopo aver aperto le Impostazioni, cerca \"Reperibilità\" nell'elenco dei servizi di " +
                    "accessibilità e attivalo, poi torna qui e premi \"Aggiorna stato\".",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "⚠ Importante: da Android 13 in poi, per le app installate fuori dal Play Store " +
                    "(come questa, installata via file APK), l'interruttore di accessibilità viene " +
                    "AUTOMATICAMENTE rimesso su \"non attivo\" ogni volta che l'app viene reinstallata " +
                    "o aggiornata con un nuovo file APK (misura di sicurezza del sistema, non un bug " +
                    "dell'app). Se dopo un aggiornamento risulta di nuovo disattivato, vai su " +
                    "Impostazioni → App → Reperibilità → menu ⋮ in alto → \"Consenti impostazioni con " +
                    "restrizioni\", poi riattivalo di nuovo da Impostazioni Accessibilità.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "Se invece si disattiva anche senza reinstallare l'app (es. dopo un semplice riavvio " +
                    "o \"Forza arresto\"), la causa più probabile è la gestione batteria/autostart del " +
                    "produttore del device: cerca nelle impostazioni del telefono qualcosa come " +
                    "\"Gestione batteria\", \"Avvio automatico\" o \"App protette\" e assicurati che " +
                    "Reperibilità sia esclusa dalle ottimizzazioni e autorizzata all'avvio automatico.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard("Composizione manuale") {
            var manualNumber by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var resultDialog by remember { mutableStateOf<String?>(null) }
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
            val app2 = context.applicationContext as App

            // Ogni composizione manuale, indipendentemente dall'esito, viene sempre loggata qui -
            // e' il modo piu' diretto per capire se il servizio di accessibilita' sta davvero
            // catturando qualcosa oppure no (prima di questo fix i pulsanti qui sotto non
            // registravano MAI nulla nei log, perche' chiamavano placeManualCall() nudo senza
            // avviare la cattura: vedi DialerRoleHelper.placeManualCallWithCapture).
            fun dial(code: String) {
                if (busy) return
                busy = true
                coroutineScope.launch {
                    val outcome = runCatching {
                        DialerRoleHelper.placeManualCallWithCapture(context, code)
                    }
                    busy = false
                    val outcomeMessage = outcome.fold(
                        onSuccess = { captured ->
                            captured?.let { "Risposta catturata: $it" }
                                ?: "Comando inviato, nessuna risposta catturata entro 20s (controlla il popup a schermo e se il servizio di accessibilità è davvero attivo)"
                        },
                        onFailure = { "Comando fallito: ${it.message}" }
                    )
                    app2.logRepository.log(
                        it.reperibilita.app.model.LogAction.CALL_FORWARDING_SET,
                        if (outcome.isFailure) it.reperibilita.app.model.LogResult.ERROR else it.reperibilita.app.model.LogResult.OK,
                        "[manuale] $code -> $outcomeMessage"
                    )
                    resultDialog = outcomeMessage
                }
            }

            resultDialog?.let { msg ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { resultDialog = null },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { resultDialog = null }) { Text("OK") }
                    },
                    title = { Text("Esito comando") },
                    text = { Text(msg) }
                )
            }

            Text(
                "Per test e verifiche manuali (es. controllare/ripristinare lo stato dell'inoltro " +
                    "senza aspettare l'automazione). Dato che Reperibilità non ha un vero tastierino " +
                    "quando è impostata come telefono predefinito, componi qui.",
                style = MaterialTheme.typography.bodySmall
            )
            LabeledField("Numero o codice (es. ##21#)", manualNumber) { manualNumber = it }
            Button(
                onClick = { dial(manualNumber) },
                enabled = manualNumber.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Composizione in corso…" else "Chiama / componi")
            }

            Text("Scorciatoie dai codici configurati:", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { dial(config.ussdDeactivateCode) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Attendere…" else "Disattiva inoltro (${config.ussdDeactivateCode})")
            }
            OutlinedButton(
                onClick = { dial(config.ussdStatusQueryCode) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Attendere…" else "Interroga stato inoltro (${config.ussdStatusQueryCode})")
            }
        }

        Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) {
            Text("Salva impostazioni")
        }

        SectionCard("Backup configurazione") {
            Text(
                "Esporta tutte le impostazioni (tenant/client id, percorso SharePoint, credenziali " +
                    "ecc.) in un file, per non doverle ridigitare su un altro device o dopo una " +
                    "reinstallazione.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "⚠ Il file contiene client secret e password SMTP in CHIARO: trattalo come " +
                    "materiale sensibile, cancellalo dopo l'uso e non condividerlo via canali non sicuri.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Button(
                onClick = { exportConfigLauncher.launch("reperibilita_config.json") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Esporta configurazione su file")
            }
            OutlinedButton(
                onClick = { importConfigLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Importa configurazione da file")
            }
        }

        SectionCard("Informazioni") {
            Text("Versione: ${it.reperibilita.app.BuildConfig.VERSION_NAME} (build ${it.reperibilita.app.BuildConfig.VERSION_CODE})")
            Text(
                if (it.reperibilita.app.BuildConfig.DEBUG) "Build di debug" else "Build di release",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun splitEmails(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun LabeledField(label: String, value: String, isSecret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isSecret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
