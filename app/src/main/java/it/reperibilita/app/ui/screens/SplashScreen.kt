package it.reperibilita.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.reperibilita.app.BuildConfig
import it.reperibilita.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mostrata una volta ad ogni avvio a freddo dell'app, prima della Dashboard. Il logo (icona di
 * lancio) entra con un piccolo "rimbalzo" (scale+fade) invece di comparire di colpo - puramente
 * estetico, ma aiuta a distinguere un avvio "vero" da un semplice cambio di schermata. La
 * SplashScreen API di sistema (vedi MainActivity/Theme.Reperibilita.Starting) copre solo
 * l'istante prima che Compose sia pronto: questo composable è quello che mostra davvero nome
 * app, versione e produttore, cosa che l'API di sistema non permette di personalizzare.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, animationSpec = tween(durationMillis = 550, easing = EaseOutBack)) }
        launch { alpha.animateTo(1f, animationSpec = tween(durationMillis = 400)) }
        delay(1600)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(0.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.alpha(alpha.value)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale.value)
                    .background(colorResource(R.color.primary), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // ic_launcher_foreground e' un semplice <vector> (a differenza di ic_launcher/
                // ic_launcher_round, che sono <adaptive-icon> a due livelli): painterResource()
                // di Compose non supporta le adaptive-icon e va in crash se usata direttamente su
                // quelle - da qui lo sfondo colorato disegnato a mano dietro al solo livello
                // "foreground", per ottenere lo stesso effetto visivo in modo sicuro.
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
            }
            Text(
                "Reperibilità",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
