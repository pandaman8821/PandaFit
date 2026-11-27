// ============================================================================
//  Panda Fit - SuenoSemana.kt
//  v26: ANALISIS DEL SUENO de los ultimos 7 dias (funciones puras):
//    * fases por noche (profundo / ligero / despierto),
//    * horas medias, hora media de acostarse y de despertar (bien calculada
//      aunque cruce la medianoche),
//    * REGULARIDAD 0-100 (lo constante que es la hora de acostarse),
//    * un comentario de Panda en lenguaje natural para la tarjeta y el aviso
//      de cada manana.
// ============================================================================
package com.pandafit.app.data

import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NocheSueno(
    val fecha: String,
    val profundoMin: Int,
    val ligeroMin: Int,
    val despiertoMin: Int,
    val totalMin: Int,
    val dormirMin: Int,
    val despertarMin: Int,
    val conFases: Boolean
)

data class AnalisisSueno(
    val noches: List<NocheSueno>,
    val mediaMin: Int,
    val acostarseMin: Int,        // -1 si no se sabe
    val despertarMin: Int,        // -1 si no se sabe
    val regularidad: Int,         // 0-100, -1 si no hay datos suficientes
    val profundoPct: Int,         // % de sueno profundo (noches con fases)
    val nochesMeta: Int,
    val comentario: String
)

object SuenoSemana {

    fun analizar(dias: List<ResumenDia>, metaHoras: Float, hoy: LocalDate = LocalDate.now()): AnalisisSueno? {
        val desde = hoy.minusDays(6)
        val noches = dias.mapNotNull { d ->
            val f = runCatching { LocalDate.parse(d.fecha) }.getOrNull() ?: return@mapNotNull null
            val s = d.sueno ?: return@mapNotNull null
            if (f < desde || f > hoy || s.dormidoMin <= 0) return@mapNotNull null
            NocheSueno(d.fecha, s.profundoMin, s.ligeroMin, s.despiertoMin, s.dormidoMin, s.dormirMin, s.despertarMin, s.tieneFases)
        }.sortedBy { it.fecha }
        if (noches.isEmpty()) return null

        val media = noches.sumOf { it.totalMin } / noches.size
        val acostarse = horaMedia(noches.map { it.dormirMin }.filter { it >= 0 })
        val despertar = horaMedia(noches.map { it.despertarMin }.filter { it >= 0 })
        val regularidad = regularidad(noches.map { it.dormirMin }.filter { it >= 0 })
        val conFases = noches.filter { it.conFases }
        val profundoPct = if (conFases.isEmpty()) 0
        else (conFases.sumOf { it.profundoMin } * 100.0 / conFases.sumOf { it.totalMin }.coerceAtLeast(1)).roundToInt()
        val meta = (metaHoras * 60).toInt()
        val nochesMeta = noches.count { it.totalMin >= meta - 15 }

        return AnalisisSueno(
            noches, media, acostarse, despertar, regularidad, profundoPct, nochesMeta,
            comentario(noches, media, meta, regularidad, profundoPct, acostarse)
        )
    }

    /** Las horas de acostarse se pasan a "minutos desde el mediodia" para promediar bien 23:30 y 00:30. */
    private fun desdeMediodia(m: Int) = (m - 720 + 1440) % 1440

    fun horaMedia(minutos: List<Int>): Int {
        if (minutos.isEmpty()) return -1
        val media = minutos.map { desdeMediodia(it) }.average()
        return ((media + 720).roundToInt()) % 1440
    }

    /** 100 = siempre a la misma hora. Cada 10 min de desviacion tipica resta ~8 puntos. */
    fun regularidad(minutos: List<Int>): Int {
        if (minutos.size < 3) return -1
        val v = minutos.map { desdeMediodia(it).toDouble() }
        val m = v.average()
        val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        return (100 - sd * 0.8).roundToInt().coerceIn(0, 100)
    }

    fun hhmm(min: Int): String = if (min < 0) "--" else "%d:%02d".format(min / 60, min % 60)

    fun horas(min: Int): String = "${min / 60} h ${min % 60} min"

    private fun comentario(
        noches: List<NocheSueno>, media: Int, meta: Int, regularidad: Int, profundoPct: Int, acostarse: Int
    ): String {
        val partes = mutableListOf<String>()
        val falta = meta - media
        partes += when {
            noches.size == 1 -> "Tengo solo una noche registrada: dormiste ${horas(media)}."
            falta > 45 -> "Esta semana duermes de media ${horas(media)}, unos ${(falta + 5) / 10 * 10} min menos de tu meta."
            falta > 0 -> "Duermes de media ${horas(media)}, muy cerca de tu meta."
            else -> "Duermes de media ${horas(media)}: estás cumpliendo tu meta, ¡bien!"
        }
        if (regularidad in 0..59) partes += "Tu hora de acostarte cambia bastante; acostarte siempre cerca de las ${hhmm(acostarse)} te ayudaría a descansar mejor."
        else if (regularidad >= 80) partes += "Además eres muy regular con la hora de acostarte, eso es oro."
        if (profundoPct in 1..14) partes += "El sueño profundo es algo bajo (${profundoPct} %): evita pantallas y cenas pesadas la última hora."
        else if (profundoPct >= 25) partes += "Buen porcentaje de sueño profundo (${profundoPct} %)."
        val ultima = noches.last()
        if (noches.size >= 2 && ultima.totalMin < media - 60) partes += "Anoche dormiste menos de lo habitual; hoy mejor un entreno suave."
        return partes.joinToString(" ")
    }

    // ---------------------------------------------------- 0.1.7: TRADUCIBLE
    private fun horasL(c: android.content.Context, min: Int) =
        c.getString(com.pandafit.app.R.string.sleep_hm, min / 60, min % 60)

    /** Comentario de la tarjeta en el idioma del telefono. */
    fun comentarioLocal(c: android.content.Context, a: AnalisisSueno, metaHoras: Float): String {
        val meta = (metaHoras * 60).toInt()
        val falta = meta - a.mediaMin
        val partes = mutableListOf<String>()
        partes += when {
            a.noches.size == 1 -> c.getString(com.pandafit.app.R.string.sleep_c_one, horasL(c, a.mediaMin))
            falta > 45 -> c.getString(com.pandafit.app.R.string.sleep_c_below, horasL(c, a.mediaMin), (falta + 5) / 10 * 10)
            falta > 0 -> c.getString(com.pandafit.app.R.string.sleep_c_close, horasL(c, a.mediaMin))
            else -> c.getString(com.pandafit.app.R.string.sleep_c_ok, horasL(c, a.mediaMin))
        }
        if (a.regularidad in 0..59) partes += c.getString(com.pandafit.app.R.string.sleep_c_irregular, hhmm(a.acostarseMin))
        else if (a.regularidad >= 80) partes += c.getString(com.pandafit.app.R.string.sleep_c_regular)
        if (a.profundoPct in 1..14) partes += c.getString(com.pandafit.app.R.string.sleep_c_deep_low, a.profundoPct)
        else if (a.profundoPct >= 25) partes += c.getString(com.pandafit.app.R.string.sleep_c_deep_good, a.profundoPct)
        if (a.noches.size >= 2 && a.noches.last().totalMin < a.mediaMin - 60) partes += c.getString(com.pandafit.app.R.string.sleep_c_last_short)
        return partes.joinToString(" ")
    }

    /** Aviso de la manana en el idioma del telefono. */
    fun avisoMananaLocal(c: android.content.Context, a: AnalisisSueno, metaHoras: Float): String {
        val anoche = a.noches.last().totalMin
        val meta = (metaHoras * 60).toInt()
        val h = horasL(c, anoche)
        return when {
            anoche >= meta -> c.getString(com.pandafit.app.R.string.sleep_m_goal, h)
            anoche >= meta - 60 -> c.getString(com.pandafit.app.R.string.sleep_m_close, h)
            else -> c.getString(com.pandafit.app.R.string.sleep_m_short, h)
        } + if (a.regularidad in 0..59) " " + c.getString(com.pandafit.app.R.string.sleep_m_regular) else ""
    }

    /** Frase corta para el aviso de la manana. */
    fun avisoManana(a: AnalisisSueno, metaHoras: Float): String {
        val anoche = a.noches.last()
        val meta = (metaHoras * 60).toInt()
        val base = "Anoche dormiste ${horas(anoche.totalMin)}"
        return when {
            anoche.totalMin >= meta -> "$base. ¡Meta cumplida! Hoy puedes darle caña."
            anoche.totalMin >= meta - 60 -> "$base, cerca de tu meta. Buen día para moverte."
            else -> "$base, menos de lo que necesitas. Hoy ve suave y acuéstate un poco antes."
        } + if (a.regularidad in 0..59) " Intenta acostarte a la misma hora." else ""
    }
}
