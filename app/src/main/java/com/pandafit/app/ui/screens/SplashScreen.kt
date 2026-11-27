// ============================================================================
//  Panda Fit - SplashScreen.kt
//  Pantalla de bienvenida: logo panda en cristal con glow que respira y
//  animacion de entrada suave. Dura ~2 segundos y navega al inicio sola.
// ============================================================================
package com.pandafit.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.pandafit.app.R
import com.pandafit.app.ui.components.PandaProgressRing
import com.pandafit.app.ui.theme.PandaFitTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import kotlinx.coroutines.delay

/**
 * @param onFinished se llama cuando termina la animacion (navega a Inicio).
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val tokens = PandaFitTheme.colors
    val accent = MaterialTheme.colorScheme.primary

    val infinite = rememberInfiniteTransition(label = "splash")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(900),
        label = "appear"
    )

    LaunchedEffect(Unit) {
        delay(1_900)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // v18: fondo liso del diseno deportivo oscuro.
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(28.dp)
        ) {
            // v0.0.2: el icono de la app (panda musculoso naranja) con un halo
            // naranja que respira y un anillo de carga girando alrededor.
            val naranja = androidx.compose.ui.graphics.Color(0xFFF7931E)
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .scale(pulse)
                        .background(
                            Brush.radialGradient(listOf(naranja.copy(alpha = 0.35f), androidx.compose.ui.graphics.Color.Transparent)),
                            CircleShape
                        )
                )
                PandaProgressRing(
                    progress = (spin % 360f) / 360f,
                    accent = naranja,
                    modifier = Modifier.size(244.dp),
                    strokeWidth = 5.dp
                ) { }
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.logo_panda),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(196.dp)
                        .scale(0.85f + 0.15f * appear)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(36.dp))
                )
            }

            Spacer(Modifier.height(34.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black,
                modifier = Modifier.alpha(appear)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(appear)
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.splash_loading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.7f)
            )
        }
    }
}
