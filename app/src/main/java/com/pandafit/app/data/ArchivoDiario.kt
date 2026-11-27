// ============================================================================
//  Panda Fit - ArchivoDiario.kt
//  v26: ARCHIVO DE DIAS (ultimos 400). Antes solo se guardaba el dia de hoy
//  (DayLog) y una cache de 7 dias. Ahora cada dia queda resumido en el telefono:
//  pasos, pulso (medio/min/max), TODAS las presiones medidas, SpO2, agua y el
//  sueno con sus fases. Lo usan: logros y retos, el analisis semanal del sueno,
//  el informe PDF mensual, la copia de seguridad y Health Connect.
//  Solo datos MEDIDOS (nunca los de ejemplo).
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PresionDia(val sistolica: Int, val diastolica: Int, val minuto: Int)

@Serializable
data class SuenoDia(
    val profundoMin: Int = 0,
    val ligeroMin: Int = 0,
    val despiertoMin: Int = 0,
    /** Minuto del dia en que se durmio / desperto (-1 si no se sabe). */
    val dormirMin: Int = -1,
    val despertarMin: Int = -1,
    /** Horas totales si solo se conoce el total (p. ej. Health Connect). */
    val horasTotal: Double = 0.0
) {
    val dormidoMin: Int get() = if (profundoMin + ligeroMin > 0) profundoMin + ligeroMin else (horasTotal * 60).toInt()
    val tieneFases: Boolean get() = profundoMin + ligeroMin > 0
}

@Serializable
data class ResumenDia(
    val fecha: String,                 // yyyy-MM-dd
    val pasos: Int = 0,
    val metaPasos: Int = 10_000,
    val km: Double = 0.0,
    val kcal: Int = 0,
    val pulsoMedio: Int = 0,
    val pulsoMin: Int = 0,
    val pulsoMax: Int = 0,
    val presiones: List<PresionDia> = emptyList(),
    val spo2: Int = 0,
    val vasosAgua: Int = 0,
    val sueno: SuenoDia? = null
)

class ArchivoDiario(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("panda_archivo", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ResumenDia.serializer())

    private val _dias = MutableStateFlow(cargar())
    /** Dias guardados, del mas antiguo al mas reciente. */
    val dias: StateFlow<List<ResumenDia>> = _dias.asStateFlow()

    /** Sustituye (o crea) el resumen de un dia, conservando lo que ya tenia. */
    @Synchronized
    fun actualizar(fecha: String, cambio: (ResumenDia) -> ResumenDia) {
        val actual = _dias.value.firstOrNull { it.fecha == fecha } ?: ResumenDia(fecha)
        val nuevo = cambio(actual)
        if (nuevo == actual && _dias.value.any { it.fecha == fecha }) return
        guardar((_dias.value.filterNot { it.fecha == fecha } + nuevo).sortedBy { it.fecha }.takeLast(MAX))
    }

    /** Para la copia de seguridad: todo el archivo como JSON. */
    fun exportar(): String = json.encodeToString(serializer, _dias.value)

    /** Restaura una copia (se mezcla: lo restaurado gana dia a dia). */
    @Synchronized
    fun importar(texto: String): Int {
        val entrada = json.decodeFromString(serializer, texto)
        val porFecha = _dias.value.associateBy { it.fecha }.toMutableMap()
        entrada.forEach { porFecha[it.fecha] = it }
        guardar(porFecha.values.sortedBy { it.fecha }.takeLast(MAX))
        return entrada.size
    }

    private fun guardar(lista: List<ResumenDia>) {
        _dias.value = lista
        runCatching { prefs.edit().putString(K, json.encodeToString(serializer, lista)).apply() }
    }

    private fun cargar(): List<ResumenDia> = runCatching {
        prefs.getString(K, null)?.let { json.decodeFromString(serializer, it) }
    }.getOrNull().orEmpty()

    companion object {
        private const val K = "dias"
        private const val MAX = 400

        /** Anade una presion si no estaba ya (misma cifra en el mismo minuto). */
        fun conPresion(lista: List<PresionDia>, p: PresionDia): List<PresionDia> =
            if (lista.any { it.sistolica == p.sistolica && it.diastolica == p.diastolica && kotlin.math.abs(it.minuto - p.minuto) <= 1 }) lista
            else (lista + p).sortedBy { it.minuto }.takeLast(48)
    }
}
