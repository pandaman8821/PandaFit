// ============================================================================
//  Panda Fit - TodayDashboard.kt
//  PANEL "HOY": tarjetas de cristal liquido con una ficha de icono flotante
//  sobre el borde izquierdo, cifra grande en el color de la metrica y una
//  grafica propia en cada tarjeta:
//
//    * Pasos        -> barras por hora (00-24 h) + km / kcal / minutos.
//    * Sueno        -> hipnograma (despierto / ligero / profundo) + horas.
//    * Ritmo        -> barra de 5 zonas con marcador del pulso actual.
//    * Presion      -> rango 0-200 mmHg con la franja diastolica-sistolica.
//    * Oxigeno      -> barra 80-100 %.
//    * Pulso del dia-> curva 00-24 h con relleno degradado.
//    * Ejercicio    -> acceso a los entrenamientos.
//
//  Todo se dibuja con Canvas (sin librerias de graficas) y SOLO con datos
//  medidos; si falta un dato se dice, no se inventa.
// ============================================================================
package com.pandafit.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pandafit.app.R
import com.pandafit.app.ble.BleProtocol
import com.pandafit.app.data.DayLog
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlyph
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pandafit.app.ui.components.PandaGlyphIcon
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.ui.theme.SportCard as SportCardColor
import com.pandafit.app.viewmodel.PandaUiState
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ============================================================================
//  COLORES PROPIOS DEL PANEL
// ============================================================================
private val SleepDeep = Color(0xFF5B3BDB)
private val SleepLight = Color(0xFFB07CFF)
private val SleepAwake = Color(0xFFFF9F6E)
private val PressureBlue = Color(0xFF5B7CFA)
private val ExerciseAmber = Color(0xFFF2A93B)
private val ZoneColors = listOf(
    Color(0xFFF7DC6F), Color(0xFFF5C26B), Color(0xFFF2A65A), Color(0xFFEE8559), Color(0xFFE85D5D)
)

// ============================================================================
//  ESTRUCTURA COMUN: TARJETA + FICHA DE ICONO FLOTANTE
// ============================================================================

/**
 * Acciones del panel. Cada tarjeta tiene SU boton (medir, leer, sincronizar);
 * si la pulsera no esta conectada, el boton pasa a ser "Conectar pulsera".
 */
data class AccionesHoy(
    val conectado: Boolean,
    val onConnect: () -> Unit,
    val onSyncBand: () -> Unit,
    val onReadSleep: () -> Unit,
    val onMeasureHeart: () -> Unit,
    val onMeasureSpo2: () -> Unit,
    val onMeasureBp: () -> Unit
)

/** Boton solido de la tarjeta: degradado del color, texto blanco, bien visible. */
@Composable
private fun BotonTarjeta(
    texto: String,
    icon: PandaGlyph,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(accent)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Diseno deportivo: pildora del color con tinta oscura encima.
        val tinta = lerp(accent, Color.Black, 0.78f)
        PandaGlyphIcon(glyph = icon, accent = accent, principal = tinta, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            texto,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = tinta,
            maxLines = 1
        )
    }
}

/** Hidratacion (v19): vasos de 250 ml con una fila de gotas y botones - / +. */
@Composable
fun WaterTodayCard(vasos: Int, meta: Int, onMenos: () -> Unit, onMas: () -> Unit) {
    val agua = PandaFitTheme.colors.distance
    val quitarVaso = stringResource(R.string.water_remove)
    TodayCard(
        icon = PandaGlyph.OXIGENO,
        accent = agua,
        title = stringResource(R.string.water_title),
        value = "$vasos",
        unit = "/ $meta",
        subtitle = String.format(Locale.US, "%.2f L", vasos * 0.25),
        subtitleRight = stringResource(R.string.water_glass_size)
    ) {
        // Una gota por vaso de la meta: llena (color) o vacia (pista).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until meta) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(topStart = 50.dp, topEnd = 50.dp, bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(if (i < vasos) agua else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        if (vasos >= meta) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.water_done),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = agua
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClickLabel = quitarVaso, onClick = onMenos)
                    .semantics { contentDescription = quitarVaso },
                contentAlignment = Alignment.Center
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            BotonTarjeta(
                stringResource(R.string.water_add),
                PandaGlyph.OXIGENO,
                agua,
                onMas,
                Modifier.weight(1f)
            )
        }
    }
}

/** Boton grande "Compartir mis resultados" (imagen con las cifras de hoy). */
@Composable
fun ShareResultsButton(onClick: () -> Unit) {
    BotonTarjeta(
        stringResource(R.string.share_button),
        PandaGlyph.COMPARTIR,
        MaterialTheme.colorScheme.primary,
        onClick,
        Modifier.height(54.dp)
    )
}

/** Boton de la tarjeta: la accion propia si hay pulsera; si no, "Conectar pulsera". */
@Composable
private fun AccionDeTarjeta(
    acciones: AccionesHoy,
    texto: String,
    icon: PandaGlyph,
    accent: Color,
    onClick: () -> Unit
) {
    Spacer(Modifier.height(14.dp))
    if (acciones.conectado) {
        BotonTarjeta(texto, icon, accent, onClick)
    } else {
        BotonTarjeta(
            stringResource(R.string.today_connect_band),
            PandaGlyph.RELOJ,
            accent,
            acciones.onConnect
        )
    }
}

/** Ficha de icono de cristal: degradado del color, brillo superior y borde de luz. */
@Composable
private fun GlassIconTile(glyph: PandaGlyph, accent: Color, modifier: Modifier = Modifier) {
    // v12: ficha PLANA (sin sombra, brillo ni borde de luz): fondo del color
    // muy suave y el icono en el color vivo de la metrica.
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        PandaGlyphIcon(glyph = glyph, accent = SportCardColor, principal = accent, size = 32.dp)
    }
}

/**
 * Tarjeta del panel "Hoy". La ficha del icono sobresale por la izquierda
 * (como una pestana de cristal) y el contenido (grafica) va debajo de la
 * cabecera, separado por una linea fina.
 */
@Composable
fun TodayCard(
    icon: PandaGlyph,
    accent: Color,
    title: String,
    value: String,
    unit: String,
    subtitle: String,
    subtitleRight: String,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val click = if (onClick != null) {
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onClick)
        } else Modifier
        PandaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(click),
            cornerRadius = 22.dp,
            glowAccent = accent,
            glow = 0f,
            padding = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconTile(glyph = icon, accent = accent)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = value,
                                style = MaterialTheme.typography.headlineMedium,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (unit.isNotBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = unit,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(bottom = 5.dp)
                                )
                            }
                        }
                        Text(
                            text = subtitleRight,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

// ============================================================================
//  UTILIDADES
// ============================================================================
// v21 (lint ConstantLocale): el formato se crea con el idioma ACTUAL; antes se
// fijaba al arrancar y no cambiaba si cambiabas el idioma del telefono.
@Composable
private fun momento(ms: Long): String =
    if (ms <= 0L) stringResource(R.string.today_not_measured)
    else DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun hhmm(minutoDelDia: Int): String =
    if (minutoDelDia < 0) "--:--"
    else String.format(Locale.US, "%02d:%02d", minutoDelDia / 60 % 24, minutoDelDia % 60)

@Composable
private fun EjeHoras() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Aviso(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/**
 * Dato del pie (km / kcal / min). Cada uno ocupa un TERCIO fijo de la fila:
 * antes se pegaban entre si y el "min" se salia de la tarjeta.
 */
@Composable
private fun DatoPie(icon: PandaGlyph, valor: String, unidad: String, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            PandaGlyphIcon(glyph = icon, accent = accent, size = 16.dp)
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                valor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                unidad,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// ============================================================================
//  1. PASOS
// ============================================================================
@Composable
fun StepsTodayCard(state: PandaUiState, log: DayLog.Snapshot, acciones: AccionesHoy) {
    val accent = PandaFitTheme.colors.steps
    val nf = NumberFormat.getIntegerInstance()
    TodayCard(
        icon = PandaGlyph.PASOS,
        accent = accent,
        title = stringResource(R.string.today_steps),
        value = nf.format(state.today.steps),
        unit = "",
        subtitle = momento(log.pasosEn),
        subtitleRight = stringResource(R.string.today_steps_goal, nf.format(state.today.stepGoal))
    ) {
        if (log.hayPasosPorHora) {
            val valores = log.pasosPorHora
            val maximo = (valores.maxOrNull() ?: 0).coerceAtLeast(1)
            // Pista de las horas sin pasos: visible tambien en tema claro.
            val vacio = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
            Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                val hueco = size.width / 24f
                val ancho = hueco * 0.56f
                valores.forEachIndexed { i, v ->
                    val alto = if (v > 0) (v / maximo.toFloat()) * size.height else 3.dp.toPx()
                    val x = i * hueco + (hueco - ancho) / 2f
                    drawRoundRect(
                        brush = if (v > 0) {
                            Brush.verticalGradient(
                                listOf(lerp(accent, Color.White, 0.25f), accent),
                                startY = size.height - alto, endY = size.height
                            )
                        } else Brush.linearGradient(listOf(vacio, vacio)),
                        topLeft = Offset(x, size.height - alto),
                        size = Size(ancho, alto),
                        cornerRadius = CornerRadius(ancho / 2f, ancho / 2f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            EjeHoras()
        } else {
            Aviso(stringResource(R.string.today_steps_chart_hint))
        }
        if (!state.usingRealData) {
            Aviso(stringResource(R.string.home_sample_data))
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            DatoPie(
                PandaGlyph.RUTA,
                String.format(Locale.US, "%.2f", state.today.distanceKm), "km",
                accent, Modifier.weight(1f)
            )
            DatoPie(
                PandaGlyph.EJERCICIO, "${state.today.caloriesKcal}", "kcal",
                accent, Modifier.weight(1f)
            )
            DatoPie(
                PandaGlyph.CRONO, "${state.today.activeMinutes}",
                stringResource(R.string.today_minutes), accent, Modifier.weight(1f)
            )
        }
        AccionDeTarjeta(
            acciones, stringResource(R.string.band_sync), PandaGlyph.SINCRONIZAR, accent, acciones.onSyncBand
        )
    }
}

// ============================================================================
//  2. SUENO (hipnograma)
// ============================================================================
@Composable
fun SleepTodayCard(
    state: PandaUiState,
    sueno: BleProtocol.SuenoBanda?,
    metaHoras: Int,
    acciones: AccionesHoy
) {
    val accent = PandaFitTheme.colors.sleep
    val minutos = sueno?.dormidoMin ?: (state.today.sleepHours * 60).toInt()
    TodayCard(
        icon = PandaGlyph.SUENO,
        accent = accent,
        title = stringResource(R.string.today_sleep),
        value = if (minutos > 0) "${minutos / 60}h ${minutos % 60}m" else "--",
        unit = "",
        subtitle = if (sueno != null) "${hhmm(sueno.dormirMin)} - ${hhmm(sueno.despertarMin)}"
        else stringResource(R.string.today_not_measured),
        subtitleRight = stringResource(R.string.today_sleep_goal, metaHoras)
    ) {
        if (sueno == null || sueno.tramos.isEmpty()) {
            Aviso(stringResource(R.string.today_sleep_no_phases))
        } else {
            DetalleSueno(sueno)
        }
        // v9: sin boton. Pedir el sueno al reloj (0x32) dejo el sueno del K56 Pro
        // real a cero; hasta tener una lectura segura no se ofrece.
        Aviso(stringResource(R.string.today_sleep_read_disabled))
    }
}

@Composable
private fun DetalleSueno(sueno: BleProtocol.SuenoBanda) {
    Column {
        Hipnograma(sueno)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(hhmm(sueno.dormirMin), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.today_sleep_fell_asleep), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hhmm(sueno.despertarMin), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.today_sleep_woke_up), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaseChip(stringResource(R.string.today_sleep_deep), sueno.profundoMin, SleepDeep, Modifier.weight(1f))
            FaseChip(stringResource(R.string.today_sleep_light), sueno.ligeroMin, SleepLight, Modifier.weight(1f))
            FaseChip(stringResource(R.string.today_sleep_awake), sueno.despiertoMin, SleepAwake, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FaseChip(nombre: String, minutos: Int, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "${minutos / 60}h ${minutos % 60}m",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(nombre, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Hipnograma: 3 carriles (despierto arriba, ligero, profundo abajo). */
@Composable
private fun Hipnograma(sueno: BleProtocol.SuenoBanda) {
    val tramos = sueno.tramos
    val inicio = tramos.first().inicioMin
    val total = tramos.sumOf { it.duracionMin }.coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
        val alto = size.height / 3.4f
        fun carril(tipo: Int): Float = when (tipo) {
            BleProtocol.TipoSueno.DESPIERTO -> 0f
            BleProtocol.TipoSueno.LIGERO -> size.height / 2f - alto / 2f
            else -> size.height - alto
        }
        fun color(tipo: Int): Color = when (tipo) {
            BleProtocol.TipoSueno.DESPIERTO -> SleepAwake
            BleProtocol.TipoSueno.LIGERO -> SleepLight
            else -> SleepDeep
        }
        var previo: Pair<Float, BleProtocol.TramoSueno>? = null
        tramos.forEach { t ->
            val rel = ((t.inicioMin - inicio + 1440) % 1440).toFloat()
            val x = rel / total * size.width
            val w = (t.duracionMin.toFloat() / total * size.width).coerceAtLeast(2f)
            val y = carril(t.tipo)
            // Union vertical con el tramo anterior (transicion de fase).
            previo?.let { (_, tp) ->
                val y1 = carril(tp.tipo) + alto / 2f
                val y2 = y + alto / 2f
                drawLine(
                    brush = Brush.verticalGradient(listOf(color(tp.tipo), color(t.tipo)), startY = minOf(y1, y2), endY = maxOf(y1, y2) + 1f),
                    start = Offset(x, y1),
                    end = Offset(x, y2),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(lerp(color(t.tipo), Color.White, 0.2f), color(t.tipo)),
                    startY = y, endY = y + alto
                ),
                topLeft = Offset(x, y),
                size = Size(w, alto),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )
            previo = x to t
        }
    }
}

// ============================================================================
//  3. RITMO CARDIACO (zonas)
// ============================================================================
@Composable
fun HeartZonesCard(state: PandaUiState, log: DayLog.Snapshot, edad: Int, acciones: AccionesHoy) {
    val accent = PandaFitTheme.colors.heart
    val ppm = when {
        state.vitals.heartRate > 0 -> state.vitals.heartRate
        log.ultimoPulso != null -> log.ultimoPulso.valor
        else -> 0
    }
    val maximo = (220 - edad).coerceIn(150, 210)
    val umbrales = listOf(0.6f, 0.7f, 0.8f, 0.9f).map { (it * maximo).toInt() }
    TodayCard(
        icon = PandaGlyph.PULSO,
        accent = accent,
        title = stringResource(R.string.today_hr),
        value = if (ppm > 0) "$ppm" else "--",
        unit = stringResource(R.string.metric_ppm),
        subtitle = momento(log.ultimoPulso?.en ?: 0L),
        subtitleRight = stringResource(R.string.today_hr_zones, edad)
    ) {
        val marcador = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            val barraY = 12.dp.toPx()
            val barraH = 18.dp.toPx()
            drawRoundRect(
                brush = Brush.horizontalGradient(ZoneColors),
                topLeft = Offset(0f, barraY),
                size = Size(size.width, barraH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            // Separadores de zona.
            for (i in 1..4) {
                val x = size.width * i / 5f
                drawLine(Color.White.copy(alpha = 0.7f), Offset(x, barraY), Offset(x, barraY + barraH), 1.5.dp.toPx())
            }
            if (ppm > 0) {
                val fr = ((ppm - maximo * 0.5f) / (maximo * 0.5f)).coerceIn(0f, 1f)
                val x = (fr * size.width).coerceIn(6.dp.toPx(), size.width - 6.dp.toPx())
                val tri = Path().apply {
                    moveTo(x - 6.dp.toPx(), 0f)
                    lineTo(x + 6.dp.toPx(), 0f)
                    lineTo(x, 9.dp.toPx())
                    close()
                }
                drawPath(tri, marcador)
            }
        }
        // Umbrales en las fronteras de zona.
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(0.5f))
            umbrales.forEach {
                Text(
                    "$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.weight(0.5f))
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(
                R.string.today_zone_1, R.string.today_zone_2, R.string.today_zone_3,
                R.string.today_zone_4, R.string.today_zone_5
            ).forEach {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        AccionDeTarjeta(
            acciones, stringResource(R.string.today_measure_hr), PandaGlyph.PULSO, accent, acciones.onMeasureHeart
        )
    }
}

// ============================================================================
//  4. PRESION ARTERIAL
// ============================================================================
@Composable
fun BloodPressureCard(log: DayLog.Snapshot, acciones: AccionesHoy) {
    val p = log.presion
    val clasificacion = when {
        p == null -> ""
        p.sistolica >= 130 || p.diastolica >= 80 -> stringResource(R.string.today_bp_high)
        p.sistolica >= 120 -> stringResource(R.string.today_bp_elevated)
        else -> stringResource(R.string.today_bp_normal)
    }
    TodayCard(
        icon = PandaGlyph.PRESION,
        accent = PressureBlue,
        title = stringResource(R.string.today_bp),
        value = if (p != null) "${p.sistolica}/${p.diastolica}" else "--",
        unit = stringResource(R.string.today_bp_unit),
        subtitle = momento(p?.en ?: 0L),
        subtitleRight = clasificacion
    ) {
        RangoBarra(
            minimo = 0f, maximo = 200f,
            desde = p?.diastolica?.toFloat(), hasta = p?.sistolica?.toFloat(),
            accent = PressureBlue,
            etiquetas = listOf("0", "50", "100", "150", "200")
        )
        if (p == null) Aviso(stringResource(R.string.today_bp_hint))
        AccionDeTarjeta(
            acciones, stringResource(R.string.today_measure_bp), PandaGlyph.PRESION,
            PressureBlue, acciones.onMeasureBp
        )
    }
}

/** Barra de rango con contorno y una franja [desde, hasta] rellena. */
@Composable
private fun RangoBarra(
    minimo: Float,
    maximo: Float,
    desde: Float?,
    hasta: Float?,
    accent: Color,
    etiquetas: List<String>
) {
    // Pista con mas contraste (antes 8 % de opacidad: no se distinguia).
    Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
        val r = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        drawRoundRect(color = accent.copy(alpha = 0.20f), cornerRadius = r)
        drawRoundRect(color = accent, cornerRadius = r, style = Stroke(width = 2.dp.toPx()))
        if (desde != null && hasta != null && hasta > desde) {
            val x1 = ((desde - minimo) / (maximo - minimo)).coerceIn(0f, 1f) * size.width
            val x2 = ((hasta - minimo) / (maximo - minimo)).coerceIn(0f, 1f) * size.width
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(lerp(accent, Color.White, 0.25f), accent)),
                topLeft = Offset(x1, 3.dp.toPx()),
                size = Size((x2 - x1).coerceAtLeast(6.dp.toPx()), size.height - 6.dp.toPx()),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        etiquetas.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = lerp(accent, MaterialTheme.colorScheme.onSurface, 0.35f)
            )
        }
    }
}

// ============================================================================
//  5. OXIGENO EN SANGRE
// ============================================================================
@Composable
fun Spo2TodayCard(state: PandaUiState, log: DayLog.Snapshot, acciones: AccionesHoy) {
    val accent = PandaFitTheme.colors.spo2
    val valor = log.spo2?.valor ?: state.vitals.spo2.takeIf { it > 0 }
    TodayCard(
        icon = PandaGlyph.OXIGENO,
        accent = accent,
        title = stringResource(R.string.today_spo2),
        value = valor?.toString() ?: "--",
        unit = "%",
        subtitle = momento(log.spo2?.en ?: 0L),
        subtitleRight = stringResource(R.string.today_spo2_range)
    ) {
        RangoBarra(
            minimo = 80f, maximo = 100f,
            desde = if (valor != null) 80f else null, hasta = valor?.toFloat(),
            accent = accent,
            etiquetas = listOf("80", "85", "90", "95", "100")
        )
        AccionDeTarjeta(
            acciones, stringResource(R.string.today_measure_spo2), PandaGlyph.OXIGENO,
            accent, acciones.onMeasureSpo2
        )
    }
}

// ============================================================================
//  6. PULSO DEL DIA (curva)
// ============================================================================
@Composable
fun HeartDayCard(log: DayLog.Snapshot, acciones: AccionesHoy) {
    val accent = PandaFitTheme.colors.heart
    TodayCard(
        icon = PandaGlyph.CURVA,
        accent = accent,
        title = stringResource(R.string.today_hr_day),
        value = if (log.pulsoMedio > 0) "${log.pulsoMedio}" else "--",
        unit = stringResource(R.string.metric_ppm),
        subtitle = momento(log.ultimoPulso?.en ?: 0L),
        subtitleRight = stringResource(R.string.today_hr_day_avg)
    ) {
        if (log.pulsos.size < 2) {
            Aviso(stringResource(R.string.today_hr_day_hint))
        } else {
            CurvaPulso(log.pulsos, accent)
        }
        AccionDeTarjeta(
            acciones, stringResource(R.string.today_measure_hr), PandaGlyph.PULSO, accent, acciones.onMeasureHeart
        )
    }
}

@Composable
private fun CurvaPulso(muestras: List<DayLog.MuestraPulso>, accent: Color) {
    Column {
        val bajo = (muestras.minOf { it.ppm } - 10).coerceAtLeast(30)
        val alto = (muestras.maxOf { it.ppm } + 10).coerceAtMost(220)
        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            fun punto(m: DayLog.MuestraPulso) = Offset(
                m.minuto / 1440f * size.width,
                size.height - (m.ppm - bajo).toFloat() / (alto - bajo) * size.height
            )
            val linea = Path()
            val area = Path()
            muestras.forEachIndexed { i, m ->
                val p = punto(m)
                if (i == 0) {
                    linea.moveTo(p.x, p.y)
                    area.moveTo(p.x, size.height)
                    area.lineTo(p.x, p.y)
                } else {
                    linea.lineTo(p.x, p.y)
                    area.lineTo(p.x, p.y)
                }
            }
            area.lineTo(punto(muestras.last()).x, size.height)
            area.close()
            drawPath(
                area,
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f)))
            )
            drawPath(linea, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawLine(
                accent.copy(alpha = 0.5f),
                Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()
            )
        }
        Spacer(Modifier.height(6.dp))
        EjeHoras()
    }
}

// ============================================================================
//  7. REGISTROS DE EJERCICIO
// ============================================================================
@Composable
fun ExerciseLinkCard(onOpenTraining: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        PandaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onOpenTraining),
            cornerRadius = 22.dp,
            glowAccent = ExerciseAmber,
            glow = 0f,
            padding = 16.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconTile(glyph = PandaGlyph.EJERCICIO, accent = ExerciseAmber)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.today_exercise),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.today_exercise_sub),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "›",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
