// ============================================================================
//  Panda Fit - PandaGlyphs.kt
//  FAMILIA DE ICONOS PROPIA Y ANIMADA (v10).
//
//  Todos los iconos de la app (fichas del panel "Hoy", baldosas de la pulsera,
//  botones, barra inferior...) se dibujan aqui con Canvas, en varias capas:
//    * forma principal ([principal], blanco por defecto),
//    * detalles en el color de la metrica ([detalle]),
//    * brillos translucidos de cristal.
//  Cada icono tiene un MOVIMIENTO suave ligado a lo que representa (el corazon
//  late, las estrellas titilan, las burbujas suben...). Con animado=false se
//  dibuja quieto (por ejemplo, las pestanas no seleccionadas de la barra).
//
//  Rejilla de diseno: 64 x 64, escalada al tamano pedido.
// ============================================================================
package com.pandafit.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Iconos disponibles. */
enum class PandaGlyph(internal val periodoMs: Int) {
    // Metricas
    PASOS(1400), SUENO(3200), PULSO(1100), PRESION(2600), OXIGENO(2400),
    CURVA(2800), EJERCICIO(900), BATERIA(2400), RUTA(2000), CRONO(3000),
    // Acciones / estado
    SINCRONIZAR(1800), RELOJ(1600), COMPARTIR(1800),
    // Barra inferior
    INICIO(3000), ENTRENAR(1600), IA(2200), SALUD(1400), PERFIL(4000)
}

/**
 * Dibuja el icono [glyph].
 * @param detalle   color de los detalles interiores (normalmente el de la metrica).
 * @param principal color de la forma principal (blanco sobre fichas de color).
 * @param animado   false = icono quieto.
 */
@Composable
fun PandaGlyphIcon(
    glyph: PandaGlyph,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    principal: Color = Color.White,
    animado: Boolean = true,
    /** Texto para TalkBack si el icono va SOLO (sin texto al lado); null = decorativo. */
    contentDescription: String? = null
) {
    val t = if (animado) {
        val transicion = rememberInfiniteTransition(label = "glyph_${glyph.name}")
        val valor by transicion.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(glyph.periodoMs, easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "glyphT"
        )
        valor
    } else 0.25f

    val accesible = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else Modifier
    Canvas(modifier = modifier.size(size).then(accesible)) {
        val s = this.size.minDimension / 64f
        scale(s, s, pivot = Offset.Zero) {
            val c = Colores(principal, accent, principal.copy(alpha = 0.42f))
            when (glyph) {
                PandaGlyph.PASOS -> pasos(c, t)
                PandaGlyph.SUENO -> sueno(c, t)
                PandaGlyph.PULSO -> pulso(c, t)
                PandaGlyph.PRESION -> presion(c, t)
                PandaGlyph.OXIGENO -> oxigeno(c, t)
                PandaGlyph.CURVA -> curva(c, t)
                PandaGlyph.EJERCICIO -> ejercicio(c, t)
                PandaGlyph.BATERIA -> bateria(c, t)
                PandaGlyph.RUTA -> ruta(c, t)
                PandaGlyph.CRONO -> crono(c, t)
                PandaGlyph.SINCRONIZAR -> sincronizar(c, t)
                PandaGlyph.RELOJ -> reloj(c, t)
                PandaGlyph.COMPARTIR -> compartir(c, t)
                PandaGlyph.INICIO -> inicio(c, t)
                PandaGlyph.ENTRENAR -> entrenar(c, t)
                PandaGlyph.IA -> ia(c, t)
                PandaGlyph.SALUD -> salud(c, t)
                PandaGlyph.PERFIL -> perfil(c, t)
            }
        }
    }
}

/** Paleta de un icono: forma principal, detalle y brillo. */
private class Colores(val p: Color, val d: Color, val brillo: Color)

/** Onda 0..1..0 suave a partir de t (0..1). */
private fun onda(t: Float, desfase: Float = 0f): Float =
    (0.5f - 0.5f * cos(2f * PI.toFloat() * (t + desfase)))

private fun Path.estrella4(c: Offset, r: Float) {
    moveTo(c.x, c.y - r)
    quadraticTo(c.x, c.y, c.x + r, c.y)
    quadraticTo(c.x, c.y, c.x, c.y + r)
    quadraticTo(c.x, c.y, c.x - r, c.y)
    quadraticTo(c.x, c.y, c.x, c.y - r)
    close()
}

private fun DrawScope.estrella(c: Offset, r: Float, color: Color) {
    drawPath(Path().apply { estrella4(c, r) }, color)
}

private fun corazonPath(): Path = Path().apply {
    moveTo(32f, 55f)
    cubicTo(12f, 42f, 5f, 31f, 7f, 21f)
    cubicTo(9f, 11f, 24f, 6f, 32f, 17f)
    cubicTo(40f, 6f, 55f, 11f, 57f, 21f)
    cubicTo(59f, 31f, 52f, 42f, 32f, 55f)
    close()
}

/** Dibuja solo la fraccion [0, f] de un trazo (efecto "se va dibujando"). */
private fun DrawScope.trazoParcial(path: Path, f: Float, color: Color, ancho: Float) {
    val medida = PathMeasure().apply { setPath(path, false) }
    val parcial = Path()
    medida.getSegment(0f, medida.length * f.coerceIn(0f, 1f), parcial, true)
    drawPath(parcial, color, style = Stroke(ancho, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ============================================================================
//  METRICAS
// ============================================================================

/**
 * Zapatilla deportiva de perfil (v11, sustituye a las huellas): suela, franja
 * lateral y cordones. Se balancea como al caminar y deja lineas de velocidad.
 */
private fun DrawScope.pasos(c: Colores, t: Float) {
    // Lineas de velocidad detras del talon: se deslizan y se desvanecen.
    for (i in 0..2) {
        val f = (t + i / 3f) % 1f
        val y = 26f + i * 7f
        val x0 = 10f - 8f * f
        drawLine(
            c.p.copy(alpha = 0.85f * (1f - f)),
            Offset(x0, y), Offset(x0 + 7f - 3f * i / 2f, y),
            2.6f, StrokeCap.Round
        )
    }
    val balanceo = -7f * onda(t)
    rotate(balanceo, pivot = Offset(40f, 50f)) {
        translate(4f, 0f) {
            // Parte de arriba de la zapatilla
            val upper = Path().apply {
                moveTo(10f, 44f)
                lineTo(11f, 27f)
                cubicTo(12f, 21f, 18f, 18f, 24f, 20f)
                lineTo(29f, 26f)
                cubicTo(35f, 29f, 43f, 31f, 50f, 34f)
                cubicTo(56f, 36f, 59f, 40f, 58f, 44f)
                close()
            }
            drawPath(upper, c.p)
            // Suela con franja de color
            drawRoundRect(c.p, topLeft = Offset(7f, 43f), size = Size(53f, 9f), cornerRadius = CornerRadius(4.5f, 4.5f))
            drawRoundRect(c.d, topLeft = Offset(10f, 46.5f), size = Size(47f, 2.6f), cornerRadius = CornerRadius(1.3f, 1.3f))
            // Franja lateral
            val franja = Path().apply {
                moveTo(15f, 39f); cubicTo(24f, 34f, 33f, 41f, 47f, 38f)
            }
            drawPath(franja, c.d, style = Stroke(3.2f, cap = StrokeCap.Round))
            // Cordones
            drawLine(c.d, Offset(30f, 30f), Offset(33.5f, 25f), 2.6f, StrokeCap.Round)
            drawLine(c.d, Offset(35.5f, 32f), Offset(39f, 27f), 2.6f, StrokeCap.Round)
            drawLine(c.d, Offset(41f, 33.5f), Offset(44.5f, 28.5f), 2.6f, StrokeCap.Round)
            // Brillo
            drawOval(c.brillo, topLeft = Offset(14f, 24f), size = Size(5f, 10f))
        }
    }
}

/** Luna creciente; las estrellas titilan y una "z" sube flotando. */
private fun DrawScope.sueno(c: Colores, t: Float) {
    val luna = Path().apply { addOval(Rect(8f, 12f, 48f, 52f)) }
    val corte = Path().apply { addOval(Rect(20f, 4f, 56f, 40f)) }
    drawPath(Path().apply { op(luna, corte, PathOperation.Difference) }, c.p)
    drawCircle(c.d.copy(alpha = 0.45f), 2.6f, Offset(17f, 38f))
    drawCircle(c.d.copy(alpha = 0.35f), 1.8f, Offset(24f, 46f))
    estrella(Offset(50f, 46f), 3.5f + 2f * onda(t), c.p)
    estrella(Offset(54f, 14f), 2f + 1.5f * onda(t, 0.5f), c.p.copy(alpha = 0.5f + 0.5f * onda(t, 0.5f)))
    // "z" que sube y se desvanece
    val sube = 10f * t
    val alfa = (1f - t).coerceIn(0f, 1f)
    val z = Path().apply {
        moveTo(36f, 26f - sube); lineTo(44f, 26f - sube); lineTo(36f, 34f - sube); lineTo(44f, 34f - sube)
    }
    drawPath(z, c.p.copy(alpha = alfa), style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Corazon que late (doble latido) con un electrocardiograma que se dibuja. */
private fun DrawScope.pulso(c: Colores, t: Float) {
    // Doble latido: picos en t=0.10 y t=0.28.
    fun pico(centro: Float) = (1f - abs(t - centro) / 0.09f).coerceIn(0f, 1f)
    val latido = 1f + 0.10f * maxOf(pico(0.10f), 0.7f * pico(0.28f))
    scale(latido, latido, pivot = Offset(32f, 32f)) {
        drawPath(corazonPath(), c.p)
        drawOval(c.brillo, topLeft = Offset(12f, 15f), size = Size(9f, 6f))
    }
    val ecg = Path().apply {
        moveTo(9f, 33f); lineTo(21f, 33f); lineTo(25f, 25f); lineTo(30f, 42f)
        lineTo(35f, 28f); lineTo(39f, 33f); lineTo(55f, 33f)
    }
    trazoParcial(ecg, (t * 1.6f).coerceAtMost(1f), c.d, 3.4f)
}

/** Manometro con la aguja oscilando. */
private fun DrawScope.presion(c: Colores, t: Float) {
    drawCircle(c.p, 24f, Offset(32f, 32f))
    drawArc(
        c.d.copy(alpha = 0.3f), 150f, 240f, false,
        topLeft = Offset(14f, 14f), size = Size(36f, 36f), style = Stroke(4f, cap = StrokeCap.Round)
    )
    // Tramo "verde" del arco y marcas.
    drawArc(
        c.d, 150f, 150f, false,
        topLeft = Offset(14f, 14f), size = Size(36f, 36f), style = Stroke(4f, cap = StrokeCap.Round)
    )
    for (i in 0..4) {
        val ang = (150f + 60f * i) * PI.toFloat() / 180f
        drawLine(
            c.d.copy(alpha = 0.6f),
            Offset(32f + 13f * cos(ang), 32f + 13f * sin(ang)),
            Offset(32f + 15.5f * cos(ang), 32f + 15.5f * sin(ang)),
            1.6f, StrokeCap.Round
        )
    }
    // Aguja: oscila entre -70 y -20 grados.
    val grados = -70f + 50f * onda(t)
    val rad = grados * PI.toFloat() / 180f
    drawLine(c.d, Offset(32f, 32f), Offset(32f + 13f * cos(rad), 32f + 13f * sin(rad)), 3.2f, StrokeCap.Round)
    drawCircle(c.d, 4f, Offset(32f, 32f))
    drawCircle(c.p, 1.6f, Offset(32f, 32f))
    drawOval(c.brillo, topLeft = Offset(16f, 12f), size = Size(12f, 6f))
}

/** Gota con burbujas de oxigeno que suben. */
private fun DrawScope.oxigeno(c: Colores, t: Float) {
    val gota = Path().apply {
        moveTo(32f, 5f)
        cubicTo(32f, 5f, 11f, 29f, 11f, 40f)
        cubicTo(11f, 52f, 21f, 59f, 32f, 59f)
        cubicTo(43f, 59f, 53f, 52f, 53f, 40f)
        cubicTo(53f, 29f, 32f, 5f, 32f, 5f)
        close()
    }
    drawPath(gota, c.p)
    // Tres burbujas en bucle, desfasadas.
    listOf(Triple(26f, 5.5f, 0f), Triple(37f, 3.8f, 0.35f), Triple(31f, 2.6f, 0.7f)).forEach { (x, r, d) ->
        val f = (t + d) % 1f
        val y = 54f - 22f * f
        drawCircle(c.d.copy(alpha = 1f - 0.7f * f), r, Offset(x, y), style = Stroke(2.4f))
    }
    drawOval(c.brillo, topLeft = Offset(17f, 30f), size = Size(5f, 10f))
}

/** Panel con una curva que se dibuja y un punto que la recorre. */
private fun DrawScope.curva(c: Colores, t: Float) {
    drawRoundRect(c.p, topLeft = Offset(6f, 10f), size = Size(52f, 44f), cornerRadius = CornerRadius(9f, 9f))
    val linea = Path().apply {
        moveTo(12f, 44f); lineTo(22f, 33f); lineTo(30f, 39f); lineTo(40f, 20f); lineTo(52f, 27f)
    }
    trazoParcial(linea, t * 1.3f, c.d, 3.4f)
    val medida = PathMeasure().apply { setPath(linea, false) }
    val pos = medida.getPosition(medida.length * (t * 1.3f).coerceAtMost(1f))
    drawCircle(c.d, 4f, pos)
    drawCircle(c.p, 1.6f, pos)
}

/** Llama de dos capas que parpadea. */
private fun DrawScope.ejercicio(c: Colores, t: Float) {
    val vibra = 1f + 0.06f * sin(2f * PI.toFloat() * t * 2f)
    scale(1f, vibra, pivot = Offset(32f, 59f)) {
        val exterior = Path().apply {
            moveTo(32f, 5f)
            cubicTo(44f, 19f, 53f, 30f, 51f, 42f)
            cubicTo(49f, 54f, 41f, 59f, 32f, 59f)
            cubicTo(23f, 59f, 13f, 53f, 13f, 42f)
            cubicTo(13f, 32f, 21f, 27f, 23f, 18f)
            cubicTo(27f, 24f, 29f, 27f, 31f, 30f)
            cubicTo(34f, 22f, 34f, 13f, 32f, 5f)
            close()
        }
        drawPath(exterior, c.p)
    }
    val interior = 0.85f + 0.2f * onda(t)
    scale(interior, interior, pivot = Offset(32f, 56f)) {
        val p = Path().apply {
            moveTo(32f, 32f)
            cubicTo(39f, 39f, 43f, 46f, 41f, 51f)
            cubicTo(39f, 56f, 25f, 56f, 23f, 51f)
            cubicTo(21f, 45f, 27f, 39f, 32f, 32f)
            close()
        }
        drawPath(p, c.d)
    }
    drawOval(c.brillo, topLeft = Offset(17f, 38f), size = Size(5f, 10f))
}

/** Pila cuyas barras se van llenando. */
private fun DrawScope.bateria(c: Colores, t: Float) {
    drawRoundRect(c.p, topLeft = Offset(12f, 14f), size = Size(40f, 44f), cornerRadius = CornerRadius(8f, 8f))
    drawRoundRect(c.p, topLeft = Offset(24f, 7f), size = Size(16f, 8f), cornerRadius = CornerRadius(3f, 3f))
    val llenas = (t * 4f).toInt().coerceIn(0, 3) + 1
    for (i in 0 until 3) {
        val y = 46f - i * 11f
        drawRoundRect(
            if (i < llenas) c.d else c.d.copy(alpha = 0.18f),
            topLeft = Offset(18f, y), size = Size(28f, 8f), cornerRadius = CornerRadius(3f, 3f)
        )
    }
    drawOval(c.brillo, topLeft = Offset(15f, 17f), size = Size(5f, 10f))
}

/** Marcador de mapa que "salta" con su sombra. */
private fun DrawScope.ruta(c: Colores, t: Float) {
    val salto = 4f * onda(t)
    drawOval(c.p.copy(alpha = 0.35f), topLeft = Offset(22f - salto, 52f), size = Size(20f + 2 * salto, 6f))
    translate(0f, -salto) {
        val pin = Path().apply {
            moveTo(32f, 54f)
            cubicTo(20f, 40f, 14f, 32f, 14f, 24f)
            cubicTo(14f, 13f, 22f, 6f, 32f, 6f)
            cubicTo(42f, 6f, 50f, 13f, 50f, 24f)
            cubicTo(50f, 32f, 44f, 40f, 32f, 54f)
            close()
        }
        drawPath(pin, c.p)
        drawCircle(c.d, 6.5f, Offset(32f, 24f))
        drawOval(c.brillo, topLeft = Offset(19f, 13f), size = Size(6f, 9f))
    }
}

/** Cronometro con la aguja dando la vuelta. */
private fun DrawScope.crono(c: Colores, t: Float) {
    drawRoundRect(c.p, topLeft = Offset(27f, 4f), size = Size(10f, 7f), cornerRadius = CornerRadius(2f, 2f))
    drawCircle(c.p, 24f, Offset(32f, 36f))
    drawCircle(c.d.copy(alpha = 0.25f), 19f, Offset(32f, 36f), style = Stroke(2f))
    drawArc(
        c.d, -90f, 360f * t, false,
        topLeft = Offset(13f, 17f), size = Size(38f, 38f), style = Stroke(3f, cap = StrokeCap.Round)
    )
    val rad = (-90f + 360f * t) * PI.toFloat() / 180f
    drawLine(c.d, Offset(32f, 36f), Offset(32f + 14f * cos(rad), 36f + 14f * sin(rad)), 3f, StrokeCap.Round)
    drawCircle(c.d, 3f, Offset(32f, 36f))
}

// ============================================================================
//  ACCIONES / ESTADO
// ============================================================================

/** Dos flechas en circulo que giran. */
private fun DrawScope.sincronizar(c: Colores, t: Float) {
    rotate(360f * t, pivot = Offset(32f, 32f)) {
        val estilo = Stroke(6f, cap = StrokeCap.Round)
        drawArc(c.p, 200f, 130f, false, topLeft = Offset(12f, 12f), size = Size(40f, 40f), style = estilo)
        drawArc(c.p, 20f, 130f, false, topLeft = Offset(12f, 12f), size = Size(40f, 40f), style = estilo)
        fun punta(ang: Float) {
            val rad = ang * PI.toFloat() / 180f
            val cx = 32f + 20f * cos(rad)
            val cy = 32f + 20f * sin(rad)
            val tangente = rad + PI.toFloat() / 2f
            val p = Path().apply {
                moveTo(cx + 7f * cos(tangente), cy + 7f * sin(tangente))
                lineTo(cx + 6f * cos(rad), cy + 6f * sin(rad))
                lineTo(cx - 6f * cos(rad), cy - 6f * sin(rad))
                close()
            }
            drawPath(p, c.p)
        }
        punta(330f)
        punta(150f)
    }
    drawCircle(c.d, 4f + 1.5f * onda(t), Offset(32f, 32f))
}

/** Tres nodos unidos (compartir) que se iluminan en cadena. */
private fun DrawScope.compartir(c: Colores, t: Float) {
    val a = Offset(46f, 14f)
    val b = Offset(16f, 32f)
    val d = Offset(46f, 50f)
    drawLine(c.p, b, a, 4f, StrokeCap.Round)
    drawLine(c.p, b, d, 4f, StrokeCap.Round)
    listOf(b to 0f, a to 0.33f, d to 0.66f).forEach { (p, fase) ->
        val k = 1f + 0.25f * onda(t, fase)
        drawCircle(c.p, 8f * k, p)
        drawCircle(c.d, 3.2f * k, p)
    }
}

/** Reloj inteligente con un latido en la pantalla. */
private fun DrawScope.reloj(c: Colores, t: Float) {
    drawRoundRect(c.p.copy(alpha = 0.7f), topLeft = Offset(23f, 3f), size = Size(18f, 12f), cornerRadius = CornerRadius(3f, 3f))
    drawRoundRect(c.p.copy(alpha = 0.7f), topLeft = Offset(23f, 49f), size = Size(18f, 12f), cornerRadius = CornerRadius(3f, 3f))
    drawRoundRect(c.p, topLeft = Offset(14f, 12f), size = Size(36f, 40f), cornerRadius = CornerRadius(10f, 10f))
    drawRoundRect(c.d, topLeft = Offset(19f, 17f), size = Size(26f, 30f), cornerRadius = CornerRadius(6f, 6f))
    val r = 3.5f + 2.5f * onda(t)
    drawCircle(c.p, r, Offset(32f, 32f))
    drawCircle(c.p.copy(alpha = 0.35f * (1f - onda(t))), r + 5f, Offset(32f, 32f), style = Stroke(1.6f))
    drawRect(c.p, topLeft = Offset(50f, 26f), size = Size(3f, 8f))
}

// ============================================================================
//  BARRA INFERIOR
// ============================================================================

/** Casa con la ventana encendida que brilla. */
private fun DrawScope.inicio(c: Colores, t: Float) {
    val casa = Path().apply {
        moveTo(32f, 8f); lineTo(56f, 28f); lineTo(50f, 28f); lineTo(50f, 54f)
        lineTo(14f, 54f); lineTo(14f, 28f); lineTo(8f, 28f); close()
    }
    drawPath(casa, c.p)
    drawRoundRect(c.d, topLeft = Offset(26f, 36f), size = Size(12f, 18f), cornerRadius = CornerRadius(3f, 3f))
    drawRoundRect(
        c.d.copy(alpha = 0.4f + 0.6f * onda(t)), topLeft = Offset(19f, 31f), size = Size(6f, 6f),
        cornerRadius = CornerRadius(1.5f, 1.5f)
    )
    drawRoundRect(
        c.d.copy(alpha = 0.4f + 0.6f * onda(t, 0.5f)), topLeft = Offset(39f, 31f), size = Size(6f, 6f),
        cornerRadius = CornerRadius(1.5f, 1.5f)
    )
}

/** Mancuerna que se levanta. */
private fun DrawScope.entrenar(c: Colores, t: Float) {
    val sube = 5f * onda(t)
    rotate(-20f, pivot = Offset(32f, 32f)) {
        translate(0f, -sube) {
            drawRoundRect(c.p, topLeft = Offset(16f, 29f), size = Size(32f, 6f), cornerRadius = CornerRadius(3f, 3f))
            drawRoundRect(c.p, topLeft = Offset(7f, 18f), size = Size(9f, 28f), cornerRadius = CornerRadius(3f, 3f))
            drawRoundRect(c.p, topLeft = Offset(48f, 18f), size = Size(9f, 28f), cornerRadius = CornerRadius(3f, 3f))
            drawRoundRect(c.d, topLeft = Offset(3f, 23f), size = Size(5f, 18f), cornerRadius = CornerRadius(2f, 2f))
            drawRoundRect(c.d, topLeft = Offset(56f, 23f), size = Size(5f, 18f), cornerRadius = CornerRadius(2f, 2f))
        }
    }
}

/** Destellos de IA: estrella grande que respira y gira, y dos chispas por turnos. */
@Suppress("UNUSED_PARAMETER")
private fun DrawScope.ia(c: Colores, t: Float) = robotIa(t)

// v27: ROBOT de la IA (elegido por el usuario): cabeza oscura con borde en
// degradado cian -> verde, antena con bola que brilla, orejas violetas, ojos
// cian que parpadean y sonrisa verde. Colores PROPIOS (es la "marca" de la IA),
// no dependen del color de la tarjeta. Reticula de 64 x 64.
private val ROBOT_CIAN = Color(0xFF22D3EE)
private val ROBOT_VERDE = Color(0xFF4ADE80)
private val ROBOT_VIOLETA = Color(0xFF8B7CF6)
private val ROBOT_CARA = Color(0xFF0E1A22)

/** @param t 0..1 de la animacion (parpadeo, brillo de la antena y vaiven suave). */
internal fun DrawScope.robotIa(t: Float) {
    val vaiven = 1.2f * sin(2f * PI.toFloat() * t)
    translate(top = vaiven) {
        // Antena
        drawLine(ROBOT_VERDE, Offset(32f, 17f), Offset(32f, 10.5f), strokeWidth = 3.2f, cap = StrokeCap.Round)
        drawCircle(ROBOT_VERDE.copy(alpha = 0.25f + 0.35f * onda(t)), 6.5f, Offset(32f, 8f))
        drawCircle(ROBOT_VERDE, 3.8f, Offset(32f, 8f))
        // Orejas
        drawRoundRect(ROBOT_VIOLETA, topLeft = Offset(4.5f, 27f), size = Size(7f, 15f), cornerRadius = CornerRadius(3f, 3f))
        drawRoundRect(ROBOT_VIOLETA, topLeft = Offset(52.5f, 27f), size = Size(7f, 15f), cornerRadius = CornerRadius(3f, 3f))
        // Cabeza: cara oscura + borde en degradado
        val borde = Brush.linearGradient(listOf(ROBOT_CIAN, ROBOT_VERDE), start = Offset(10f, 17f), end = Offset(54f, 54f))
        drawRoundRect(ROBOT_CARA, topLeft = Offset(10f, 17f), size = Size(44f, 37f), cornerRadius = CornerRadius(13f, 13f))
        drawRoundRect(
            borde, topLeft = Offset(10f, 17f), size = Size(44f, 37f), cornerRadius = CornerRadius(13f, 13f),
            style = Stroke(width = 4.6f)
        )
        // Ojos (parpadean al final de cada ciclo)
        val abierto = if (t > 0.92f) 0.15f else 1f
        val ojos = Brush.verticalGradient(listOf(Color(0xFF67E8F9), ROBOT_CIAN), startY = 27f, endY = 37f)
        listOf(24.5f, 39.5f).forEach { x ->
            drawOval(ojos, topLeft = Offset(x - 4.6f, 32f - 4.6f * abierto), size = Size(9.2f, 9.2f * abierto))
        }
        // Sonrisa
        drawArc(
            ROBOT_VERDE, startAngle = 25f, sweepAngle = 130f, useCenter = false,
            topLeft = Offset(24f, 33.5f), size = Size(16f, 11f),
            style = Stroke(width = 3.4f, cap = StrokeCap.Round)
        )
    }
}

/** Corazon con cruz de salud que late suave. */
private fun DrawScope.salud(c: Colores, t: Float) {
    val k = 1f + 0.06f * onda(t)
    scale(k, k, pivot = Offset(32f, 32f)) {
        drawPath(corazonPath(), c.p)
    }
    drawRoundRect(c.d, topLeft = Offset(29f, 22f), size = Size(6f, 20f), cornerRadius = CornerRadius(2f, 2f))
    drawRoundRect(c.d, topLeft = Offset(22f, 29f), size = Size(20f, 6f), cornerRadius = CornerRadius(2f, 2f))
    drawOval(c.brillo, topLeft = Offset(12f, 15f), size = Size(9f, 6f))
}

/** Cara de panda que parpadea. */
private fun DrawScope.perfil(c: Colores, t: Float) {
    // Orejas
    drawCircle(c.d, 8f, Offset(15f, 15f))
    drawCircle(c.d, 8f, Offset(49f, 15f))
    // Cabeza (con borde fino para que se vea tambien sobre fondo claro)
    drawCircle(c.p, 24f, Offset(32f, 35f))
    drawCircle(c.d.copy(alpha = 0.55f), 24f, Offset(32f, 35f), style = Stroke(1.6f))
    // Manchas de los ojos
    drawOval(c.d, topLeft = Offset(17f, 26f), size = Size(11f, 14f))
    drawOval(c.d, topLeft = Offset(36f, 26f), size = Size(11f, 14f))
    // Ojos: se cierran un instante (parpadeo) al final del ciclo
    val abierto = if (t in 0.90f..0.96f) 0.15f else 1f
    drawOval(c.p, topLeft = Offset(20.5f, 31f + 2.5f * (1 - abierto)), size = Size(4.5f, 5f * abierto))
    drawOval(c.p, topLeft = Offset(39f, 31f + 2.5f * (1 - abierto)), size = Size(4.5f, 5f * abierto))
    // Nariz y boca
    drawOval(c.d, topLeft = Offset(28.5f, 42f), size = Size(7f, 5f))
    drawArc(
        c.d, 20f, 140f, false, topLeft = Offset(27f, 44f), size = Size(10f, 6f),
        style = Stroke(1.8f, cap = StrokeCap.Round)
    )
}
