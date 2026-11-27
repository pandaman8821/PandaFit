// ============================================================================
//  Panda Fit - PandaShapes.kt
//  Formas Material 3 redondeadas y "grandes" (estilo DaFit / AgeFit).
//  Un cristal liquido necesita radios amplios para verse como una gota.
// ============================================================================
package com.pandafit.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val PandaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)
