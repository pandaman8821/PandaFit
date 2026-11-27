// ============================================================================
//  Panda Fit - PandaGlassComponents.kt
//  KIT DE COMPONENTES "LIQUID GLASS" reutilizables (estilo DaFit + AgeFit):
//  botones GRANDES, tarjetas de cristal, chips, anillo de progreso, barras y
//  campos de texto. Todo con tipografia grande y transiciones suaves.
// ============================================================================
package com.pandafit.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.ui.theme.PandaGlassRadius
import com.pandafit.app.ui.theme.PandaGlassRadiusLarge
import com.pandafit.app.ui.theme.glassRimBrush
import com.pandafit.app.ui.theme.liquidGlass
import com.pandafit.app.ui.theme.liquidGlassInteractive

// ============================================================================
//  TARJETA DE CRISTAL
// ============================================================================
/**
 * Contenedor de cristal liquido. Es la unidad basica de toda la interfaz.
 */
@Composable
fun PandaGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = PandaGlassRadius,
    glowAccent: Color = Color.Unspecified,
    glow: Float = 0.32f,
    padding: Dp = 18.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .liquidGlass(
                cornerRadius = cornerRadius,
                glow = glow,
                glowAccent = glowAccent
            )
            .padding(padding)
    ) {
        content()
    }
}

// ============================================================================
//  BOTON GRANDE PRINCIPAL (estilo DaFit)
// ============================================================================
/**
 * Boton gigante con cristal, halo de color y animacion al pulsar.
 * Es el protagonista de la pantalla principal.
 */
@Composable
fun PandaBigGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = Color.Unspecified,
    height: Dp = 76.dp,
    /** Icono propio animado (tiene prioridad sobre [icon]). */
    glyph: PandaGlyph? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val tokens = PandaFitTheme.colors
    val color = if (accent == Color.Unspecified) tokens.steps else accent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .liquidGlassInteractive(
                interactionSource = interaction,
                cornerRadius = PandaGlassRadiusLarge,
                glowAccent = color
            )
            .clip(RoundedCornerShape(PandaGlassRadiusLarge))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null || glyph != null) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (glyph != null) {
                        PandaGlyphIcon(glyph = glyph, accent = MaterialTheme.colorScheme.surface, principal = color, size = 32.dp)
                    } else if (icon != null) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                // titleMedium (20sp) + ajuste automatico del tamano: con el texto
                // grande anterior, "Entrenar" y "Sincronizar" se cortaban en
                // pantallas estrechas.
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Flecha: deja claro que la tarjeta entera se puede pulsar.
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                fontSize = 30.sp,
                color = color.copy(alpha = 0.85f),
                fontWeight = FontWeight.Light
            )
        }
    }
}

/** Boton de cristal pequeno (acciones secundarias, chips de accion). */
@Composable
fun PandaGlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    accent: Color = Color.Unspecified
) {
    val interaction = remember { MutableInteractionSource() }
    val tokens = PandaFitTheme.colors
    val color = if (accent == Color.Unspecified) tokens.steps else accent
    val background by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.28f) else Color.Transparent,
        animationSpec = tween(220),
        label = "pillBg"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(
                BorderStroke(1.dp, color.copy(alpha = if (selected) 0.75f else 0.35f)),
                RoundedCornerShape(50)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Centrado: cuando la pildora ocupa media fila (weight) el texto no
        // queda pegado a la izquierda.
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
//  TARJETA DE METRICA (con anillo de progreso opcional)
// ============================================================================
@Composable
fun PandaMetricCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    footer: String? = null,
    onClick: (() -> Unit)? = null,
    /** Icono propio animado (tiene prioridad sobre [icon]). */
    glyph: PandaGlyph? = null
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    PandaGlassCard(
        modifier = modifier.then(clickModifier),
        cornerRadius = 26.dp,
        glowAccent = accent,
        glow = 0.22f,
        padding = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.16f)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (glyph != null) {
                        PandaGlyphIcon(glyph = glyph, accent = MaterialTheme.colorScheme.surface, principal = accent, size = 28.dp)
                    } else {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(12.dp))
            if (progress != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    PandaProgressRing(
                        progress = progress,
                        accent = accent,
                        modifier = Modifier.size(54.dp),
                        strokeWidth = 6.dp
                    ) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // La cifra ocupa el resto del ancho y se AJUSTA SOLA al espacio
                    // real (TextAutoSize): "9,548" o "12,345" caben en cualquier movil.
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = value,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 12.sp,
                                maxFontSize = 28.sp,
                                stepSize = 1.sp
                            )
                        )
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            if (footer != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================================
//  ANILLO DE PROGRESO CON GLOW
// ============================================================================
@Composable
fun PandaProgressRing(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
    trackColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(900),
        label = "ringProgress"
    )
    val tokens = PandaFitTheme.colors
    val track = if (trackColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    } else trackColor

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)

            // Pista
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // v12: progreso de color SOLIDO, sin halo (diseno deportivo plano).
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        content()
    }
}

// ============================================================================
//  BARRA DE PROGRESO DE CRISTAL
// ============================================================================
@Composable
fun PandaProgressBar(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(800),
        label = "barProgress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
    }
}

// ============================================================================
//  CABECERA DE SECCION
// ============================================================================
@Composable
fun PandaSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

// ============================================================================
//  CAMPO DE TEXTO DE CRISTAL (usado por el chat y por Ajustes)
// ============================================================================
@Composable
fun PandaGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Send,
    onImeAction: () -> Unit = {},
    shape: Shape = RoundedCornerShape(50),
    trailing: @Composable (() -> Unit)? = null
) {
    val tokens = PandaFitTheme.colors
    Row(
        modifier = modifier
            .clip(shape)
            .background(tokens.glassTop.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, tokens.glassRim.copy(alpha = 0.35f)), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onSend = { onImeAction() },
                    onDone = { onImeAction() },
                    onGo = { onImeAction() }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

// ============================================================================
//  FILA DE DATO (etiqueta + valor) usada en resumenes
// ============================================================================
@Composable
fun PandaDataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified
) {
    val color = if (accent == Color.Unspecified) MaterialTheme.colorScheme.primary else accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

// ============================================================================
//  AVISO DE PRIVACIDAD (cristal con borde suave)
// ============================================================================
@Composable
fun PandaPrivacyBanner(text: String, modifier: Modifier = Modifier) {
    val tokens = PandaFitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tokens.glassTop.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, tokens.glassRimCold.copy(alpha = 0.30f)), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = "\uD83D\uDD12", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

/** Etiqueta de valor grande con subtitulo (cabecera del panel). */
@Composable
fun PandaStatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified
) {
    val color = if (accent == Color.Unspecified) MaterialTheme.colorScheme.onSurface else accent
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Gradiente de cristal reutilizable (para fondos de tarjetas especiales). */
@Composable
fun glassPanelBrush(accent: Color): Brush {
    val tokens = PandaFitTheme.colors
    return Brush.linearGradient(
        listOf(
            accent.copy(alpha = 0.30f),
            tokens.glassTop.copy(alpha = 0.12f),
            Color.Transparent
        )
    )
}

/** Espaciador vertical corto de uso comun. */
@Composable
fun PandaSpacer(height: Dp = 14.dp) = Spacer(Modifier.height(height))

/** Icono dentro de una burbuja de cristal (cabeceras de pantalla). */
@Composable
fun PandaIconBubble(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.40f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** Texto con pesos grandes para los titulos de pantalla. */
@Composable
fun PandaScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Black
    )
}
