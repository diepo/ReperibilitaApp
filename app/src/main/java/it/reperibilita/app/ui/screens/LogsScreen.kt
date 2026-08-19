package it.reperibilita.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.reperibilita.app.App
import it.reperibilita.app.model.LogResult
import it.reperibilita.app.ui.viewmodel.LogsViewModel
import java.time.Instant
import java.time.ZoneId

@Composable
fun LogsScreen(app: App) {
    val viewModel: LogsViewModel = viewModel(factory = viewModelFactory {
        initializer { LogsViewModel(app) }
    })
    val logs by viewModel.logs.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(exportMessage) {
        exportMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.exportToFile(context, uri)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Log azioni", style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(viewModel.formatAsText()))
                    android.widget.Toast.makeText(context, "Log copiati negli appunti", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Text("Copia")
            }
            OutlinedButton(
                onClick = { exportLauncher.launch("reperibilita_log.txt") },
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Text("Esporta su file")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(logs) { entry ->
                val ts = Instant.ofEpochMilli(entry.timestampEpochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val color = when (entry.result) {
                    LogResult.ERROR -> MaterialTheme.colorScheme.error
                    LogResult.WARNING -> MaterialTheme.colorScheme.tertiary
                    LogResult.OK -> MaterialTheme.colorScheme.onSurface
                }
                Card(Modifier.padding(vertical = 2.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("$ts — ${entry.action} [${entry.result}]", color = color)
                        Text(entry.message)
                        entry.personName?.let { Text("Persona: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
