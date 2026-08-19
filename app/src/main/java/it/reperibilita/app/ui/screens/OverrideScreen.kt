package it.reperibilita.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.reperibilita.app.App
import it.reperibilita.app.ui.viewmodel.OverrideViewModel

@Composable
fun OverrideScreen(app: App) {
    val viewModel: OverrideViewModel = viewModel(factory = viewModelFactory {
        initializer { OverrideViewModel(app) }
    })
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Override manuale reperibilità", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Forza l'inoltro chiamata su un numero specifico, ignorando il calendario Excel " +
                "finché non lo disattivi. Utile per sostituzioni last-minute."
        )

        if (state.active) {
            Text("Override attualmente ATTIVO verso ${state.personName} (${state.number})", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { viewModel.clearOverride() }, modifier = Modifier.fillMaxWidth()) {
                Text("Disattiva override e torna al calendario")
            }
        } else {
            OutlinedTextField(
                value = state.personName,
                onValueChange = viewModel::onPersonNameChange,
                label = { Text("Nome persona") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.number,
                onValueChange = viewModel::onNumberChange,
                label = { Text("Numero di inoltro") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.applyOverride() },
                enabled = state.number.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Attiva override")
            }
        }
    }
}
