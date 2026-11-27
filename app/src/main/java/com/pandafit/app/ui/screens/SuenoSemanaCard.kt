// ============================================================================
//  Panda Fit - SuenoSemanaCard.kt
//  v26: tarjeta "Tu sueño esta semana" (pantalla Salud): barras apiladas por
//  noche (profundo / ligero / despierto), horas medias, hora media de
//  acostarse y de despertar, regularidad y el comentario de Panda.
// ============================================================================
package com.pandafit.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pandafit.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.data.SuenoSemana
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import com.pandafit.app.ui.theme.PandaFitTheme
import java.time.LocalDate

@Composable
fun SuenoSemanaCard() {
    val app = remember { PandaFitApplication.get() }
    val dias by app.archivo.dias.collectAsStateWithLifecycle()
    val meta = app.preferences.sleepGoalHours
    val a = remember(dias, meta) { SuenoSemana.analizar(dias, meta) }
    val violeta = PandaFitTheme.colors.sleep
    val profundo = violeta
    val ligero = violeta.copy(alpha = 0.45f)
    val despierto = PandaFitTheme.colors.calories

    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, glow = 0f, padding = 16.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PandaGlyphIcon(PandaGlyph.SUENO, MaterialTheme.colorScheme.surface, principal = violeta, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sleep_week_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (a == null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.sleep_week_empty),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            Spacer(Modifier.height(14.dp))
            // ---- barras apiladas por noche (los 7 ultimos dias, huecos si no hay dato)
            val hoy = LocalDate.now()
            val porFecha = a.noches.associateBy { it.fecha }
            val fechas = (6 downTo 0).map { hoy.minusDays(it.toLong()).toString() }
            val maxMin = maxOf(a.noches.maxOf { it.totalMin + it.despiertoMin }, (meta * 60).toInt()).toFloat()
            val linea = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                val n = fechas.size
                val hueco = size.width / n
                val ancho = hueco * 0.52f
                val yMeta = size.height * (1 - meta * 60 / maxMin)
                // Linea de la meta (discontinua).
                var x = 0f
                while (x < size.width) {
                    drawLine(linea, Offset(x, yMeta), Offset(x + 10f, yMeta), 2f); x += 18f
                }
                fechas.forEachIndexed { i, f ->
                    val noche = porFecha[f] ?: return@forEachIndexed
                    val izq = i * hueco + (hueco - ancho) / 2
                    var base = size.height
                    fun tramo(min: Int, c: Color) {
                        if (min <= 0) return
                        val h = size.height * min / maxMin
                        drawRoundRect(c, Offset(izq, base - h), Size(ancho, h), CornerRadius(6f))
                        base -= h
                    }
                    if (noche.conFases) {
                        tramo(noche.profundoMin, profundo)
                        tramo(noche.ligeroMin, ligero)
                        tramo(noche.despiertoMin, despierto)
                    } else tramo(noche.totalMin, ligero)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                fechas.forEach { f ->
                    Text(inicial(f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Leyenda(stringResource(R.string.sleep_deep), profundo); Leyenda(stringResource(R.string.sleep_light), ligero); Leyenda(stringResource(R.string.sleep_awake), despierto)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dato(stringResource(R.string.sleep_avg), "${a.mediaMin / 60}h ${a.mediaMin % 60}m", violeta, Modifier.weight(1f))
                Dato(stringResource(R.string.sleep_bedtime), SuenoSemana.hhmm(a.acostarseMin), violeta, Modifier.weight(1f))
                Dato(stringResource(R.string.sleep_waketime), SuenoSemana.hhmm(a.despertarMin), violeta, Modifier.weight(1f))
                Dato(stringResource(R.string.sleep_regular), if (a.regularidad < 0) "--" else "${a.regularidad}", violeta, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(violeta.copy(alpha = 0.10f)).padding(12.dp)
            ) {
                Text("Panda: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = violeta)
                Text(SuenoSemana.comentarioLocal(androidx.compose.ui.platform.LocalContext.current, a, meta), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun inicial(f: String): String = runCatching {
    LocalDate.parse(f).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())
}.getOrDefault("")

@Composable
private fun Leyenda(texto: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(texto, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dato(titulo: String, valor: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(valor, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(titulo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
