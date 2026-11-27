// ============================================================================
//  Panda Fit - LiquidGlassModifier.kt
//  MOTOR DE LIQUID GLASS (estilo premium 2026) escrito a mano sobre Compose.
//
//  Tecnicas reales incluidas:
//    1. REFRACTION   -> RenderEffect de desenfoque (API 31+) que refracta el
//                       propio panel + doble capa de inclinacion simulando la
//                       superficie curvada de una lente.
//    2. RIM LIGHT    -> borde con gradiente: luz blanca arriba-izquierda y
//                       rebote azul frio abajo-derecha (efecto bisel).
//    3. GLOW LIQUIDO -> halo que respira con animacion infinita y se intensifica
//                       al pulsar (BlendMode por capas).
//    4. TRANSPARENCIA FLUIDA -> relleno con gradiente vertical, especular
//                       interna y banda de refraccion.
//    5. AURA DE FONDO -> tres blobs de color detras del cristal (algo real que
//                       refractar, como en las apps premium).
//
//  Compatible con API 26+: en API < 31 el desenfoque se degrada a una capa
//  translucida (glassmorphism clasico) sin romper nada.
// ============================================================================
package com.pandafit.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
//  1. MODIFIER PRINCIPAL
// ============================================================================

/**
 * Convierte cualquier superficie en cristal liquido.
 *
 * Ejemplo:
 * ```
 * Box(
 *     Modifier
 *         .fillMaxWidth()
 *         .liquidGlass(cornerRadius = 28.dp, glowAccent = PandaFitTheme.colors.steps)
 * ) { Text("Hola Panda Fit") }
 * ```
 *
 * @param cornerRadius radio de la gota de cristal.
 * @param blurRadius   fuerza del desenfoque de refraccion (8-24 recomendado).
 * @param refraction   desplazamiento de la capa interna (simula lente).
 * @param rimLight     intensidad del borde luminoso (0 = sin borde).
 * @param glow         intensidad del halo exterior (0 = sin halo).
 * @param glowAccent   color del halo (por defecto, el del tema).
 * @param tint         tinte extra del cristal (0.0-1.0) para cristales de color.
 * @param breathing    el halo respira lentamente (animacion premium).
 * @param shape        forma exterior (por defecto, RoundedCornerShape).
 */
fun Modifier.liquidGlass(
    cornerRadius: Dp = PandaGlassRadius,
    blurRadius: Dp = 16.dp,
    refraction: Dp = 6.dp,
    rimLight: Float = 1f,
    glow: Float = 0.4f,
    glowAccent: Color = Color.Unspecified,
    tint: Float = 0f,
    breathing: Boolean = true,
    shape: Shape = RoundedCornerShape(cornerRadius)
): Modifier = composed {
    val tokens = PandaFitTheme.colors
    val glowPhase = rememberBreathingGlow(breathing)
    this.buildGlass(
        tokens = tokens,
        shape = shape,
        cornerRadius = cornerRadius,
        blurRadius = blurRadius,
        refraction = refraction,
        rimLight = rimLight,
        glow = glow,
        glowAccent = glowAccent,
        tint = tint,
        glowPhase = glowPhase.value
    )
}

/**
 * Variante del cristal ligada a un [InteractionSource]: el halo y la escala
 * responden al toque (feedback tactil premium).
 */
@Composable
fun Modifier.liquidGlassInteractive(
    interactionSource: InteractionSource,
    cornerRadius: Dp = PandaGlassRadius,
    blurRadius: Dp = 16.dp,
    refraction: Dp = 6.dp,
    glowAccent: Color = Color.Unspecified,
    shape: Shape = RoundedCornerShape(cornerRadius)
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val tokens = PandaFitTheme.colors
    val glowPhase = rememberBreathingGlow(!pressed)
    val scale = if (pressed) 0.975f else 1f
    return this.buildGlass(
        tokens = tokens,
        shape = shape,
        cornerRadius = cornerRadius,
        blurRadius = blurRadius,
        refraction = refraction,
        rimLight = if (pressed) 1.35f else 1f,
        glow = if (pressed) 0.85f else 0.4f,
        glowAccent = glowAccent,
        tint = if (pressed) 0.06f else 0f,
        glowPhase = glowPhase.value
    ).graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// ============================================================================
//  2. IMPLEMENTACION
// ============================================================================

/** Estado interno del panel de cristal (mutaciones durante el rebuild). */
@Immutable
private class GlassSpec(
    var shape: Shape,
    var cornerRadius: Dp,
    var blurRadius: Dp,
    var refraction: Dp,
    var rimLight: Float,
    var glow: Float,
    var glowAccent: Color,
    var tint: Float,
    var glowPhase: Float
)

private fun Modifier.buildGlass(
    tokens: PandaFitColors,
    shape: Shape,
    cornerRadius: Dp,
    blurRadius: Dp,
    refraction: Dp,
    rimLight: Float,
    glow: Float,
    glowAccent: Color,
    tint: Float,
    glowPhase: Float
): Modifier {
    val spec = GlassSpec(
        shape = shape,
        cornerRadius = cornerRadius,
        blurRadius = blurRadius,
        refraction = refraction,
        rimLight = rimLight,
        glow = glow,
        glowAccent = glowAccent,
        tint = tint,
        glowPhase = glowPhase
    )

    val accent = if (spec.glowAccent == Color.Unspecified) tokens.steps else spec.glowAccent

    // ========================================================================
    //  IMPORTANTE (correccion de un fallo real):
    //
    //  Antes se aplicaba `.graphicsLayer { renderEffect = createBlurEffect(...) }`
    //  y `.shadow(...)` a TODA la capa del cristal. Como el contenido (texto,
    //  ejercicios, graficas) vive en esa misma capa, el desenfoque difuminaba
    //  tambien el texto: en pantalla se veia todo borroso e ilegible.
    //
    //  Ahora el desenfoque NO se aplica a la capa: se simula con el propio
    //  relleno del vidrio (alpha segun la intensidad del cristal) y el volumen
    //  se consigue dibujando el halo DENTRO del lienzo, con trazos concentricos.
    //  Resultado: cristal con brillo, borde luminoso y glow... y TEXTO NITIDO.
    // ========================================================================

    // "blurRadius" se conserva como parametro de la API publica y ahora expresa
    // la INTENSIDAD del vidrio (0.0 = mas transparente, 1.0 = mas esmerilado).
    //
    // AJUSTE DE LEGIBILIDAD: antes el velo se multiplicaba por 0.42 y las
    // tarjetas dejaban pasar tanto el fondo que el texto perdia contraste. Ahora
    // el vidrio es CASI OPACO (0.72 a 0.92 dp de alpha): se sigue viendo el aura
    // de fondo, pero el contenido se lee perfectamente.
    val glassIntensity = (spec.blurRadius.value / 24f).coerceIn(0f, 1f)
    val fillAlpha = 0.72f + 0.16f * glassIntensity

    // MODO OSCURO: los brillos blancos (especular, franja superior y ovalo de
    // refraccion) estaban pensados para el tema claro. Sobre vidrio oscuro se
    // veian como RECTANGULOS GRISES encima del texto ("modo oscuro hecho un
    // desastre"). En oscuro se reducen a un reflejo sutil.
    val oscuro = tokens.glassTop.luminance() < 0.4f
    val brillo = if (oscuro) 0.22f else 1f

    val glowWidth = if (spec.glow > 0f) (5f + 9f * spec.glow) else 0f

    // v12 (diseno deportivo oscuro): SUPERFICIE PLANA. Sin reflejos, sin halo,
    // sin lente: relleno opaco de tarjeta + borde fino. Al pulsar, un leve tinte
    // del color de la metrica (tint) da la respuesta tactil.
    val tarjeta = if (spec.tint > 0f) lerp(tokens.glassTop, accent, spec.tint * 1.6f) else tokens.glassTop
    return this
        .clip(spec.shape)
        .drawWithCache {
            val path = roundedRectPath(size, spec.cornerRadius.toPx())
            val borde = 1.dp.toPx()
            onDrawWithContent {
                drawPath(path, tarjeta)
                if (spec.rimLight > 0f) {
                    drawPath(path, tokens.glassRim, style = Stroke(width = borde))
                }
                drawContent()
            }
        }
}
/** Halo que respira (0..1) cuando [breathing] esta activo. */
@Composable
private fun rememberBreathingGlow(breathing: Boolean): State<Float> = if (breathing) {
    rememberInfiniteTransition(label = "pandaGlassGlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPhase"
    )
} else {
    // El estado se recuerda para no crear objetos durante la composicion.
    remember { androidx.compose.runtime.mutableFloatStateOf(0.35f) }
}

// ============================================================================
//  3. AURA DE FONDO
// ============================================================================

/**
 * Aura liquida de fondo: tres blobs de color que dan al cristal algo real que
 * refractar. Se aplica al contenedor raiz de la pantalla.
 *
 * Es @Composable porque lee los tokens del tema (cambian con claro/oscuro).
 */
@Composable
fun Modifier.liquidAura(accent: Color = Color.Unspecified): Modifier {
    // v12: diseno deportivo oscuro = fondo liso, sin manchas de color.
    @Suppress("UNUSED_VARIABLE") val sinUso = accent
    return this
}

// ============================================================================
//  4. UTILIDADES
// ============================================================================

private fun roundedRectPath(size: Size, radius: Float): Path = Path().apply {
    addRoundRect(
        RoundRect(
            rect = Rect(Offset.Zero, size),
            cornerRadius = CornerRadius(radius, radius)
        )
    )
}

/** Borde de cristal listo para usar con Material 3 (BorderStroke). */
fun glassBorderStroke(width: Dp = 1.dp): BorderStroke = BorderStroke(
    width,
    Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x33FFFFFF)))
)
