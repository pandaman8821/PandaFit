// ============================================================================
//  Panda Fit - Logros.kt
//  v26: MEDALLAS y RETOS SEMANALES.
//    * Medallas: se ganan una vez y quedan guardadas con su fecha (aviso al
//      conseguirlas). Rachas, metas, entrenos, distancias, agua y sueno.
//    * Retos de la semana (lunes a domingo): Panda los calcula con TUS datos
//      de las ultimas semanas (un poco por encima de lo habitual) y se fijan
//      para toda la semana; el progreso se ve en vivo.
//  Todo lo de calcular es puro (se prueba sin telefono).
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.data.model.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

enum class TipoMedalla { RACHA, PASOS, ENTRENO, DISTANCIA, AGUA, SUENO }

data class Medalla(val id: String, val titulo: String, val detalle: String, val tipo: TipoMedalla)

data class Reto(val id: String, val titulo: String, val objetivo: Int, val progreso: Int, val unidad: String) {
    val fraccion: Float get() = if (objetivo <= 0) 0f else (progreso.toFloat() / objetivo).coerceIn(0f, 1f)
    val cumplido: Boolean get() = progreso >= objetivo
}

class Logros(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("panda_logros", Context.MODE_PRIVATE)

    private val _conseguidas = MutableStateFlow(cargar())
    /** id de medalla -> fecha en que se consiguio. */
    val conseguidas: StateFlow<Map<String, String>> = _conseguidas.asStateFlow()

    private fun cargar(): Map<String, String> =
        prefs.getStringSet("medallas", emptySet()).orEmpty().mapNotNull {
            val partes = it.split("@")
            if (partes.size == 2) partes[0] to partes[1] else null
        }.toMap()

    /** Revisa las medallas con los datos actuales y avisa de las nuevas. */
    @Synchronized
    fun revisar(app: PandaFitApplication) {
        val ganadas = evaluar(
            dias = app.archivo.dias.value,
            sesiones = app.workoutHistory.sesiones.value,
            metaAgua = app.preferences.waterGoalGlasses,
            metaSueno = app.preferences.sleepGoalHours
        )
        val nuevas = ganadas - _conseguidas.value.keys
        if (nuevas.isEmpty()) return
        val hoy = LocalDate.now().toString()
        val mapa = _conseguidas.value + nuevas.associateWith { hoy }
        _conseguidas.value = mapa
        prefs.edit().putStringSet("medallas", mapa.map { "${it.key}@${it.value}" }.toSet()).apply()
        // La primera vez se "descubren" las antiguas de golpe: no se avisa de todas.
        val primeraVez = !prefs.getBoolean("inicializado", false)
        prefs.edit().putBoolean("inicializado", true).apply()
        if (primeraVez) return
        nuevas.mapNotNull { id -> MEDALLAS.firstOrNull { it.id == id } }.take(2).forEachIndexed { i, m ->
            val (t, d) = TEXTOS[m.id] ?: return@forEachIndexed
            AvisosActividad.notificar(app, 5200 + i, app.getString(com.pandafit.app.R.string.medal_new, app.getString(t)), app.getString(d))
        }
    }

    /** Retos de esta semana (se fijan la primera vez que se calculan en la semana). */
    fun retosSemana(app: PandaFitApplication, hoy: LocalDate = LocalDate.now()): List<Reto> {
        val semana = claveSemana(hoy)
        val guardado = prefs.getString("retos_$semana", null)
        val objetivos: Map<String, Int> = guardado?.split(";")?.mapNotNull {
            val (k, v) = it.split("=").takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to (v.toIntOrNull() ?: return@mapNotNull null)
        }?.toMap() ?: proponer(app.archivo.dias.value, app.workoutHistory.sesiones.value, app.preferences.sleepGoalHours, hoy).also { obj ->
            prefs.edit().putString("retos_$semana", obj.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
        }
        return progreso(
            objetivos, app.archivo.dias.value, app.workoutHistory.sesiones.value,
            app.preferences.sleepGoalHours, app.preferences.waterGoalGlasses, hoy
        )
    }

    companion object {

        /** 0.1.7: textos traducibles de cada medalla: id -> (titulo, detalle). */
        val TEXTOS: Map<String, Pair<Int, Int>> = mapOf(
            "primer_dia" to (com.pandafit.app.R.string.medal_first_day to com.pandafit.app.R.string.medal_first_day_d),
            "meta_1" to (com.pandafit.app.R.string.medal_goal to com.pandafit.app.R.string.medal_goal_d),
            "pasos_15k" to (com.pandafit.app.R.string.medal_15k to com.pandafit.app.R.string.medal_15k_d),
            "pasos_20k" to (com.pandafit.app.R.string.medal_20k to com.pandafit.app.R.string.medal_20k_d),
            "racha_3" to (com.pandafit.app.R.string.medal_streak3 to com.pandafit.app.R.string.medal_streak3_d),
            "racha_7" to (com.pandafit.app.R.string.medal_streak7 to com.pandafit.app.R.string.medal_streak7_d),
            "racha_30" to (com.pandafit.app.R.string.medal_streak30 to com.pandafit.app.R.string.medal_streak30_d),
            "semana_perfecta" to (com.pandafit.app.R.string.medal_perfect_week to com.pandafit.app.R.string.medal_perfect_week_d),
            "entreno_1" to (com.pandafit.app.R.string.medal_workout1 to com.pandafit.app.R.string.medal_workout1_d),
            "entreno_10" to (com.pandafit.app.R.string.medal_workout10 to com.pandafit.app.R.string.medal_workout10_d),
            "km_5" to (com.pandafit.app.R.string.medal_5k to com.pandafit.app.R.string.medal_5k_d),
            "km_10" to (com.pandafit.app.R.string.medal_10k to com.pandafit.app.R.string.medal_10k_d),
            "km_21" to (com.pandafit.app.R.string.medal_half to com.pandafit.app.R.string.medal_half_d),
            "agua" to (com.pandafit.app.R.string.medal_water to com.pandafit.app.R.string.medal_water_d),
            "sueno" to (com.pandafit.app.R.string.medal_sleep to com.pandafit.app.R.string.medal_sleep_d)
        )

        /** 0.1.7: textos traducibles de cada reto: id -> (titulo, unidad). */
        val TEXTOS_RETO: Map<String, Pair<Int, Int>> = mapOf(
            "pasos" to (com.pandafit.app.R.string.challenge_steps to com.pandafit.app.R.string.unit_steps),
            "entrenos" to (com.pandafit.app.R.string.challenge_workouts to com.pandafit.app.R.string.unit_sessions),
            "sueno" to (com.pandafit.app.R.string.challenge_sleep to com.pandafit.app.R.string.unit_nights),
            "agua" to (com.pandafit.app.R.string.challenge_water to com.pandafit.app.R.string.unit_days)
        )

        val MEDALLAS = listOf(
            Medalla("primer_dia", "Primer día", "Registraste tu primer día de actividad real.", TipoMedalla.PASOS),
            Medalla("meta_1", "Meta cumplida", "Llegaste por primera vez a tu meta de pasos.", TipoMedalla.PASOS),
            Medalla("pasos_15k", "15.000 pasos", "Un día con 15.000 pasos o más.", TipoMedalla.PASOS),
            Medalla("pasos_20k", "20.000 pasos", "Un día con 20.000 pasos o más.", TipoMedalla.PASOS),
            Medalla("racha_3", "Racha de 3", "3 días seguidos cumpliendo la meta.", TipoMedalla.RACHA),
            Medalla("racha_7", "Racha de 7", "Una semana entera seguida cumpliendo la meta.", TipoMedalla.RACHA),
            Medalla("racha_30", "Racha de 30", "30 días seguidos cumpliendo la meta. ¡Leyenda!", TipoMedalla.RACHA),
            Medalla("semana_perfecta", "Semana perfecta", "Meta cumplida de lunes a domingo.", TipoMedalla.RACHA),
            Medalla("entreno_1", "Primer entreno", "Guardaste tu primer entrenamiento.", TipoMedalla.ENTRENO),
            Medalla("entreno_10", "10 entrenos", "Ya llevas 10 entrenamientos.", TipoMedalla.ENTRENO),
            Medalla("km_5", "Primeros 5 km", "Una sesión de 5 km o más.", TipoMedalla.DISTANCIA),
            Medalla("km_10", "Primer 10K", "Una sesión de 10 km o más.", TipoMedalla.DISTANCIA),
            Medalla("km_21", "Media maratón", "Una sesión de 21 km o más.", TipoMedalla.DISTANCIA),
            Medalla("agua", "Bien hidratado", "Cumpliste tu meta de agua del día.", TipoMedalla.AGUA),
            Medalla("sueno", "Descanso completo", "Una noche durmiendo tu meta de horas.", TipoMedalla.SUENO)
        )

        /** Ids de las medallas que cumplen los datos. Funcion pura. */
        fun evaluar(dias: List<ResumenDia>, sesiones: List<WorkoutSession>, metaAgua: Int, metaSueno: Float): Set<String> {
            val r = mutableSetOf<String>()
            val reales = dias.filter { it.pasos > 0 }
            if (reales.isNotEmpty()) r += "primer_dia"
            if (reales.any { it.pasos >= it.metaPasos }) r += "meta_1"
            if (reales.any { it.pasos >= 15_000 }) r += "pasos_15k"
            if (reales.any { it.pasos >= 20_000 }) r += "pasos_20k"
            val mejor = mejorRacha(dias)
            if (mejor >= 3) r += "racha_3"
            if (mejor >= 7) r += "racha_7"
            if (mejor >= 30) r += "racha_30"
            if (haySemanaPerfecta(dias)) r += "semana_perfecta"
            if (sesiones.isNotEmpty()) r += "entreno_1"
            if (sesiones.size >= 10) r += "entreno_10"
            val maxKm = sesiones.maxOfOrNull { it.distanceKm } ?: 0.0
            if (maxKm >= 5) r += "km_5"
            if (maxKm >= 10) r += "km_10"
            if (maxKm >= 21.0975) r += "km_21"
            if (metaAgua > 0 && dias.any { it.vasosAgua >= metaAgua }) r += "agua"
            if (dias.any { (it.sueno?.dormidoMin ?: 0) >= metaSueno * 60 }) r += "sueno"
            return r
        }

        /** Mayor numero de dias SEGUIDOS (fechas consecutivas) con la meta cumplida. */
        fun mejorRacha(dias: List<ResumenDia>): Int {
            val ok = dias.filter { it.pasos >= it.metaPasos && it.pasos > 0 }
                .mapNotNull { runCatching { LocalDate.parse(it.fecha) }.getOrNull() }.toSortedSet()
            var mejor = 0; var actual = 0; var previo: LocalDate? = null
            for (f in ok) {
                actual = if (previo != null && previo.plusDays(1) == f) actual + 1 else 1
                mejor = maxOf(mejor, actual)
                previo = f
            }
            return mejor
        }

        private fun haySemanaPerfecta(dias: List<ResumenDia>): Boolean {
            val ok = dias.filter { it.pasos >= it.metaPasos && it.pasos > 0 }
                .mapNotNull { runCatching { LocalDate.parse(it.fecha) }.getOrNull() }.toSet()
            return ok.filter { it.dayOfWeek == DayOfWeek.MONDAY }.any { lunes -> (0L..6L).all { lunes.plusDays(it) in ok } }
        }

        fun claveSemana(d: LocalDate): String = "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

        private fun lunesDe(d: LocalDate) = d.with(DayOfWeek.MONDAY)

        private fun redondear(v: Int, a: Int) = ((v + a / 2) / a) * a

        /**
         * Objetivos de la semana con los datos de las 2-4 semanas anteriores:
         * un poco por encima de lo habitual, para que sea un reto alcanzable.
         */
        fun proponer(dias: List<ResumenDia>, sesiones: List<WorkoutSession>, metaSueno: Float, hoy: LocalDate): Map<String, Int> {
            val lunes = lunesDe(hoy)
            val previos = dias.filter {
                val f = runCatching { LocalDate.parse(it.fecha) }.getOrNull() ?: return@filter false
                f < lunes && f >= lunes.minusWeeks(4) && it.pasos > 0
            }
            val media = if (previos.isEmpty()) 6_000 else previos.sumOf { it.pasos } / previos.size
            val obj = mutableMapOf<String, Int>()
            obj["pasos"] = redondear((media * 7 * 1.1).toInt(), 5_000).coerceIn(30_000, 140_000)
            val zona = ZoneId.systemDefault()
            val semanaPasada = sesiones.count {
                val f = java.time.Instant.ofEpochMilli(it.startedAt).atZone(zona).toLocalDate()
                f >= lunes.minusWeeks(1) && f < lunes
            }
            obj["entrenos"] = (semanaPasada + 1).coerceIn(2, 5)
            val nochesConDatos = previos.count { it.sueno != null }
            if (nochesConDatos >= 2) {
                val buenas = previos.count { (it.sueno?.dormidoMin ?: 0) >= (metaSueno - 0.5f) * 60 }
                val porSemana = buenas * 7 / maxOf(previos.size, 1)
                obj["sueno"] = (porSemana + 1).coerceIn(3, 6)
            }
            obj["agua"] = 4
            return obj
        }

        /** Progreso de los retos dentro de la semana de [hoy]. */
        fun progreso(
            objetivos: Map<String, Int>, dias: List<ResumenDia>, sesiones: List<WorkoutSession>,
            metaSueno: Float, metaAgua: Int, hoy: LocalDate
        ): List<Reto> {
            val lunes = lunesDe(hoy)
            val semana = dias.filter {
                val f = runCatching { LocalDate.parse(it.fecha) }.getOrNull() ?: return@filter false
                f >= lunes && f <= hoy
            }
            val zona = ZoneId.systemDefault()
            val entrenos = sesiones.count {
                val f = java.time.Instant.ofEpochMilli(it.startedAt).atZone(zona).toLocalDate()
                f >= lunes && f <= hoy
            }
            return listOfNotNull(
                objetivos["pasos"]?.let { Reto("pasos", "Pasos de la semana", it, semana.sumOf { d -> d.pasos }, "pasos") },
                objetivos["entrenos"]?.let { Reto("entrenos", "Entrenamientos", it, entrenos, "sesiones") },
                objetivos["sueno"]?.let {
                    Reto("sueno", "Noches de buen descanso", it,
                        semana.count { d -> (d.sueno?.dormidoMin ?: 0) >= (metaSueno - 0.5f) * 60 }, "noches")
                },
                objetivos["agua"]?.let { Reto("agua", "Días con la meta de agua", it, semana.count { d -> d.vasosAgua >= metaAgua }, "días") }
            )
        }
    }
}
