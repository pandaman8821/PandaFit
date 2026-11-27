// ============================================================================
//  Panda Fit - Limite.kt
//  0.1.2: lee la respuesta 429 ("limite de uso") de Google/OpenAI/DeepSeek:
//    * cuantos segundos pide esperar (retryDelay / "try again in 20s"),
//    * si el limite es POR MINUTO (se pasa esperando) o POR DIA,
//    * el mensaje legible del proveedor (para mostrarlo tal cual, corto).
//  Funciones puras: se prueban sin red.
// ============================================================================
package com.pandafit.app.ai

object Limite {

    /** Segundos que el proveedor pide esperar, o null si no lo dice. */
    fun segundosDeEspera(cuerpo: String): Int? {
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(cuerpo)?.let { return it.groupValues[1].toInt() }
        Regex("(?i)(?:try again|retry) in (\\d+(?:\\.\\d+)?)\\s*s").find(cuerpo)?.let {
            return it.groupValues[1].toDouble().toInt() + 1
        }
        Regex("(?i)retry in (\\d+(?:\\.\\d+)?)ms").find(cuerpo)?.let { return 1 }
        return null
    }

    /** true si el limite agotado es el DIARIO (no se arregla esperando un minuto). */
    fun esDiario(cuerpo: String): Boolean =
        cuerpo.contains("PerDay", true) || cuerpo.contains("per day", true) || cuerpo.contains("daily", true) ||
            cuerpo.contains("RequestsPerDay", true)

    /** Mensaje legible del proveedor (campo "message"), recortado. */
    fun mensajeProveedor(cuerpo: String): String? =
        Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(cuerpo)?.groupValues?.get(1)
            ?.replace("\\n", " ")?.replace("\\\"", "\"")?.trim()?.take(160)?.takeIf { it.isNotBlank() }
}
