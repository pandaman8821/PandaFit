// ============================================================================
//  Panda Fit - LogrosScreen.kt
//  v26: pantalla de LOGROS: retos de esta semana (con su progreso) y medallas
//  (las conseguidas a color con su fecha; las pendientes en gris con lo que
//  hay que hacer para ganarlas). Tambien la tarjeta de acceso para Inicio.
// ============================================================================
package com.pandafit.app.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pandafit.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.data.Logros
import com.pandafit.app.data.Medalla
import com.pandafit.app.data.Reto
import com.pandafit.app.data.TipoMedalla
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlassPill
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import com.pandafit.app.ui.components.PandaProgressBar
import com.pandafit.app.ui.theme.PandaFitTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun colorMedalla(t: TipoMedalla): Color {
    val c = PandaFitTheme.colors
    return when (t) {
        TipoMedalla.RACHA -> c.calories
        TipoMedalla.PASOS -> c.steps
        TipoMedalla.ENTRENO -> c.heart
        TipoMedalla.DISTANCIA -> c.distance
        TipoMedalla.AGUA -> c.spo2
        TipoMedalla.SUENO -> c.sleep
    }
}

private fun glyphMedalla(t: TipoMedalla): PandaGlyph = when (t) {
    TipoMedalla.RACHA -> PandaGlyph.EJERCICIO
    TipoMedalla.PASOS -> PandaGlyph.PASOS
    TipoMedalla.ENTRENO -> PandaGlyph.ENTRENAR
    TipoMedalla.DISTANCIA -> PandaGlyph.RUTA
    TipoMedalla.AGUA -> PandaGlyph.OXIGENO
    TipoMedalla.SUENO -> PandaGlyph.SUENO
}

@Composable
fun LogrosScreen(onBack: () -> Unit) {
    val app = remember { PandaFitApplication.get() }
    val conseguidas by app.logros.conseguidas.collectAsStateWithLifecycle()
    val dias by app.archivo.dias.collectAsStateWithLifecycle()
    val sesiones by app.workoutHistory.sesiones.collectAsStateWithLifecycle()
    val retos = remember(dias, sesiones) { app.logros.retosSemana(app) }
    val lima = MaterialTheme.colorScheme.primary

    com.pandafit.app.ui.navigation.PandaScreen(title = null) { modifier ->
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ach_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.ach_count, conseguidas.size, Logros.MEDALLAS.size),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PandaGlassPill(text = stringResource(R.string.action_back), onClick = onBack)
                }
            }
            item {
                Text(stringResource(R.string.ach_week_challenges), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.ach_week_challenges_sub),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(retos, key = { it.id }) { r -> FilaReto(r, lima) }
            item {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ach_medals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            // Conseguidas primero.
            val orden = Logros.MEDALLAS.sortedBy { if (it.id in conseguidas) 0 else 1 }
            items(orden.chunked(2), key = { fila -> fila.joinToString { it.id } }) { fila ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    fila.forEach { m -> TarjetaMedalla(m, conseguidas[m.id], Modifier.weight(1f)) }
                    if (fila.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FilaReto(r: Reto, lima: Color) {
    val nf = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }
    val color = if (r.cumplido) lima else PandaFitTheme.colors.distance
    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, glow = 0f, padding = 14.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Logros.TEXTOS_RETO[r.id]?.first ?: R.string.ach_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    if (r.cumplido) stringResource(R.string.ach_done) else "${nf.format(r.progreso)} / ${nf.format(r.objetivo)} " + stringResource(Logros.TEXTOS_RETO[r.id]?.second ?: R.string.unit_days),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color
                )
            }
            Spacer(Modifier.height(8.dp))
            PandaProgressBar(progress = r.fraccion, accent = color)
        }
    }
}

@Composable
private fun TarjetaMedalla(m: Medalla, fecha: String?, modifier: Modifier = Modifier) {
    val ganada = fecha != null
    val color = if (ganada) colorMedalla(m.tipo) else MaterialTheme.colorScheme.onSurfaceVariant
    val forma = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(forma)
            .background(if (ganada) color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (ganada) color.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, forma)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (ganada) color.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, color.copy(alpha = if (ganada) 1f else 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            PandaGlyphIcon(glyphMedalla(m.tipo), MaterialTheme.colorScheme.surface, principal = color.copy(alpha = if (ganada) 1f else 0.5f),
                size = 32.dp, animado = ganada)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Logros.TEXTOS[m.id]?.first ?: R.string.ach_medals), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = if (ganada) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (ganada) stringResource(R.string.ach_earned_on, formatoFecha(fecha!!)) else stringResource(Logros.TEXTOS[m.id]?.second ?: R.string.ach_medals),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, minLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun formatoFecha(f: String): String = runCatching {
    LocalDate.parse(f).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
}.getOrDefault(f)

/** Tarjeta de Inicio: medallas conseguidas + el reto mas cercano. */
@Composable
fun LogrosHomeCard(onOpen: () -> Unit) {
    val app = remember { PandaFitApplication.get() }
    val conseguidas by app.logros.conseguidas.collectAsStateWithLifecycle()
    val dias by app.archivo.dias.collectAsStateWithLifecycle()
    val retos = remember(dias) { app.logros.retosSemana(app) }
    val lima = MaterialTheme.colorScheme.primary
    val ambar = PandaFitTheme.colors.calories
    val siguiente = retos.filterNot { it.cumplido }.maxByOrNull { it.fraccion }
    PandaGlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), cornerRadius = 24.dp, glow = 0f, padding = 16.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(ambar.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { PandaGlyphIcon(PandaGlyph.EJERCICIO, MaterialTheme.colorScheme.surface, principal = ambar, size = 28.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ach_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.ach_card_sub, conseguidas.size, retos.count { it.cumplido }, retos.size),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.action_see), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = lima)
            }
            if (siguiente != null) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ach_next, stringResource(Logros.TEXTOS_RETO[siguiente.id]?.first ?: R.string.ach_title), (siguiente.fraccion * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                PandaProgressBar(progress = siguiente.fraccion, accent = PandaFitTheme.colors.distance)
            }
        }
    }
}
