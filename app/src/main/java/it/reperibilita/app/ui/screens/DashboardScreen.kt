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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.reperibilita.app.App
import it.reperibilita.app.telecom.DialerRoleHelper
import it.reperibilita.app.ui.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(app: App) {
    val viewModel: DashboardViewModel = viewModel(factory = viewModelFactory {
        initializer { DashboardViewModel(app) }
    })
    val state by viewModel.uiState.collectAsState()
    val logs by viewModel.recentLogs.collectAsState()
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity

    // Toast "usa e getta": conferma il tap subito ("Controllo avviato…") e poi l'esito reale
    // quando il lavoro in background finisce ("Controllo completato"/"Controllo fallito…").
    LaunchedEffect(state.lastActionMessage) {
        state.lastActionMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }

    // Aggiorna automaticamente quando l'utente torna sulla app (es. dopo aver attivato
    // l'accessibilità o impostato il telefono predefinito dalle Impostazioni di sistema): senza
    // questo, la card "Setup guidato" sotto resterebbe visibile finche' l'utente non pensa di
    // premere "Aggiorna stato" a mano, vanificando lo scopo di renderla immediata.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
    }

    // Schermata interamente scrollabile: un messaggio di errore lungo nella Card di stato non
    // deve mai poter spingere i bottoni sotto il bordo visibile senza modo di raggiungerli.
    // La lista "Ultime azioni" e' volutamente una Column normale (non LazyColumn, max 20 righe):
    // una LazyColumn dentro un contenitore con verticalScroll causerebbe un crash per vincoli
    // di altezza infinita.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Stato reperibilità", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        if (!state.automationEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("⏸ Automazione disattivata", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nessun controllo verrà eseguito finché non la riattivi da Impostazioni → " +
                            "Automazione. L'inoltro eventualmente già attivo sulla SIM non viene toccato.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Visibile solo finche' manca qualcosa; sparisce da sola non appena entrambi i requisiti
        // sono soddisfatti. Non possiamo concedere questi due permessi automaticamente all'avvio:
        // Android richiede SEMPRE un tap esplicito dell'utente sul proprio dialog di sistema per
        // il ruolo di app Telefono predefinita e per l'accesso al servizio di Accessibilità -
        // e' una protezione di sicurezza voluta (impedisce a un'app di concedersi da sola
        // capacita' cosi' potenti), non qualcosa che si possa aggirare lato codice. Questa card
        // rende pero' il prossimo passo sempre a portata di un tap, invece di doverlo cercare nei
        // menu di sistema.
        if (!state.isAppDefaultDialer || !state.isAccessibilityEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup guidato", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "⚠ Passaggio 0 (spesso necessario prima degli altri due, essendo l'app " +
                            "installata da file APK e non dal Play Store): se premendo i pulsanti " +
                            "sotto non succede nulla o l'opzione risulta disattivata, Android sta " +
                            "probabilmente bloccando l'app per sicurezza (\"Impostazioni con " +
                            "restrizioni\"). Vai su Informazioni app, tocca i tre puntini ⋮ in alto a " +
                            "destra e scegli \"Consenti impostazioni con restrizioni\", poi torna qui " +
                            "e riprova.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apri informazioni app")
                    }
                    Text(
                        "Per funzionare, l'app ha bisogno anche di due permessi che Android richiede " +
                            "di concedere a mano (nessuna app può farlo da sola):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!state.isAppDefaultDialer) {
                        Text("• Telefono predefinito: non ancora impostato")
                        Button(
                            onClick = {
                                if (activity != null) roleLauncher.launch(DialerRoleHelper.buildRequestRoleIntent(context))
                            },
                            enabled = activity != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Imposta come telefono predefinito")
                        }
                    } else {
                        Text("✓ Telefono predefinito impostato")
                    }
                    if (!state.isAccessibilityEnabled) {
                        Text("• Verifica automatica USSD (Accessibilità): non ancora attiva")
                        Button(
                            onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Attiva servizio di accessibilità")
                        }
                    } else {
                        Text("✓ Verifica automatica USSD attiva")
                    }
                    Text(
                        "Torna qui dopo ogni passaggio: questa card si aggiorna da sola e sparisce " +
                            "quando tutto è pronto.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reperibile ora: ${state.currentPersonName ?: "Nessuno"}")
                Text("Numero attivo: ${state.currentNumber ?: "-"}")
                if (state.overrideActive) {
                    Text("⚠ Override manuale attivo", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                } else {
                    Text("Prossimo cambio: ${state.nextPersonName ?: "-"} (${state.nextStart ?: "-"})")
                }
                state.lastError?.let { Text("Errore: $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        }

        state.defaultDialerLabel?.let { dialerLabel ->
            if (state.defaultDialerRisky) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "⚠ App telefono predefinita: $dialerLabel",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Questa app è nota per non inviare correttamente i codici USSD: l'inoltro " +
                                "chiamata potrebbe non attivarsi davvero anche se l'app non segnala errori. " +
                                "Imposta \"Telefono\" (l'app di sistema) come predefinita da " +
                                "Impostazioni → App → App predefinite → App telefono.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Button(onClick = { viewModel.refresh() }, enabled = !state.isRefreshing, modifier = Modifier.fillMaxWidth()) {
            Text("Aggiorna stato")
        }
        Button(
            onClick = { viewModel.runCheckNow() },
            enabled = !state.isCheckRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isCheckRunning) "Controllo in corso…" else "Esegui controllo turno ora")
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { viewModel.forceRecheck() },
            enabled = !state.isCheckRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isCheckRunning) "Attendere…" else "Forza nuovo tentativo (ignora stato precedente)")
        }
        Text(
            "Da usare per far ripartire un cambio turno dopo aver risolto un problema (es. app " +
                "telefono predefinita). Non serve modificare il file Excel: lo stato di chi è " +
                "reperibile è tenuto sul telefono, non nella colonna Stato.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        if (state.isRefreshing || state.isCheckRunning) CircularProgressIndicator()

        HorizontalDivider()
        Text("Ultime azioni", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            logs.forEach { entry ->
                Text("[${entry.result}] ${entry.action}: ${entry.message}")
            }
        }
    }
}
