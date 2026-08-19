package it.reperibilita.app.telecom

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.reperibilita.app.ui.theme.ReperibilitaTheme

/**
 * UI minima per le chiamate gestite dall'app come telefono predefinito: risponde/riaggancia le
 * chiamate normali (incluse quelle di emergenza - vedi nota in ReperibilitaInCallService). Non
 * viene mostrata per le chiamate USSD auto-piazzate dall'automazione (si risolvono da sole).
 */
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        setContent {
            ReperibilitaTheme {
                val call by CallHolder.activeCall.collectAsState()
                val state by CallHolder.callState.collectAsState()

                LaunchedEffect(call) {
                    if (call == null) finish()
                }

                IncomingCallScreen(
                    number = call?.details?.handle?.schemeSpecificPart ?: "Numero sconosciuto",
                    state = state,
                    onAnswer = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                    onHangup = {
                        val current = call
                        if (current != null) {
                            if (current.details.state == Call.STATE_RINGING) current.reject(false, null) else current.disconnect()
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, InCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun IncomingCallScreen(number: String, state: Int?, onAnswer: () -> Unit, onHangup: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(number, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(callStateLabel(state), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state == Call.STATE_RINGING) {
                    Button(onClick = onAnswer) { Text("Rispondi") }
                }
                OutlinedButton(onClick = onHangup) { Text("Riaggancia") }
            }
        }
    }
}

private fun callStateLabel(state: Int?): String = when (state) {
    Call.STATE_RINGING -> "Chiamata in arrivo"
    Call.STATE_DIALING -> "Chiamata in corso"
    Call.STATE_ACTIVE -> "In conversazione"
    Call.STATE_DISCONNECTED -> "Terminata"
    else -> "..."
}
