// ============================================================================
//  Panda Fit - TextoHablado.kt
//  0.0.6: prepara el texto de la IA para LEERLO EN VOZ ALTA bien:
//    * quita markdown, emojis y enlaces; cada punto de una lista es una frase,
//    * lee bien abreviaturas y cifras: "5 km" -> "5 kilometros", "72 ppm" ->
//      "72 pulsaciones por minuto", "113/70" -> "113 sobre 70", "3x12" ->
//      "3 por 12", "8-10" -> "8 a 10", "%", "kcal", "SpO2", "°C"...
//    * NO recorta nada (antes se cortaba a 900 caracteres) y parte las frases
//      muy largas por comas para que el motor de voz no las rechace.
//  Funciones puras: se prueban sin telefono.
// ============================================================================
package com.pandafit.app.ai

object TextoHablado {

    /** Texto listo para la voz, dividido en frases (sin perder ninguna parte). */
    fun frases(texto: String, maxLargo: Int = 280, idioma: String = "es"): List<String> {
        val limpio = normalizar(texto, idioma)
        val frases = Regex("(?<=[.!?;:])\\s+").split(limpio).map { it.trim() }.filter { it.isNotEmpty() }
        return frases.flatMap { partir(it, maxLargo) }
    }

    /** Agrupa frases en bloques de hasta [max] caracteres (para la voz natural). */
    fun bloques(frases: List<String>, primero: Int = 220, max: Int = 700): List<String> {
        val out = mutableListOf<String>()
        val actual = StringBuilder()
        for (f in frases) {
            val limite = if (out.isEmpty()) primero else max
            if (actual.isNotEmpty() && actual.length + f.length + 1 > limite) {
                out += actual.toString(); actual.clear()
            }
            if (actual.isNotEmpty()) actual.append(' ')
            actual.append(f)
        }
        if (actual.isNotEmpty()) out += actual.toString()
        return out
    }

    fun normalizar(texto: String, idioma: String = "es"): String {
        var t = ConEnes.corregir(texto)
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
            .replace(Regex("https?://\\S+"), " ")
        // Linea a linea: listas y titulos se convierten en frases con su pausa.
        t = t.lines().map { linea ->
            var l = linea.trim()
                .replace(Regex("^[-*•·]+\\s*"), "")
                .replace(Regex("^\\d+[.)]\\s+"), "")
                .replace(Regex("^#+\\s*"), "")
                .replace("|", ", ")
                .replace(Regex("[*_`~>#]"), "")
                .trim()
            if (l.isEmpty()) return@map ""
            // TITULOS EN MAYUSCULAS: en minusculas para que no los deletree.
            val letras = l.filter { it.isLetter() }
            if (letras.length > 4 && letras.all { it.isUpperCase() }) {
                l = l.lowercase().replaceFirstChar { it.uppercase() }
            }
            if (l.last() !in ".!?;:,") l += "."
            l
        }.filter { it.isNotEmpty() }.joinToString(" ")
        t = quitarEmojis(t)
        // 0.1.7: "km" -> "kilometros", etc. solo en espanol (en otro idioma mezclaria palabras).
        if (idioma == "es") t = abreviaturas(t)
        return t.replace(Regex("\\s+"), " ").replace(Regex("\\s+([.,;:!?])"), "$1").trim()
    }

    private fun quitarEmojis(t: String): String = buildString {
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
                cp == 0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF
            if (!emoji) appendCodePoint(cp)
            i += Character.charCount(cp)
        }
    }

    private fun unidad(n: String, uno: String, varios: String) =
        "$n " + if (n == "1") uno else varios

    fun abreviaturas(entrada: String): String {
        var t = entrada
        val fin = "(?![\\p{L}\\d_])"
        val num = "(\\d+(?:[.,]\\d+)?)"
        t = t.replace(Regex("(\\d{2,3})\\s?/\\s?(\\d{2,3})\\s?mm\\s?Hg", RegexOption.IGNORE_CASE), "$1 sobre $2 milímetros de mercurio")
        t = t.replace(Regex("(?<![\\p{L}\\d_])mm\\s?Hg$fin", RegexOption.IGNORE_CASE), "milímetros de mercurio")
        t = t.replace(Regex("(\\d{1,3})\\s?/\\s?(\\d{1,3})(?!\\d)"), "$1 sobre $2")
        t = t.replace(Regex("$num\\s?km/h$fin", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} kilómetros por hora" }
        t = t.replace(Regex("$num\\s?km$fin", RegexOption.IGNORE_CASE)) { unidad(it.groupValues[1], "kilómetro", "kilómetros") }
        t = t.replace(Regex("(?<![\\p{L}\\d_])km$fin", RegexOption.IGNORE_CASE), "kilómetros")
        t = t.replace(Regex("$num\\s?(?:ppm|lpm|bpm)$fin", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} pulsaciones por minuto" }
        t = t.replace(Regex("(?<![\\p{L}\\d_])(?:ppm|lpm|bpm)$fin", RegexOption.IGNORE_CASE), "pulsaciones por minuto")
        t = t.replace(Regex("$num\\s?kcal$fin", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} kilocalorías" }
        t = t.replace(Regex("(?<![\\p{L}\\d_])kcal$fin", RegexOption.IGNORE_CASE), "kilocalorías")
        t = t.replace(Regex("$num\\s?kg$fin", RegexOption.IGNORE_CASE)) { unidad(it.groupValues[1], "kilo", "kilos") }
        t = t.replace(Regex("$num\\s?ml$fin", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} mililitros" }
        t = t.replace(Regex("$num\\s?(?:min|mins)$fin", RegexOption.IGNORE_CASE)) { unidad(it.groupValues[1], "minuto", "minutos") }
        t = t.replace(Regex("$num\\s?(?:seg|s)$fin")) { unidad(it.groupValues[1], "segundo", "segundos") }
        t = t.replace(Regex("$num\\s?h$fin")) { unidad(it.groupValues[1], "hora", "horas") }
        t = t.replace(Regex("$num\\s?°\\s?C$fin")) { unidad(it.groupValues[1], "grado", "grados") }
        t = t.replace(Regex("$num\\s?°")) { unidad(it.groupValues[1], "grado", "grados") }
        t = t.replace(Regex("$num\\s?%")) { "${it.groupValues[1]} por ciento" }
        t = t.replace(Regex("(\\d+)\\s?[xX×]\\s?(\\d+)"), "$1 por $2")
        t = t.replace(Regex("\\s[xX×]\\s(\\d)"), " por $1")
        t = t.replace(Regex("(\\d+)\\s?[-–]\\s?(\\d+)"), "$1 a $2")
        t = t.replace(Regex("\\bSpO2$fin", RegexOption.IGNORE_CASE), "oxígeno en sangre")
        t = t.replace(Regex("\\bHRV$fin"), "variabilidad del pulso")
        t = t.replace(Regex("\\bvs\\.?\\s", RegexOption.IGNORE_CASE), "frente a ")
        t = t.replace(Regex("\\betc\\.", RegexOption.IGNORE_CASE), "etcétera.")
        t = t.replace(Regex("\\baprox\\.", RegexOption.IGNORE_CASE), "aproximadamente")
        t = t.replace(Regex("\\bp\\.\\s?ej\\.", RegexOption.IGNORE_CASE), "por ejemplo")
        t = t.replace("&", " y ")
        return t
    }

    /** Parte una frase demasiado larga por comas (o espacios) sin perder texto. */
    private fun partir(frase: String, max: Int): List<String> {
        if (frase.length <= max) return listOf(frase)
        val out = mutableListOf<String>()
        var resto = frase
        while (resto.length > max) {
            var corte = resto.lastIndexOf(", ", max)
            if (corte < max / 3) corte = resto.lastIndexOf(' ', max)
            if (corte <= 0) corte = max
            out += resto.substring(0, corte + 1).trim()
            resto = resto.substring(corte + 1).trim()
        }
        if (resto.isNotEmpty()) out += resto
        return out
    }
}
