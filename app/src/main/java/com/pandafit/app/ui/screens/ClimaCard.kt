// ============================================================================
//  Panda Fit - ClimaCard.kt
//  0.0.3: tarjeta del CLIMA en Inicio: ciudad, temperatura grande, estado del
//  cielo con icono animado, max/min, sensacion, viento, humedad y un consejo
//  para entrenar hoy. Tocar = actualizar. Sin permiso: boton para activarlo.
// ============================================================================
package com.pandafit.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pandafit.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandafit.app.data.Cielo
import com.pandafit.app.data.Clima
import com.pandafit.app.data.ClimaActual
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.theme.PandaFitTheme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SOL = Color(0xFFFFC53D)
private val LUNA = Color(0xFFE6E9F5)
private val NUBE = Color(0xFFDCE3EE)
private val NUBE_OSC = Color(0xFF9AA6B8)
private val GOTA = Color(0xFF5B9CFF)
private val RAYO = Color(0xFFFFD84D)

@Composable
fun ClimaCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clima by remember { mutableStateOf(Clima.guardado(context)) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var permiso by remember { mutableStateOf(Clima.tienePermiso(context)) }
    var intento by remember { mutableIntStateOf(0) }

    fun actualizar(forzar: Boolean) {
        if (cargando) return
        cargando = true
        scope.launch {
            runCatching { Clima.obtener(context, forzar) }
                .onSuccess { clima = it; error = null }
                .onFailure { error = (it as? Clima.ClimaError)?.let { e -> context.getString(e.res) } ?: context.getString(R.string.wx_err_generic) }
            cargando = false
        }
    }

    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permiso = r.values.any { it }
        if (permiso) intento++
    }
    LaunchedEffect(permiso, intento) { if (permiso) actualizar(forzar = false) }

    val tokens = PandaFitTheme.colors
    PandaGlassCard(
        modifier = Modifier.fillMaxWidth().clickable(enabled = permiso) { actualizar(forzar = true) },
        cornerRadius = 24.dp, glow = 0f, padding = 16.dp
    ) {
        val c = clima
        when {
            !permiso && c == null -> SinPermiso {
                pedir.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            c == null -> Text(
                if (cargando) stringResource(R.string.wx_searching) else (error ?: stringResource(R.string.wx_tap_to_see)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PinUbicacion(tokens.distance)
                            Spacer(Modifier.width(4.dp))
                            Text(c.ciudad, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${c.temp}°", fontSize = 52.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.padding(bottom = 10.dp)) {
                                Text(stringResource(Clima.descripcionRes(c.codigo, c.esDeDia)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.wx_max_min, c.maxima, c.minima), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    IconoClima(c.cielo, c.esDeDia, Modifier.size(86.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dato(stringResource(R.string.wx_feels), "${c.sensacion}°", tokens.calories, Modifier.weight(1f))
                    Dato(stringResource(R.string.wx_wind), "${c.vientoKmh} km/h", tokens.distance, Modifier.weight(1f))
                    Dato(stringResource(R.string.wx_humidity), "${c.humedad} %", tokens.spo2, Modifier.weight(1f))
                    Dato(stringResource(R.string.wx_rain_chance), "${c.probLluvia} %", GOTA, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    Clima.consejoRes(c).let { (res, n) -> if (n != null) stringResource(res, n) else stringResource(res) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
                Spacer(Modifier.height(6.dp))
                val minutos = ((System.currentTimeMillis() - c.obtenidoEn) / 60_000).toInt()
                Text(
                    when {
                        cargando -> stringResource(R.string.wx_updating)
                        // 0.1.6: se dice el motivo real (antes siempre "Sin conexión").
                        error != null -> stringResource(R.string.wx_update_failed, minutos, error ?: "")
                        minutos < 1 -> stringResource(R.string.wx_updated_now)
                        else -> stringResource(R.string.wx_updated_ago, minutos)
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SinPermiso(onActivar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconoClima(Cielo.POCO_NUBOSO, true, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.wx_city_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.wx_enable_location),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.wx_see),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onActivar)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun Dato(titulo: String, valor: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(valor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(titulo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun PinUbicacion(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val p = Path().apply {
            moveTo(w / 2, w)
            cubicTo(w * 0.05f, w * 0.55f, w * 0.1f, 0f, w / 2, 0f)
            cubicTo(w * 0.9f, 0f, w * 0.95f, w * 0.55f, w / 2, w)
            close()
        }
        drawPath(p, color)
        drawCircle(Color(0xFF171A20), w * 0.16f, Offset(w / 2, w * 0.38f))
    }
}

/** Icono del tiempo dibujado y animado (sol que gira, nubes que flotan, lluvia que cae). */
@Composable
fun IconoClima(cielo: Cielo, dia: Boolean, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "clima").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "t"
    )
    Canvas(modifier) {
        val s = size.minDimension / 64f
        val flota = 1.5f * s * sin(2 * PI.toFloat() * t)
        when (cielo) {
            Cielo.DESPEJADO -> if (dia) sol(Offset(32 * s, 32 * s), 13 * s, t) else luna(Offset(32 * s, 30 * s), 15 * s)
            Cielo.POCO_NUBOSO -> {
                if (dia) sol(Offset(22 * s, 22 * s), 10 * s, t) else luna(Offset(22 * s, 22 * s), 11 * s)
                nube(Offset(36 * s, 40 * s + flota), 15 * s, NUBE)
            }
            Cielo.NUBLADO -> {
                nube(Offset(26 * s, 30 * s - flota), 12 * s, NUBE_OSC)
                nube(Offset(36 * s, 38 * s + flota), 15 * s, NUBE)
            }
            Cielo.NIEBLA -> {
                nube(Offset(32 * s, 28 * s), 14 * s, NUBE_OSC)
                for (i in 0..2) drawLine(NUBE, Offset((12 + 4 * i) * s + flota, (44 + 6 * i) * s), Offset((52 - 4 * i) * s + flota, (44 + 6 * i) * s), 3 * s, StrokeCap.Round)
            }
            Cielo.LLOVIZNA, Cielo.LLUVIA -> {
                nube(Offset(32 * s, 28 * s), 16 * s, NUBE_OSC)
                val gotas = if (cielo == Cielo.LLUVIA) 4 else 3
                for (i in 0 until gotas) {
                    val fase = (t * 2 + i * 0.27f) % 1f
                    val x = (20 + i * 8) * s
                    val y = (42 + 14 * fase) * s
                    drawLine(GOTA.copy(alpha = 1 - fase), Offset(x, y), Offset(x - 2 * s, y + 6 * s), 3 * s, StrokeCap.Round)
                }
            }
            Cielo.NIEVE -> {
                nube(Offset(32 * s, 28 * s), 16 * s, NUBE)
                for (i in 0 until 3) {
                    val fase = (t + i * 0.33f) % 1f
                    drawCircle(Color.White.copy(alpha = 1 - fase), 2.4f * s, Offset((22 + i * 10) * s, (44 + 14 * fase) * s))
                }
            }
            Cielo.TORMENTA -> {
                nube(Offset(32 * s, 26 * s), 16 * s, NUBE_OSC)
                val brillo = if (t % 0.5f < 0.08f) 1f else 0.75f
                val rayo = Path().apply {
                    moveTo(34 * s, 36 * s); lineTo(26 * s, 48 * s); lineTo(32 * s, 48 * s)
                    lineTo(28 * s, 60 * s); lineTo(40 * s, 44 * s); lineTo(33 * s, 44 * s); close()
                }
                drawPath(rayo, RAYO.copy(alpha = brillo))
            }
        }
    }
}

private fun DrawScope.sol(c: Offset, r: Float, t: Float) {
    drawCircle(SOL.copy(alpha = 0.25f), r * 1.55f, c)
    rotate(t * 360f / 4, c) {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4
            drawLine(SOL, Offset(c.x + cos(a) * r * 1.3f, c.y + sin(a) * r * 1.3f),
                Offset(c.x + cos(a) * r * 1.75f, c.y + sin(a) * r * 1.75f), r * 0.22f, StrokeCap.Round)
        }
    }
    drawCircle(SOL, r, c)
}

private fun DrawScope.luna(c: Offset, r: Float) {
    drawCircle(LUNA.copy(alpha = 0.18f), r * 1.4f, c)
    drawCircle(LUNA, r, c)
    drawCircle(Color(0xFF171A20), r * 0.85f, Offset(c.x + r * 0.45f, c.y - r * 0.3f))
}

private fun DrawScope.nube(c: Offset, r: Float, color: Color) {
    drawCircle(color, r * 0.62f, Offset(c.x - r * 0.55f, c.y + r * 0.1f))
    drawCircle(color, r * 0.8f, Offset(c.x, c.y - r * 0.15f))
    drawCircle(color, r * 0.6f, Offset(c.x + r * 0.6f, c.y + r * 0.12f))
    drawRoundRect(color, Offset(c.x - r * 1.15f, c.y + r * 0.05f), Size(r * 2.3f, r * 0.66f),
        androidx.compose.ui.geometry.CornerRadius(r * 0.33f))
}
