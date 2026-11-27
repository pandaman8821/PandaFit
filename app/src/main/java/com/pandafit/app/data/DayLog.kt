// ============================================================================
//  Panda Fit - DayLog.kt
//  REGISTRO DEL DIA para las tarjetas del panel "Hoy":
//    * pasos por HORA (24 barras),
//    * muestras de pulso a lo largo del dia (curva 00:00-24:00),
//    * ultima presion arterial, ultimo SpO2 y ultimo pulso con su hora.
//
//  Solo guarda datos MEDIDOS (pulsera o podometro del telefono), nunca los de
//  ejemplo. Se persiste en SharedPreferences para sobrevivir a cierres y se
//  reinicia solo al cambiar de dia.
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import com.pandafit.app.data.model.toDateKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalTime

class DayLog(context: Context) {

    /** Una medicion con su momento (epoch ms). */
    data class Medida(val valor: Int, val en: Long)

    /** Presion arterial con su momento. */
    data class Presion(val sistolica: Int, val diastolica: Int, val en: Long)

    /** Muestra de pulso: minuto del dia (0-1439) y ppm. */
    data class MuestraPulso(val minuto: Int, val ppm: Int)

    data class Snapshot(
        val fecha: String = "",
        val pasosPorHora: List<Int> = List(24) { 0 },
        val pasosEn: Long = 0L,
        val pulsos: List<MuestraPulso> = emptyList(),
        val ultimoPulso: Medida? = null,
        val spo2: Medida? = null,
        val presion: Presion? = null,
        /** Vasos de agua (250 ml) apuntados hoy (v19). */
        val vasosAgua: Int = 0
    ) {
        val pulsoMedio: Int
            get() = if (pulsos.isEmpty()) 0 else pulsos.sumOf { it.ppm } / pulsos.size
        val hayPasosPorHora: Boolean get() = pasosPorHora.any { it > 0 }
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _estado = MutableStateFlow(cargar())
    val estado: StateFlow<Snapshot> = _estado.asStateFlow()

    /** Ultimo total de pasos visto (para calcular el incremento de cada hora). */
    private var ultimoTotal = prefs.getInt(K_ULTIMO_TOTAL, -1)

    // ------------------------------------------------------------------ PASOS
    /**
     * Recibe el TOTAL de pasos de hoy (pulsera o telefono) y reparte el
     * incremento en la hora actual. La primera vez solo fija la referencia:
     * no se inventa en que horas se dieron los pasos anteriores.
     */
    @Synchronized
    fun registrarPasos(total: Int, fuente: String = "", ahora: Long = System.currentTimeMillis()) {
        val actual = deHoy()
        val hora = LocalTime.now().hour
        // Si cambia la FUENTE del total (podometro del telefono <-> pulsera),
        // los dos contadores no cuadran entre si: el salto NO son pasos nuevos.
        // Antes se apuntaba entero en la hora actual (una barra gigante a las
        // 19 h al conectar la pulsera). Ahora solo se fija una nueva referencia.
        val fuenteAnterior = prefs.getString(K_FUENTE, "") ?: ""
        val cambioDeFuente = fuente.isNotEmpty() && fuenteAnterior.isNotEmpty() && fuente != fuenteAnterior
        val nuevas = if (cambioDeFuente) actual.pasosPorHora
        else repartir(actual.pasosPorHora, ultimoTotal, total, hora)
        if (fuente.isNotEmpty()) prefs.edit().putString(K_FUENTE, fuente).apply()
        ultimoTotal = total
        prefs.edit().putInt(K_ULTIMO_TOTAL, total).apply()
        if (nuevas != actual.pasosPorHora || actual.pasosEn == 0L) {
            guardar(actual.copy(pasosPorHora = nuevas, pasosEn = ahora))
        }
    }

    // ------------------------------------------------------------------ PULSO
    @Synchronized
    fun registrarPulso(ppm: Int, en: Long) {
        if (ppm !in 30..220) return
        val actual = deHoy()
        if (actual.ultimoPulso?.en == en) return
        val minuto = LocalTime.now().let { it.hour * 60 + it.minute }
        // Una muestra por minuto como mucho (las notificaciones llegan cada segundo).
        val pulsos = if (actual.pulsos.lastOrNull()?.minuto == minuto) {
            actual.pulsos.dropLast(1) + MuestraPulso(minuto, ppm)
        } else {
            (actual.pulsos + MuestraPulso(minuto, ppm)).takeLast(MAX_PULSOS)
        }
        guardar(actual.copy(pulsos = pulsos, ultimoPulso = Medida(ppm, en)))
    }

    @Synchronized
    fun registrarSpo2(valor: Int, en: Long) {
        if (valor !in 70..100) return
        val actual = deHoy()
        if (actual.spo2?.valor == valor && actual.spo2.en == en) return
        guardar(actual.copy(spo2 = Medida(valor, en)))
    }

    @Synchronized
    fun registrarPresion(sistolica: Int, diastolica: Int, en: Long = System.currentTimeMillis()) {
        val actual = deHoy()
        val previa = actual.presion
        if (previa != null && previa.sistolica == sistolica && previa.diastolica == diastolica &&
            en - previa.en < 60_000
        ) return
        guardar(actual.copy(presion = Presion(sistolica, diastolica, en)))
    }

    /** Suma o resta vasos de agua de hoy (nunca por debajo de 0 ni por encima de 30). */
    @Synchronized
    fun cambiarAgua(delta: Int) {
        val actual = deHoy()
        guardar(actual.copy(vasosAgua = (actual.vasosAgua + delta).coerceIn(0, 30)))
    }

    /** Llamar al cambiar de dia (o al abrir) para limpiar lo de ayer. */
    @Synchronized
    fun refrescarDia() {
        val actual = deHoy()
        if (actual != _estado.value) _estado.value = actual
    }

    // ---------------------------------------------------------- PERSISTENCIA
    private fun deHoy(): Snapshot {
        val hoy = LocalDate.now().toDateKey()
        val actual = _estado.value
        if (actual.fecha == hoy) return actual
        // Dia nuevo: todo a cero. La referencia de pasos vuelve a fijarse.
        ultimoTotal = -1
        prefs.edit().putInt(K_ULTIMO_TOTAL, -1).putString(K_FUENTE, "").apply()
        return Snapshot(fecha = hoy).also { guardar(it) }
    }

    private fun guardar(s: Snapshot) {
        _estado.value = s
        prefs.edit()
            .putString(K_FECHA, s.fecha)
            .putString(K_HORAS, s.pasosPorHora.joinToString(","))
            .putLong(K_PASOS_EN, s.pasosEn)
            .putString(K_PULSOS, s.pulsos.joinToString(";") { "${it.minuto}:${it.ppm}" })
            .putString(K_ULT_PULSO, s.ultimoPulso?.let { "${it.valor}@${it.en}" })
            .putString(K_SPO2, s.spo2?.let { "${it.valor}@${it.en}" })
            .putString(K_PRESION, s.presion?.let { "${it.sistolica}/${it.diastolica}@${it.en}" })
            .putInt(K_AGUA, s.vasosAgua)
            .apply()
    }

    private fun cargar(): Snapshot = runCatching {
        val hoy = LocalDate.now().toDateKey()
        if (prefs.getString(K_FECHA, null) != hoy) return@runCatching Snapshot(fecha = hoy)
        val horas = prefs.getString(K_HORAS, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 24 } ?: List(24) { 0 }
        val pulsos = prefs.getString(K_PULSOS, null).orEmpty()
            .split(";").filter { it.isNotBlank() }
            .mapNotNull { p ->
                val (m, v) = p.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                MuestraPulso(m.toIntOrNull() ?: return@mapNotNull null, v.toIntOrNull() ?: return@mapNotNull null)
            }
        Snapshot(
            fecha = hoy,
            pasosPorHora = horas,
            pasosEn = prefs.getLong(K_PASOS_EN, 0L),
            pulsos = pulsos,
            ultimoPulso = leerMedida(prefs.getString(K_ULT_PULSO, null)),
            spo2 = leerMedida(prefs.getString(K_SPO2, null)),
            presion = prefs.getString(K_PRESION, null)?.let { txt ->
                val (valores, en) = txt.split("@").takeIf { it.size == 2 } ?: return@let null
                val (s, d) = valores.split("/").takeIf { it.size == 2 } ?: return@let null
                Presion(s.toIntOrNull() ?: return@let null, d.toIntOrNull() ?: return@let null, en.toLongOrNull() ?: 0L)
            },
            vasosAgua = prefs.getInt(K_AGUA, 0)
        )
    }.getOrElse { Snapshot(fecha = LocalDate.now().toDateKey()) }

    private fun leerMedida(txt: String?): Medida? {
        val (v, en) = txt?.split("@")?.takeIf { it.size == 2 } ?: return null
        return Medida(v.toIntOrNull() ?: return null, en.toLongOrNull() ?: 0L)
    }

    companion object {
        private const val PREFS = "panda_dia"
        private const val K_FECHA = "fecha"
        private const val K_HORAS = "pasos_hora"
        private const val K_PASOS_EN = "pasos_en"
        private const val K_PULSOS = "pulsos"
        private const val K_ULT_PULSO = "ultimo_pulso"
        private const val K_SPO2 = "spo2"
        private const val K_PRESION = "presion"
        private const val K_ULTIMO_TOTAL = "ultimo_total"
        private const val K_FUENTE = "fuente_pasos"
        private const val K_AGUA = "vasos_agua"
        private const val MAX_PULSOS = 1440

        /**
         * Reparte en [hora] el incremento entre [anterior] y [total].
         * Funcion pura (se prueba sin telefono).
         */
        fun repartir(horas: List<Int>, anterior: Int, total: Int, hora: Int): List<Int> {
            if (anterior < 0 || total <= anterior || hora !in 0..23) return horas
            val incremento = (total - anterior).coerceAtMost(30_000)
            return horas.mapIndexed { i, v -> if (i == hora) v + incremento else v }
        }
    }
}
