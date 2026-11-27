// ============================================================================
//  Panda Fit - TrainingScreen.kt
//  ENTRENAMIENTO EN VIVO (v14, diseno deportivo oscuro):
//    * selector de actividad con fichas e iconos propios,
//    * cronometro gigante con indicador "En curso",
//    * datos en vivo: distancia (GPS), ritmo min/km, pulso y kcal estimadas,
//    * dibujo del RECORRIDO (forma del trayecto a partir del GPS; no es un mapa
//      de calles) y resumen de la ultima sesion con su recorrido.
//  Al terminar, la sesion se guarda en el telefono y se sube a la nube.
//  Ya no se muestran latitud/longitud en pantalla (no aportaban y exponian la
//  ubicacion).
// ============================================================================
package com.pandafit.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pandafit.app.R
import com.pandafit.app.data.model.TrackPoint
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlassPill
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.viewmodel.FirebaseHealthViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Actividad: nombre (se guarda tal cual en la sesion), icono y MET para kcal. */
private data class Actividad(val nombre: String, val glyph: PandaGlyph, val met: Double, val usaGps: Boolean)

private val ACTIVIDADES = listOf(
    Actividad("Caminata", PandaGlyph.PASOS, 3.5, true),
    Actividad("Carrera", PandaGlyph.EJERCICIO, 9.8, true),
    Actividad("Ciclismo", PandaGlyph.RUTA, 7.5, true),
    Actividad("Fuerza", PandaGlyph.ENTRENAR, 5.0, false),
    Actividad("Yoga", PandaGlyph.SALUD, 2.5, false)
)

/** Peso de referencia para estimar kcal (la app no pide el peso). */
private const val PESO_REFERENCIA_KG = 70.0

@Composable
fun TrainingScreen(
    onBack: () -> Unit,
    viewModel: FirebaseHealthViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val active by viewModel.activeWorkout.collectAsStateWithLifecycle()
    val last by viewModel.lastWorkout.collectAsStateWithLifecycle()
    val historial by remember { com.pandafit.app.PandaFitApplication.get().workoutHistory.sesiones }
        .collectAsStateWithLifecycle()
    val tokens = PandaFitTheme.colors
    val lima = MaterialTheme.colorScheme.primary

    var actividad by remember { mutableStateOf(ACTIVIDADES.first()) }
    // v26: el cronometro, la distancia y el recorrido los lleva el SERVICIO
    // (siguen con la pantalla apagada, con pausa automatica y voz).
    val vivo by com.pandafit.app.ble.PandaFitBackgroundService.entreno.collectAsStateWithLifecycle()
    var arrancando by remember { mutableStateOf(false) }
    val running = vivo != null || arrancando
    LaunchedEffect(vivo) {
        if (vivo != null) {
            arrancando = false
            ACTIVIDADES.firstOrNull { it.nombre == vivo?.tipo }?.let { actividad = it }
        }
    }
    LaunchedEffect(arrancando) { if (arrancando) { delay(4_000); arrancando = false } }
    val prefs = remember { com.pandafit.app.PandaFitApplication.get().preferences }
    var vozOn by remember { mutableStateOf(prefs.voiceCoachEnabled) }
    var autoPausaOn by remember { mutableStateOf(prefs.autoPauseEnabled) }

    val gps = state.lastTrackPoint
    val elapsedSeconds = vivo?.segundos ?: 0
    val puntos = vivo?.recorrido ?: emptyList()
    val distancia = vivo?.km ?: 0.0
    val ritmo = if (distancia > 0.05) elapsedSeconds / 60.0 / distancia else 0.0
    // v19: kcal con el PESO del usuario (Perfil); 70 kg si no lo ha indicado.
    val peso = remember { com.pandafit.app.PandaFitApplication.get().preferences.userWeightKg.toDouble() }
    val kcal = (actividad.met * (if (peso > 0) peso else PESO_REFERENCIA_KG) * elapsedSeconds / 3600.0).toInt()
    val pulso = state.vitals.heartRate

    com.pandafit.app.ui.navigation.PandaScreen(title = null) { modifier ->
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // --------------------------------------------------------- CABECERA
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.training_title),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = if (running) stringResource(nombreActividad(actividad.nombre)) else stringResource(R.string.training_what_today),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PandaGlassPill(text = stringResource(R.string.action_back), onClick = onBack)
                }
            }

            // ------------------------------------------------ TIPO DE ACTIVIDAD
            if (!running) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ACTIVIDADES, key = { it.nombre }) { a ->
                            FichaActividad(a, seleccionada = a == actividad, acento = lima) { actividad = a }
                        }
                    }
                }
            }

            // -------------------------------------------------- CRONOMETRO
            item {
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 26.dp, glow = 0f, padding = 20.dp) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EstadoSesion(running, lima, tokens.heart, pausado = vivo?.pausado == true, auto = vivo?.pausaAuto == true)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = formatDuration(elapsedSeconds),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DatoVivo(
                                if (actividad.usaGps) String.format(Locale.US, "%.2f", distancia) else "--",
                                "km", tokens.distance, Modifier.weight(1f)
                            )
                            DatoVivo(
                                if (ritmo > 0) formatoRitmo(ritmo) else "--",
                                "min/km", tokens.calories, Modifier.weight(1f)
                            )
                            DatoVivo(
                                if (pulso > 0) "$pulso" else "--",
                                stringResource(R.string.metric_ppm), tokens.heart, Modifier.weight(1f)
                            )
                            DatoVivo("$kcal", "kcal", tokens.steps, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        if (!running) {
                            // v26: opciones del entrenador antes de empezar.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Opcion(stringResource(R.string.train_voice_coach), vozOn, lima, Modifier.weight(1f)) {
                                    vozOn = !vozOn; prefs.voiceCoachEnabled = vozOn
                                }
                                if (actividad.usaGps) {
                                    Opcion(stringResource(R.string.train_auto_pause), autoPausaOn, lima, Modifier.weight(1f)) {
                                        autoPausaOn = !autoPausaOn; prefs.autoPauseEnabled = autoPausaOn
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            BotonGrande(
                                texto = stringResource(R.string.training_start),
                                glyph = actividad.glyph,
                                color = lima
                            ) {
                                arrancando = true
                                viewModel.startWorkout(actividad.nombre)
                            }
                        } else {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val enPausa = vivo?.pausaManual == true
                            BotonGrande(
                                texto = if (enPausa) stringResource(R.string.train_resume) else stringResource(R.string.train_pause),
                                glyph = PandaGlyph.CRONO,
                                color = if (enPausa) lima else MaterialTheme.colorScheme.surfaceVariant,
                                tinta = if (enPausa) lerp(lima, Color.Black, 0.78f) else MaterialTheme.colorScheme.onSurface
                            ) {
                                if (enPausa) com.pandafit.app.ble.PandaFitBackgroundService.resumeWorkout(ctx)
                                else com.pandafit.app.ble.PandaFitBackgroundService.pauseWorkout(ctx)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BotonGrande(
                                    texto = stringResource(R.string.training_stop),
                                    glyph = PandaGlyph.CRONO,
                                    color = tokens.heart,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    arrancando = false
                                    viewModel.stopWorkout()
                                }
                                // Medicion SOLO si la pide el usuario (no al iniciar).
                                BotonGrande(
                                    texto = stringResource(R.string.today_measure_hr),
                                    glyph = PandaGlyph.PULSO,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    tinta = tokens.heart,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.measureHeartRate() }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.training_kcal_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ------------------------------------------------------ RECORRIDO
            if (actividad.usaGps || running) {
                item {
                    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PandaGlyphIcon(
                                    glyph = PandaGlyph.RUTA,
                                    accent = MaterialTheme.colorScheme.surface,
                                    principal = tokens.distance,
                                    size = 26.dp,
                                    animado = running
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.training_route),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                // Estado del GPS como chip (sin coordenadas en pantalla).
                                val gpsOk = gps != null && gps.accuracyMeters <= 30f
                                Text(
                                    text = if (gpsOk) "GPS ±${gps!!.accuracyMeters.toInt()} m"
                                    else stringResource(R.string.training_gps_waiting),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (gpsOk) tokens.distance else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            (if (gpsOk) tokens.distance else MaterialTheme.colorScheme.onSurfaceVariant)
                                                .copy(alpha = 0.14f)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            // v26: mapa de calles real (OpenStreetMap) con la ruta encima.
                            com.pandafit.app.ui.components.MapaRecorrido(
                                puntos = puntos,
                                posicion = gps?.takeIf { it.accuracyMeters <= 50f },
                                colorRuta = tokens.distance,
                                colorSalida = lima,
                                seguir = running,
                                alto = 240
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------- RESUMEN DE LA SEMANA
            val semana = com.pandafit.app.data.WorkoutHistory.resumenSemana(historial)
            if (semana.sesiones > 0) {
                item {
                    Text(
                        stringResource(R.string.training_week_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DatoVivo("${semana.sesiones}", stringResource(R.string.training_sessions), lima, Modifier.weight(1f))
                        DatoVivo("${semana.minutos}", "min", tokens.sleep, Modifier.weight(1f))
                        DatoVivo(String.format(Locale.US, "%.1f", semana.km), "km", tokens.distance, Modifier.weight(1f))
                        DatoVivo("${semana.kcal}", "kcal", tokens.steps, Modifier.weight(1f))
                    }
                }
            }

            // -------------------------------------------- ULTIMA SESION GUARDADA
            // v20: si la app se reinicio, la ultima sesion sale del historial local.
            (last ?: historial.firstOrNull())?.let { sesion ->
                item {
                    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
                        Column {
                            Text(
                                text = stringResource(R.string.training_last_session),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = sesion.type,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DatoVivo(formatDuration(sesion.durationSeconds), stringResource(R.string.training_duration), tokens.sleep, Modifier.weight(1f))
                                DatoVivo(String.format(Locale.US, "%.2f", sesion.distanceKm), "km", tokens.distance, Modifier.weight(1f))
                                DatoVivo("${sesion.caloriesKcal}", "kcal", tokens.steps, Modifier.weight(1f))
                            }
                            if (sesion.trackPoints.size >= 2) {
                                Spacer(Modifier.height(12.dp))
                                // key: si cambia la sesion, el mapa vuelve a encuadrar su ruta.
                                androidx.compose.runtime.key(sesion.id, sesion.startedAt) {
                                    com.pandafit.app.ui.components.MapaRecorrido(
                                        puntos = sesion.trackPoints,
                                        posicion = null,
                                        colorRuta = tokens.distance,
                                        colorSalida = lima,
                                        seguir = false,
                                        alto = 170
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.training_saved_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------- HISTORIAL
            val anteriores = historial.drop(1)
            if (anteriores.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.training_history),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                items(anteriores, key = { it.id.ifBlank { it.startedAt.toString() } }) { s ->
                    FilaHistorial(s)
                }
            }

            // ------------------------------------------------- NOTA INFORMATIVA
            item {
                Text(
                    text = stringResource(R.string.training_background_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

// ============================================================================
//  PIEZAS
// ============================================================================

/** Ficha de actividad: icono grande + nombre; la elegida se resalta en lima. */
@Composable
private fun FichaActividad(a: Actividad, seleccionada: Boolean, acento: Color, onClick: () -> Unit) {
    val forma = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(forma)
            .background(if (seleccionada) acento.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (seleccionada) acento else MaterialTheme.colorScheme.outlineVariant,
                forma
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PandaGlyphIcon(
            glyph = a.glyph,
            accent = MaterialTheme.colorScheme.surface,
            principal = if (seleccionada) acento else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 36.dp,
            animado = seleccionada
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(nombreActividad(a.nombre)),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (seleccionada) FontWeight.Bold else FontWeight.Medium,
            color = if (seleccionada) acento else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/** Fila del historial: icono de la actividad, tipo, fecha, duracion y km. */
@Composable
private fun FilaHistorial(s: com.pandafit.app.data.model.WorkoutSession) {
    val act = ACTIVIDADES.firstOrNull { it.nombre == s.type } ?: ACTIVIDADES.first()
    val color = PandaFitTheme.colors.distance
    val fecha = remember(s.startedAt) {
        java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
            .format(java.time.Instant.ofEpochMilli(s.startedAt).atZone(java.time.ZoneId.systemDefault()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            PandaGlyphIcon(act.glyph, MaterialTheme.colorScheme.surface, principal = color, size = 28.dp, animado = false)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(nombreActividad(s.type)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(fecha, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(s.durationSeconds), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (s.distanceKm > 0) String.format(Locale.US, "%.2f km", s.distanceKm) else "${s.caloriesKcal} kcal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Chip de estado: "Listo" o "En curso" con un punto que late. */
@Composable
private fun EstadoSesion(running: Boolean, lima: Color, rojo: Color, pausado: Boolean = false, auto: Boolean = false) {
    val latido by rememberInfiniteTransition(label = "estado").animateFloat(
        initialValue = 0.7f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "latido"
    )
    val ambar = PandaFitTheme.colors.calories
    val color = when {
        running && pausado -> ambar
        running -> rojo
        else -> lima
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .scale(if (running && !pausado) latido else 1f)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                running && auto -> stringResource(R.string.train_auto_paused)
                running && pausado -> stringResource(R.string.train_paused)
                running -> stringResource(R.string.training_live)
                else -> stringResource(R.string.training_ready)
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/** 0.1.7: nombre traducible de cada actividad (en la sesion se guarda en espanol). */
internal fun nombreActividad(tipo: String): Int = when (tipo) {
    "Carrera" -> R.string.act_run
    "Ciclismo" -> R.string.act_bike
    "Fuerza" -> R.string.act_strength
    "Yoga" -> R.string.act_yoga
    else -> R.string.act_walk
}

/** Interruptor tipo chip (voz del entrenador, pausa automatica). */
@Composable
private fun Opcion(texto: String, activa: Boolean, lima: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(forma)
            .background(if (activa) lima.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (activa) lima else MaterialTheme.colorScheme.outlineVariant, forma)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (activa) lima else MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(Modifier.width(6.dp))
        // 0.1.7: hasta 2 lineas (antes "Voz del entrenador" salia cortado en "Voz del").
        Text(
            texto,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (activa) lima else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
    }
}

/** Dato en vivo: cifra grande + unidad, en una casilla oscura. */
@Composable
private fun DatoVivo(valor: String, unidad: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            valor,
            style = if (valor.length <= 4) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false
        )
        Text(unidad, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Boton grande solido (pildora) con icono animado. */
@Composable
private fun BotonGrande(
    texto: String,
    glyph: PandaGlyph,
    color: Color,
    modifier: Modifier = Modifier,
    tinta: Color = lerp(color, Color.Black, 0.78f),
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PandaGlyphIcon(glyph = glyph, accent = color, principal = tinta, size = 26.dp)
        Spacer(Modifier.width(8.dp))
        Text(texto, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tinta, maxLines = 1)
    }
}

/**
 * Dibuja la FORMA del recorrido (no es un mapa de calles): proyeccion plana de
 * los puntos GPS, linea en el color de la distancia, salida en lima y punto
 * actual en blanco.
 */
@Composable
private fun DibujoRecorrido(puntos: List<TrackPoint>, color: Color, lima: Color, alto: Int = 170) {
    val rejilla = MaterialTheme.colorScheme.outlineVariant
    if (puntos.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(alto.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.training_route_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(alto.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // Rejilla suave de fondo.
        val paso = 28.dp.toPx()
        var x = paso
        while (x < size.width) { drawLine(rejilla, Offset(x, 0f), Offset(x, size.height), 1f); x += paso }
        var y = paso
        while (y < size.height) { drawLine(rejilla, Offset(0f, y), Offset(size.width, y), 1f); y += paso }

        val latMedia = puntos.map { it.lat }.average()
        val k = cos(Math.toRadians(latMedia))
        val xs = puntos.map { it.lon * k }
        val ys = puntos.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val rango = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1e-9
        val margen = 18.dp.toPx()
        val escala = minOf(size.width - 2 * margen, size.height - 2 * margen) / rango
        val offX = (size.width - (maxX - minX) * escala) / 2
        val offY = (size.height - (maxY - minY) * escala) / 2
        fun pantalla(i: Int) = Offset(
            (offX + (xs[i] - minX) * escala).toFloat(),
            (size.height - offY - (ys[i] - minY) * escala).toFloat()
        )
        val linea = Path().apply {
            moveTo(pantalla(0).x, pantalla(0).y)
            for (i in 1 until puntos.size) lineTo(pantalla(i).x, pantalla(i).y)
        }
        drawPath(linea, color, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lima, 7.dp.toPx(), pantalla(0))
        drawCircle(Color.White, 7.dp.toPx(), pantalla(puntos.lastIndex))
        drawCircle(color, 3.5.dp.toPx(), pantalla(puntos.lastIndex))
    }
}

// ============================================================================
//  CALCULOS
// ============================================================================

/** Distancia entre dos puntos GPS (haversine), en km. */
internal fun distanciaKm(a: TrackPoint, b: TrackPoint): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

internal fun recorridoKm(puntos: List<TrackPoint>): Double =
    puntos.zipWithNext { a, b -> distanciaKm(a, b) }.sum()

/** Ritmo en min/km como m:ss. */
internal fun formatoRitmo(minPorKm: Double): String {
    if (minPorKm > 60) return "--"
    val total = (minPorKm * 60).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** Formatea segundos como mm:ss o h:mm:ss. */
private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
