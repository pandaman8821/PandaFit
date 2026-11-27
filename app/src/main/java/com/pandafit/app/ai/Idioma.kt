// ============================================================================
//  Panda Fit - Idioma.kt
//  0.1.7: idioma de la app = el del telefono, si es uno de los traducidos
//  (espanol, ingles, portugues, frances, italiano, aleman); si no, espanol.
//  Lo usan la voz del telefono, la voz del entrenador y la IA (para que
//  responda en ese idioma).
// ============================================================================
package com.pandafit.app.ai

import java.util.Locale

object Idioma {

    val SOPORTADOS = listOf("es", "en", "pt", "fr", "it", "de")

    /** Codigo del idioma a usar ("es", "en", ...). */
    fun actual(locale: Locale = Locale.getDefault()): String =
        locale.language.lowercase().takeIf { it in SOPORTADOS } ?: "es"

    /** Nombre del idioma para decirselo a la IA ("inglés", "portugués"...). */
    fun nombreParaIa(codigo: String = actual()): String = when (codigo) {
        "en" -> "inglés (English)"
        "pt" -> "portugués (Português)"
        "fr" -> "francés (Français)"
        "it" -> "italiano (Italiano)"
        "de" -> "alemán (Deutsch)"
        else -> "español"
    }
}
