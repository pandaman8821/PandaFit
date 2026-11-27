// ============================================================================
//  Panda Fit - HealthScreen.kt
//  PANEL DE SALUD: barras de los ultimos 7 dias (pasos, sueno, pulso), estado
//  del oxigeno en sangre, gestion de Health Connect y resumen semanal.
//  Todo dibujado a mano con Canvas dentro de tarjetas de cristal.
// ============================================================================
package com.pandafit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.NightlightRound
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pandafit.app.R
import com.pandafit.app.ui.components.PandaBigGlassButton
import com.pandafit.app.ui.components.PandaDataRow
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlassPill
import com.pandafit.app.ui.components.PandaMetricCard
import com.pandafit.app.ui.components.PandaPrivacyBanner
import com.pandafit.app.ui.components.PandaSectionTitle
import com.pandafit.app.ui.navigation.PandaScreen
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.viewmodel.FirebaseHealthViewModel
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import com.pandafit.app.ui.components.PandaProgressRing
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign

@Composable
fun HealthScreen(
    onBack: () -> Unit,
    viewModel: FirebaseHealthViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = PandaFitTheme.colors
    val context = LocalContext.current

    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = viewModel.healthConnectHelper().permissionContract()
    ) { granted -> viewModel.onHealthConnectGranted(granted.isNotEmpty()) }

    val days = state.history.days

    PandaScreen { modifier ->
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.nav_health),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = stringResource(R.string.health_last_7_days),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PandaGlassPill(text = stringResource(R.string.action_back), onClick = onBack)
                }
            }

            // ------------------------------------------- PUNTUACION PANDA (v16)
            item { TarjetaPuntuacion(score = state.report.score, resumen = state.report.summary) }

            // ---------------------------------------------------- HOY (tarjetas)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PandaMetricCard(
                        title = stringResource(R.string.metric_steps),
                        value = java.text.NumberFormat.getIntegerInstance().format(state.today.steps),
                        unit = stringResource(R.string.home_steps_unit),
                        icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
                        glyph = com.pandafit.app.ui.components.PandaGlyph.PASOS,
                        accent = tokens.steps,
                        progress = state.stepProgress,
                        modifier = Modifier.weight(1f)
                    )
                    PandaMetricCard(
                        title = stringResource(R.string.metric_sleep),
                        value = "%.1f".format(state.today.sleepHours),
                        unit = stringResource(R.string.metric_hours),
                        icon = Icons.Outlined.NightlightRound,
                        glyph = com.pandafit.app.ui.components.PandaGlyph.SUENO,
                        accent = tokens.sleep,
                        progress = (state.today.sleepHours / 8.0).toFloat().coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PandaMetricCard(
                        title = stringResource(R.string.metric_heart_rate),
                        value = (if (state.vitals.heartRate > 0) state.vitals.heartRate else state.today.avgHeartRate)
                            .takeIf { it > 0 }?.toString() ?: "--",
                        unit = stringResource(R.string.metric_ppm),
                        icon = Icons.Filled.Favorite,
                        glyph = com.pandafit.app.ui.components.PandaGlyph.PULSO,
                        accent = tokens.heart,
                        footer = stringResource(R.string.health_rest_max, state.today.restingHeartRate, state.today.maxHeartRate),
                        modifier = Modifier.weight(1f)
                    )
                    PandaMetricCard(
                        title = stringResource(R.string.metric_spo2),
                        value = state.today.spo2.takeIf { it > 0 }?.toString() ?: "--",
                        unit = "% SpO2",
                        icon = Icons.Filled.Bloodtype,
                        glyph = com.pandafit.app.ui.components.PandaGlyph.OXIGENO,
                        accent = tokens.spo2,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ------------------------------------------------------ GRAFICAS 7D
            if (days.isNotEmpty()) {
                item { PandaSectionTitle(text = stringResource(R.string.health_week)) }
                val etiquetas = days.map { inicialDia(it.dateKey) }
                item {
                    BarChartCard(
                        title = stringResource(R.string.health_steps_by_day),
                        values = days.map { it.steps.toFloat() },
                        labels = etiquetas,
                        goal = state.today.stepGoal.toFloat(),
                        accent = tokens.steps,
                        glyph = PandaGlyph.PASOS
                    )
                }
                item {
                    BarChartCard(
                        title = stringResource(R.string.health_sleep_by_night),
                        values = days.map { it.sleepHours.toFloat() },
                        labels = etiquetas,
                        goal = 8f,
                        accent = tokens.sleep,
                        decimals = true,
                        glyph = PandaGlyph.SUENO
                    )
                }
                // v26: sueno a fondo (fases, hora media de acostarse, regularidad).
                item { SuenoSemanaCard() }
                // El pulso en reposo solo se grafica si hay algun dia medido.
                if (days.any { it.restingHeartRate > 0 }) {
                    item {
                        BarChartCard(
                            title = stringResource(R.string.health_resting_hr_chart),
                            values = days.map { it.restingHeartRate.toFloat() },
                            labels = etiquetas,
                            goal = 70f,
                            accent = tokens.heart,
                            glyph = PandaGlyph.PULSO
                        )
                    }
                }
            }

            // ------------------------------------------------- RESUMEN SEMANAL
            item { PandaSectionTitle(text = stringResource(R.string.health_week_summary)) }
            item {
                val diasMeta = days.count { it.steps >= state.today.stepGoal }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CasillaSemana(
                            stringResource(R.string.health_avg_steps),
                            java.text.NumberFormat.getIntegerInstance().format(state.history.averageSteps),
                            tokens.steps, Modifier.weight(1f)
                        )
                        CasillaSemana(
                            stringResource(R.string.health_avg_sleep),
                            "%.1f h".format(state.history.averageSleep),
                            tokens.sleep, Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CasillaSemana(
                            stringResource(R.string.health_avg_spo2),
                            if (state.history.averageSpo2 > 0) "${state.history.averageSpo2} %" else "--",
                            tokens.spo2, Modifier.weight(1f)
                        )
                        CasillaSemana(
                            stringResource(R.string.health_goal_days),
                            "$diasMeta/${days.size.coerceAtLeast(1)}",
                            MaterialTheme.colorScheme.primary, Modifier.weight(1f)
                        )
                    }
                }
            }

            // -------------------------------------------------- HEALTH CONNECT
            item {
                PandaGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowAccent = MaterialTheme.colorScheme.secondary,
                    glow = 0.28f
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                PandaGlyphIcon(
                                    glyph = PandaGlyph.SALUD,
                                    accent = MaterialTheme.colorScheme.surface,
                                    principal = MaterialTheme.colorScheme.secondary,
                                    size = 32.dp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.hc_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (state.healthConnectGranted)
                                        stringResource(R.string.health_hc_reading)
                                    else stringResource(R.string.health_hc_authorize),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        PandaBigGlassButton(
                            text = if (state.healthConnectGranted) stringResource(R.string.health_hc_reread)
                            else stringResource(R.string.health_hc_connect),
                            subtitle = if (state.healthConnectAvailable)
                                stringResource(R.string.health_hc_system)
                            else stringResource(R.string.health_hc_install),
                            glyph = PandaGlyph.SINCRONIZAR,
                            accent = MaterialTheme.colorScheme.secondary,
                            height = 78.dp,
                            onClick = {
                                val helper = viewModel.healthConnectHelper()
                                if (helper.isAvailable()) {
                                    healthConnectLauncher.launch(helper.readPermissions)
                                } else {
                                    runCatching { context.startActivity(helper.installIntent()) }
                                }
                            }
                        )
                    }
                }
            }

            // ----------------------------------------------------- INSIGHTS
            if (state.report.insights.isNotEmpty()) {
                item { PandaSectionTitle(text = stringResource(R.string.health_smart_analysis)) }
                itemsIndexed(state.report.insights) { _, insight ->
                    PandaGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowAccent = when (insight.severity) {
                            2 -> MaterialTheme.colorScheme.error
                            1 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        glow = 0.18f
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Chip de gravedad: atencion (rojo), aviso (violeta), bien (lima).
                                val colorAviso = when (insight.severity) {
                                    2 -> MaterialTheme.colorScheme.error
                                    1 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Text(
                                    text = when (insight.severity) {
                                        2 -> stringResource(R.string.health_sev_high)
                                        1 -> stringResource(R.string.health_sev_mid)
                                        else -> stringResource(R.string.health_sev_ok)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colorAviso,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(colorAviso.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = insight.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = insight.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (insight.recommendation.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "\u2192 ${insight.recommendation}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                PandaPrivacyBanner(
                    text = stringResource(R.string.health_privacy_note)
                )
            }
        }
    }
}

// ============================================================================
//  PIEZAS DEL PANEL DE SALUD (v16, diseno deportivo oscuro)
// ============================================================================

/** Inicial del dia de la semana ("L", "M", "X"...) a partir de "yyyy-MM-dd". */
private fun inicialDia(dateKey: String): String = runCatching {
    java.time.LocalDate.parse(dateKey).dayOfWeek
        .getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())
        .uppercase(java.util.Locale.getDefault())
}.getOrDefault(dateKey.takeLast(2))

/** Puntuacion Panda (0-100) en un anillo grande, con su resumen. */
@Composable
private fun TarjetaPuntuacion(score: Int, resumen: String) {
    val color = when {
        score >= 75 -> MaterialTheme.colorScheme.primary
        score >= 50 -> PandaFitTheme.colors.calories
        else -> MaterialTheme.colorScheme.error
    }
    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PandaProgressRing(
                progress = score / 100f,
                accent = color,
                modifier = Modifier.size(104.dp),
                strokeWidth = 11.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$score",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text("/100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.health_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    resumen.ifBlank { stringResource(R.string.health_score_hint) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4
                )
            }
        }
    }
}

/** Casilla del resumen semanal: etiqueta pequena + cifra grande en su color. */
@Composable
private fun CasillaSemana(titulo: String, valor: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text(titulo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(
            valor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Grafica de barras de 7 dias: barras planas (hoy en color pleno, el resto al
 * 45 %), meta en linea discontinua, iniciales de los dias y el valor de hoy.
 */
@Composable
private fun BarChartCard(
    title: String,
    values: List<Float>,
    labels: List<String>,
    goal: Float,
    accent: Color,
    decimals: Boolean = false,
    glyph: PandaGlyph? = null
) {
    val maxValue = maxOf(values.maxOrNull() ?: 1f, goal).coerceAtLeast(1f) * 1.12f
    val conDatos = values.filter { it > 0f }
    val average = if (conDatos.isEmpty()) 0f else conDatos.average().toFloat()
    val hoy = values.lastOrNull() ?: 0f
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pista = MaterialTheme.colorScheme.surfaceVariant
    val format = { v: Float ->
        if (decimals) String.format(java.util.Locale.US, "%.1f", v)
        else java.text.NumberFormat.getIntegerInstance().format(v.toInt())
    }

    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PandaGlyphIcon(glyph = glyph, accent = MaterialTheme.colorScheme.surface, principal = accent, size = 24.dp, animado = false)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(format(hoy), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
                    Text(stringResource(R.string.health_today_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                if (values.isEmpty()) return@Canvas
                val slot = size.width / values.size
                val barWidth = slot * 0.52f
                values.forEachIndexed { index, value ->
                    val left = index * slot + (slot - barWidth) / 2f
                    val r = CornerRadius(barWidth / 2f, barWidth / 2f)
                    // Pista de fondo de cada dia.
                    drawRoundRect(pista, Offset(left, 0f), Size(barWidth, size.height), r)
                    val barHeight = ((value / maxValue).coerceIn(0f, 1f) * size.height)
                    if (barHeight > 0f) {
                        drawRoundRect(
                            color = if (index == values.lastIndex) accent else accent.copy(alpha = 0.45f),
                            topLeft = Offset(left, size.height - barHeight),
                            size = Size(barWidth, barHeight.coerceAtLeast(barWidth)),
                            cornerRadius = r
                        )
                    }
                }
                // Meta: linea discontinua por encima de las barras.
                val goalY = size.height - (goal / maxValue) * size.height
                drawLine(
                    color = metaColor,
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (index == labels.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == labels.lastIndex) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.health_chart_avg_goal, format(average), format(goal)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
