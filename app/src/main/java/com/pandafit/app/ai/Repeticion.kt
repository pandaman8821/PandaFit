// ============================================================================
//  Panda Fit - Repeticion.kt
//  0.1.1: ayuda a la IA a NO REPETIRSE. Mira sus ultimas respuestas y le
//  recuerda (en el contexto, invisible para el usuario):
//    * como empezo cada una (para que la siguiente empiece distinto),
//    * las palabras que ya uso demasiado (para que busque sinonimos).
//  Funcion pura: se prueba sin telefono.
// ============================================================================
package com.pandafit.app.ai

import java.text.Normalizer

object Repeticion {

    /** Palabras comunes que no cuentan como "repetidas" (articulos, verbos basicos...). */
    private val COMUNES = setOf(
        "porque", "cuando", "donde", "tambien", "despues", "antes", "puedes", "puede", "hacer", "estas",
        "estan", "tienes", "tiene", "sobre", "entre", "hasta", "desde", "mucho", "mucha", "muchos", "poco",
        "algo", "alguna", "algun", "todos", "todas", "todo", "cada", "otro", "otra", "otros", "otras", "mismo",
        "misma", "pasos", "sueno", "pulso", "minutos", "horas", "noche", "manana", "semana", "datos", "meta",
        "ahora", "luego", "bien", "mejor", "quieres", "tienes", "siempre", "nunca", "hacia", "segun", "estos",
        "estas", "esto", "esta", "este", "dormiste", "llevas", "aunque", "entonces", "mientras", "panda"
    )

    private fun sinTildes(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Comienzo de una respuesta (primeras palabras, sin markdown). */
    fun comienzo(texto: String, palabras: Int = 6): String =
        texto.replace(Regex("[*_#>`]"), "").trim().split(Regex("\\s+")).take(palabras).joinToString(" ")

    /** Palabras (5+ letras) que aparecen en 3 o mas de las respuestas dadas. */
    fun muyUsadas(respuestas: List<String>, minimo: Int = 3): List<String> {
        val conteo = mutableMapOf<String, Int>()
        respuestas.forEach { r ->
            Regex("\\p{L}{5,}").findAll(r).map { sinTildes(it.value) }.filter { it !in COMUNES }.toSet()
                .forEach { conteo[it] = (conteo[it] ?: 0) + 1 }
        }
        return conteo.filter { it.value >= minimo }.entries.sortedByDescending { it.value }.map { it.key }.take(12)
    }

    /** Bloque para el contexto ("" si no hay suficientes respuestas previas). */
    fun aviso(respuestas: List<String>): String {
        if (respuestas.size < 2) return ""
        val inicios = respuestas.takeLast(4).map { comienzo(it) }.filter { it.isNotBlank() }
        val usadas = muyUsadas(respuestas)
        return buildString {
            appendLine()
            appendLine("[PARA NO REPETIRTE]")
            appendLine("Así empezaste tus últimas respuestas: " + inicios.joinToString(" | ") { "\"$it\"" } +
                ". Empieza esta de otra forma.")
            if (usadas.isNotEmpty()) {
                appendLine("Palabras que ya usaste demasiado (busca sinónimos u otra forma de decirlo): " +
                    usadas.joinToString(", ") + ".")
            }
        }
    }
}
