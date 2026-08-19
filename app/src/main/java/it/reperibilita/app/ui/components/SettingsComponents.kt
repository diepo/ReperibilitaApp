package it.reperibilita.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * Elenco verticale di opzioni a larghezza piena. Con etichette lunghe (es. "App-only (client
 * secret)" / "Login utente delegato") una Row orizzontale senza scroll/weight va in overflow e su
 * schermi stretti forza Compose a spezzare il testo carattere per carattere: uno stack verticale
 * evita del tutto il problema, a qualunque numero/lunghezza di opzioni.
 */
@Composable
fun <T> OptionSelector(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                if (value == selected) {
                    Button(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
                }
            }
        }
    }
}
