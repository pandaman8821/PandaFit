// ============================================================================
//  Panda Fit - PandaFitTheme.kt
//  Tema Material 3 con acabado "Liquid Glass" (2026): azul hielo, menta liquida
//  y violeta aurora sobre tinta profunda. Modo claro/oscuro AUTOMATICO
//  (sigue el ajuste del sistema y puede forzarse del perfil de usuario).
//
//  Incluye:
//    * PandaFitColors  -> tokens extra que Material 3 no tiene (gradientes de
//                         cristal, rim light, glow y colores por metrica).
//    * PandaFitTheme   -> CompositionLocal + tipografia grande (estilo DaFit).
// ============================================================================
package com.pandafit.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ============================================================================
//  1. PALETA BASE PANDA FIT
// ============================================================================

/** Azul hielo: color primario de Panda Fit. */
val PandaIceBlue = Color(0xFF39C8F5)
val PandaIceBlueDeep = Color(0xFF0A7BB8)

/** Menta liquida: exito, pasos completados, salud. */
val PandaMint = Color(0xFF2FDCA5)
val PandaMintDeep = Color(0xFF0E9C74)

/** Violeta aurora: acentos del asistente IA. */
val PandaViolet = Color(0xFF9B7BFF)
val PandaVioletDeep = Color(0xFF6B45E0)

/** Ambar/rosa: alertas suaves y metricas de sueno. */
val PandaAmber = Color(0xFFFFC46B)
val PandaRose = Color(0xFFFF7CA8)

/** Tinta profunda: fondo oscuro premium (no negro puro: mas "cristal"). */
val PandaInk = Color(0xFF070B18)
val PandaInkSoft = Color(0xFF0E1428)
val PandaInkElevated = Color(0xFF151C36)

/** Superficie clara: blanco humo con velo azulado. */
val PandaMist = Color(0xFFF4F8FF)
val PandaMistElevated = Color(0xFFFFFFFF)

// --- Esquema OSCURO ---------------------------------------------------------
private val PandaDarkScheme = darkColorScheme(
    primary = PandaIceBlue,
    onPrimary = Color(0xFF00202E),
    primaryContainer = PandaIceBlueDeep,
    onPrimaryContainer = Color(0xFFD6F4FF),
    secondary = PandaMint,
    onSecondary = Color(0xFF00281C),
    secondaryContainer = PandaMintDeep,
    onSecondaryContainer = Color(0xFFCFFBEC),
    tertiary = PandaViolet,
    onTertiary = Color(0xFF180046),
    tertiaryContainer = PandaVioletDeep,
    onTertiaryContainer = Color(0xFFEDE5FF),
    background = PandaInk,
    onBackground = Color(0xFFE8EEFF),
    surface = PandaInkSoft,
    onSurface = Color(0xFFE8EEFF),
    surfaceVariant = Color(0xFF1C2544),
    onSurfaceVariant = Color(0xFFB9C6E6),
    surfaceContainer = PandaInkElevated,
    surfaceContainerHigh = Color(0xFF1A2245),
    surfaceContainerLow = PandaInkSoft,
    outline = Color(0xFF7C8CB5),
    outlineVariant = Color(0xFF2A3560),
    error = Color(0xFFFF7A87),
    onError = Color(0xFF3A0009),
    errorContainer = Color(0xFF5C1220),
    onErrorContainer = Color(0xFFFFDAD9),
    inverseSurface = Color(0xFFE8EEFF),
    inverseOnSurface = PandaInkSoft,
    scrim = Color(0xFF000000)
)

// --- Esquema CLARO ----------------------------------------------------------
private val PandaLightScheme = lightColorScheme(
    primary = PandaIceBlueDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9EDFF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = PandaMintDeep,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2F5E2),
    onSecondaryContainer = Color(0xFF00251A),
    tertiary = PandaVioletDeep,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7DEFF),
    onTertiaryContainer = Color(0xFF1C0060),
    background = PandaMist,
    onBackground = Color(0xFF0C1424),
    surface = PandaMistElevated,
    onSurface = Color(0xFF0C1424),
    surfaceVariant = Color(0xFFE1E8F7),
    onSurfaceVariant = Color(0xFF44506B),
    surfaceContainer = Color(0xFFEDF2FC),
    surfaceContainerHigh = Color(0xFFE6ECF9),
    surfaceContainerLow = Color(0xFFF7FAFF),
    outline = Color(0xFF77839D),
    outlineVariant = Color(0xFFC9D3E8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF0C1424),
    inverseOnSurface = Color(0xFFEFF2F9),
    scrim = Color(0xFF000000)
)

// ============================================================================
//  2. TOKENS LIQUID GLASS (lo que Material 3 no trae)
// ============================================================================

@Immutable
data class PandaFitColors(
    /** Tinte superior del cristal (refraccion de luz de estudio). */
    val glassTop: Color,
    /** Tinte inferior del cristal (sombra refractada). */
    val glassBottom: Color,
    /** Borde luminoso superior (rim light). */
    val glassRim: Color,
    /** Borde inferior frio. */
    val glassRimCold: Color,
    /** Halo exterior (glow liquido). */
    val glassGlow: Color,
    /** Aura de fondo (blobs de color detras del cristal). */
    val auraA: Color,
    val auraB: Color,
    val auraC: Color,
    /** Texto secundario sobre cristal. */
    val onGlassMuted: Color,
    /** Color por metrica (tarjetas del panel). */
    val steps: Color,
    val distance: Color,
    val calories: Color,
    val sleep: Color,
    val heart: Color,
    val spo2: Color
)

private val DarkGlass = PandaFitColors(
    glassTop = Color(0x33FFFFFF),
    glassBottom = Color(0x0DFFFFFF),
    glassRim = Color(0x99FFFFFF),
    glassRimCold = Color(0x3D9BD8FF),
    glassGlow = Color(0x552FB8F0),
    auraA = Color(0xFF1E5BFF),
    auraB = Color(0xFF00D6A4),
    auraC = Color(0xFF9B4DFF),
    onGlassMuted = Color(0xB3DCE6FF),
    steps = PandaIceBlue,
    distance = PandaMint,
    calories = PandaAmber,
    sleep = PandaViolet,
    heart = PandaRose,
    spo2 = Color(0xFF63E6FF)
)

private val LightGlass = PandaFitColors(
    glassTop = Color(0xCCFFFFFF),
    glassBottom = Color(0x66EAF3FF),
    glassRim = Color(0xFFFFFFFF),
    glassRimCold = Color(0x6687C9FF),
    glassGlow = Color(0x332A9BE8),
    auraA = Color(0xFF7FC4FF),
    auraB = Color(0xFF8AF0D2),
    auraC = Color(0xFFC3AEFF),
    onGlassMuted = Color(0xCC3A4A6B),
    steps = PandaIceBlueDeep,
    distance = PandaMintDeep,
    calories = Color(0xFFD98A17),
    sleep = PandaVioletDeep,
    heart = Color(0xFFD6457A),
    spo2 = Color(0xFF0E86A8)
)

// ============================================================================
//  DISENO "DEPORTIVO OSCURO" (v12) - sustituye al Liquid Glass
//  Fondo casi negro, tarjetas grafito planas, bordes finos y un color vivo por
//  metrica. Sin cristal, sin halos, sin auras: superficies planas y cifras grandes.
// ============================================================================
val SportInk = Color(0xFF0E1014)
val SportCard = Color(0xFF171A20)
val SportCardHigh = Color(0xFF1F232B)
val SportLine = Color(0xFF2A2E36)
val SportLime = Color(0xFF9BE15D)
val SportText = Color(0xFFF3F4F6)
val SportMuted = Color(0xFF8B93A1)

private val SportScheme = darkColorScheme(
    primary = SportLime,
    onPrimary = Color(0xFF173404),
    primaryContainer = Color(0xFF27500A),
    onPrimaryContainer = Color(0xFFD9F5BE),
    secondary = Color(0xFFFF5C7A),
    onSecondary = Color(0xFF4B1528),
    secondaryContainer = Color(0xFF72243E),
    onSecondaryContainer = Color(0xFFFBEAF0),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF26215C),
    tertiaryContainer = Color(0xFF3C3489),
    onTertiaryContainer = Color(0xFFEEEDFE),
    background = SportInk,
    onBackground = SportText,
    surface = SportCard,
    onSurface = SportText,
    surfaceVariant = SportCardHigh,
    onSurfaceVariant = SportMuted,
    surfaceContainer = SportCard,
    surfaceContainerHigh = SportCardHigh,
    surfaceContainerLow = SportInk,
    outline = Color(0xFF3A3F49),
    outlineVariant = SportLine,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0009),
    errorContainer = Color(0xFF5C1220),
    onErrorContainer = Color(0xFFFFDAD9),
    inverseSurface = SportText,
    inverseOnSurface = SportInk,
    scrim = Color(0xFF000000)
)

private val SportTokens = PandaFitColors(
    // "Cristal" ahora = tarjeta plana y opaca con borde fino.
    glassTop = SportCard,
    glassBottom = SportCard,
    glassRim = SportLine,
    glassRimCold = SportLine,
    glassGlow = Color.Transparent,
    auraA = SportInk,
    auraB = SportInk,
    auraC = SportInk,
    onGlassMuted = SportMuted,
    steps = SportLime,
    distance = Color(0xFF38D3E8),
    calories = Color(0xFFFF9F43),
    sleep = Color(0xFFA78BFA),
    heart = Color(0xFFFF5C7A),
    spo2 = Color(0xFF5B9CFF)
)

/** Acceso a los tokens Liquid Glass desde cualquier composable. */
val LocalPandaFitColors = staticCompositionLocalOf { DarkGlass }

/** Objeto-companero para leer los tokens con `PandaFitTheme.colors`. */
object PandaFitTheme {
    val colors: PandaFitColors
        @Composable get() = LocalPandaFitColors.current
}

// ============================================================================
//  3. TIPOGRAFIA GRANDE (estilo DaFit / AgeFit: legible de un vistazo)
// ============================================================================
private val PandaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 60.sp, lineHeight = 64.sp, letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 23.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 22.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp
    )
)

// ============================================================================
//  4. TEMA
// ============================================================================

/**
 * Tema de Panda Fit.
 *
 * @param darkTheme     modo oscuro. Por defecto sigue al sistema (automatico).
 * @param dynamicColor  si es true usa los colores del fondo de pantalla en
 *                      Android 12+ (se puede desactivar desde Perfil).
 * @param content       contenido de la app.
 */
@Composable
fun PandaFitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // v12: diseno DEPORTIVO OSCURO fijo. Ni el modo claro ni los colores del
    // fondo de pantalla (Android 12+) lo sustituyen: romperian la paleta.
    @Suppress("UNUSED_VARIABLE") val ignorados = darkTheme to dynamicColor
    val colorScheme = SportScheme
    val glassTokens = SportTokens
    val oscuro = true

    // Barra de estado / navegacion transparentes con iconos del color correcto.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .apply { isAppearanceLightStatusBars = !oscuro; isAppearanceLightNavigationBars = !oscuro }
        }
    }

    CompositionLocalProvider(LocalPandaFitColors provides glassTokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PandaTypography,
            shapes = PandaShapes,
            content = content
        )
    }
}

// ============================================================================
//  5. AYUDAS DE DIBUJO (brushes reutilizables por el motor de cristal)
// ============================================================================

/** Gradiente vertical del vidrio: brillo arriba, sombra abajo. */
fun glassFillBrush(colors: PandaFitColors): Brush = Brush.verticalGradient(
    0.0f to colors.glassTop,
    0.45f to colors.glassTop.copy(alpha = colors.glassTop.alpha * 0.45f),
    1.0f to colors.glassBottom
)

/** Rim light: blanco arriba-izquierda -> azul frio abajo-derecha. */
fun glassRimBrush(colors: PandaFitColors): Brush = Brush.linearGradient(
    0.0f to colors.glassRim,
    0.35f to colors.glassRim.copy(alpha = 0.18f),
    0.75f to colors.glassRimCold.copy(alpha = 0.22f),
    1.0f to colors.glassRim
)

/** Halo de glow liquido alrededor de un boton grande. */
fun glassGlowBrush(colors: PandaFitColors, accent: Color): Brush = Brush.radialGradient(
    colors = listOf(accent.copy(alpha = 0.45f), colors.glassGlow.copy(alpha = 0.0f)),
    radius = 420f
)

/** Aura de fondo: tres blobs que se mueven muy lento tras el cristal. */
fun auraBrush(colors: PandaFitColors, phase: Float): Brush = Brush.radialGradient(
    colors = listOf(
        colors.auraA.copy(alpha = 0.55f),
        colors.auraB.copy(alpha = 0.38f),
        colors.auraC.copy(alpha = 0.30f),
        Color.Transparent
    ),
    center = androidx.compose.ui.geometry.Offset(
        x = 220f + 180f * kotlin.math.cos(phase),
        y = 320f + 260f * kotlin.math.sin(phase * 0.8f)
    ),
    radius = 900f
)

/** Radio estandar de las tarjetas de cristal (botones grandes DaFit). */
val PandaGlassRadius = androidx.compose.ui.unit.Dp(28f)
val PandaGlassRadiusLarge = androidx.compose.ui.unit.Dp(36f)
val PandaScreenPadding = androidx.compose.ui.unit.Dp(20f)
