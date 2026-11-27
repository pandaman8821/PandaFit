// ============================================================================
//  Panda Fit - ConEnes.kt
//  v27: pone la Ñ en las palabras que a veces llegan sin ella ("sueno",
//  "manana", "pequeno"...) en las respuestas de la IA, antes de mostrarlas y
//  de leerlas en voz alta. Respeta mayusculas.
// ============================================================================
package com.pandafit.app.ai

object ConEnes {

    private val PALABRAS = mapOf(
        "sueno" to "sueño", "suenos" to "sueños",
        "manana" to "mañana", "mananas" to "mañanas",
        "pequeno" to "pequeño", "pequena" to "pequeña", "pequenos" to "pequeños", "pequenas" to "pequeñas",
        "espanol" to "español", "senal" to "señal", "senales" to "señales",
        "anade" to "añade", "anadir" to "añadir", "diseno" to "diseño", "tamano" to "tamaño",
        "companero" to "compañero", "companera" to "compañera"
    )

    private val patron = Regex(
        "(?<![\\p{L}\\d_-])(" + PALABRAS.keys.sortedByDescending { it.length }.joinToString("|") + ")(?![\\p{L}\\d_-])",
        RegexOption.IGNORE_CASE
    )

    fun corregir(texto: String): String = patron.replace(texto) { m ->
        val w = m.value
        val n = PALABRAS[w.lowercase()] ?: return@replace w
        when {
            w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() } -> n.uppercase()
            w[0].isUpperCase() -> n.replaceFirstChar { it.uppercase() }
            else -> n
        }
    }
}
