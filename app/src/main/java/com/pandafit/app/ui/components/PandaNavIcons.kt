// ============================================================================
//  Panda Fit - PandaNavIcons.kt
//  v23: iconos de la barra inferior, estilo "premium" (como los de iOS / apps
//  deportivas de gama alta): trazo fino y limpio en reposo, version RELLENA al
//  seleccionar, con transicion suave entre ambas. Dibujados en una reticula
//  de 24 x 24 para que las proporciones sean exactas en cualquier pantalla.
// ============================================================================
package com.pandafit.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NavIcono { INICIO, ENTRENAR, IA, SALUD, PERFIL }

@Composable
fun PandaNavIcon(
    icono: NavIcono,
    seleccionado: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = null
) {
    // 0 = contorno, 1 = relleno. Se mezclan durante la transicion.
    val relleno by animateFloatAsState(
        targetValue = if (seleccionado) 1f else 0f,
        animationSpec = tween(260),
        label = "navFill"
    )
    Canvas(
        modifier = modifier
            .size(size)
            // Capa propia: los "huecos" (puerta, linea de pulso...) se recortan
            // con BlendMode.Clear sin borrar el fondo de la barra.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .then(
                if (contentDescription != null)
                    Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            )
    ) {
        val u = this.size.minDimension / 24f
        val trazo = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (relleno < 1f) dibujar(icono, u, color.copy(alpha = color.alpha * (1f - relleno)), trazo, false)
        if (relleno > 0f) dibujar(icono, u, color.copy(alpha = color.alpha * relleno), trazo, true)
    }
}

private fun p(u: Float, x: Float, y: Float) = Offset(x * u, y * u)

private fun DrawScope.dibujar(icono: NavIcono, u: Float, c: Color, trazo: Stroke, lleno: Boolean) {
    val estilo = if (lleno) Fill else trazo
    val hueco = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icono) {
        NavIcono.INICIO -> {
            val casa = Path().apply {
                moveTo(3.5f * u, 10.4f * u)
                lineTo(12f * u, 3.6f * u)
                lineTo(20.5f * u, 10.4f * u)
                lineTo(20.5f * u, 19f * u)
                quadraticTo(20.5f * u, 20.5f * u, 19f * u, 20.5f * u)
                lineTo(5f * u, 20.5f * u)
                quadraticTo(3.5f * u, 20.5f * u, 3.5f * u, 19f * u)
                close()
            }
            if (lleno) {
                drawPath(casa, c, style = Fill)
                drawPath(casa, c, style = trazo)   // esquinas redondeadas igual que el contorno
                drawRoundRect(
                    color = Color.Black, topLeft = p(u, 10f, 14f), size = Size(4f * u, 7.5f * u),
                    cornerRadius = CornerRadius(1.2f * u), blendMode = BlendMode.Clear
                )
            } else {
                drawPath(casa, c, style = trazo)
                drawLine(c, p(u, 10f, 20.5f), p(u, 10f, 15.2f), trazo.width, StrokeCap.Round)
                drawLine(c, p(u, 10f, 15.2f), p(u, 14f, 15.2f), trazo.width, StrokeCap.Round)
                drawLine(c, p(u, 14f, 15.2f), p(u, 14f, 20.5f), trazo.width, StrokeCap.Round)
            }
        }

        NavIcono.ENTRENAR -> {
            // Mancuerna: barra + dos discos grandes + topes.
            drawLine(c, p(u, 7.5f, 12f), p(u, 16.5f, 12f), trazo.width * 1.1f, StrokeCap.Round)
            val r = CornerRadius(1.4f * u)
            drawRoundRect(c, p(u, 4.5f, 6.5f), Size(3f * u, 11f * u), r, style = estilo)
            drawRoundRect(c, p(u, 16.5f, 6.5f), Size(3f * u, 11f * u), r, style = estilo)
            drawLine(c, p(u, 2.2f, 9.5f), p(u, 2.2f, 14.5f), trazo.width, StrokeCap.Round)
            drawLine(c, p(u, 21.8f, 9.5f), p(u, 21.8f, 14.5f), trazo.width, StrokeCap.Round)
        }

        NavIcono.IA -> {
            // v27: el robot de la IA a todo color (es la "marca" de la IA).
            // Se dibuja una sola vez (en la capa rellena o, si no, en la de contorno).
            if (lleno || c.alpha > 0.5f) {
                val k = 24f * u / 64f
                scale(k * 1.12f, k * 1.12f, pivot = Offset.Zero) { translate(left = -3.4f, top = 0.5f) { robotIa(0.25f) } }
            }
        }

        NavIcono.SALUD -> {
            val corazon = Path().apply {
                moveTo(12f * u, 20.3f * u)
                cubicTo(5.2f * u, 15.6f * u, 2.6f * u, 12.2f * u, 2.6f * u, 8.6f * u)
                cubicTo(2.6f * u, 5.6f * u, 4.9f * u, 3.6f * u, 7.6f * u, 3.6f * u)
                cubicTo(9.5f * u, 3.6f * u, 11f * u, 4.6f * u, 12f * u, 6.2f * u)
                cubicTo(13f * u, 4.6f * u, 14.5f * u, 3.6f * u, 16.4f * u, 3.6f * u)
                cubicTo(19.1f * u, 3.6f * u, 21.4f * u, 5.6f * u, 21.4f * u, 8.6f * u)
                cubicTo(21.4f * u, 12.2f * u, 18.8f * u, 15.6f * u, 12f * u, 20.3f * u)
                close()
            }
            val pulso = Path().apply {
                moveTo(6.2f * u, 11.2f * u)
                lineTo(9f * u, 11.2f * u)
                lineTo(10.4f * u, 8.6f * u)
                lineTo(12.8f * u, 13.6f * u)
                lineTo(14.2f * u, 11.2f * u)
                lineTo(17.8f * u, 11.2f * u)
            }
            if (lleno) {
                drawPath(corazon, c, style = Fill)
                drawPath(pulso, Color.Black, style = hueco, blendMode = BlendMode.Clear)
            } else {
                drawPath(corazon, c, style = trazo)
                drawPath(pulso, c, style = trazo)
            }
        }

        NavIcono.PERFIL -> {
            drawCircle(c, radius = 4.1f * u, center = p(u, 12f, 7.9f), style = estilo)
            val hombros = Path().apply {
                moveTo(4f * u, 20.4f * u)
                cubicTo(4f * u, 16.2f * u, 7.6f * u, 13.8f * u, 12f * u, 13.8f * u)
                cubicTo(16.4f * u, 13.8f * u, 20f * u, 16.2f * u, 20f * u, 20.4f * u)
                if (lleno) close()
            }
            if (lleno) {
                drawPath(hombros, c, style = Fill)
                drawPath(hombros, c, style = trazo)
            } else {
                drawPath(hombros, c, style = trazo)
            }
        }
    }
}

/** Estrella de 4 puntas con lados concavos (curvas hacia el centro). */
private fun destello(u: Float, cx: Float, cy: Float, r: Float): Path {
    val k = r * 0.18f   // "cintura": cuanto mas pequeno, mas afilado
    return Path().apply {
        moveTo(cx * u, (cy - r) * u)
        quadraticTo((cx + k) * u, (cy - k) * u, (cx + r) * u, cy * u)
        quadraticTo((cx + k) * u, (cy + k) * u, cx * u, (cy + r) * u)
        quadraticTo((cx - k) * u, (cy + k) * u, (cx - r) * u, cy * u)
        quadraticTo((cx - k) * u, (cy - k) * u, cx * u, (cy - r) * u)
        close()
    }
}
