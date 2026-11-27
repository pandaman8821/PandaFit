// ============================================================================
//  Panda Fit - WorkoutHistory.kt
//  HISTORIAL LOCAL DE ENTRENAMIENTOS (v20): las ultimas 30 sesiones quedan en
//  el telefono (antes solo se veia la ultima y se perdia al cerrar la app).
//  El recorrido se reduce a 200 puntos: basta para dibujarlo y ocupa poco.
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import com.pandafit.app.data.model.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class WorkoutHistory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("panda_entrenos", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WorkoutSession.serializer())

    private val _sesiones = MutableStateFlow(cargar())
    /** Sesiones guardadas, la mas reciente primero. */
    val sesiones: StateFlow<List<WorkoutSession>> = _sesiones.asStateFlow()

    @Synchronized
    fun anadir(sesion: WorkoutSession) {
        if (sesion.durationSeconds < 30) return        // pulsaciones sin querer: no se guardan
        val ligera = sesion.copy(trackPoints = reducir(sesion.trackPoints, 200))
        val lista = (listOf(ligera) + _sesiones.value.filterNot { it.id == sesion.id }).take(MAX)
        _sesiones.value = lista
        runCatching { prefs.edit().putString(K, json.encodeToString(serializer, lista)).apply() }
    }

    @Synchronized
    fun borrar(id: String) {
        val lista = _sesiones.value.filterNot { it.id == id }
        _sesiones.value = lista
        runCatching { prefs.edit().putString(K, json.encodeToString(serializer, lista)).apply() }
    }

    private fun cargar(): List<WorkoutSession> = runCatching {
        prefs.getString(K, null)?.let { json.decodeFromString(serializer, it) }
    }.getOrNull().orEmpty()

    companion object {
        private const val K = "sesiones"
        private const val MAX = 30

        /** Deja como mucho [max] puntos, repartidos y conservando el primero y el ultimo. */
        fun <T> reducir(lista: List<T>, max: Int): List<T> {
            if (lista.size <= max || max < 2) return lista
            val paso = (lista.size - 1).toDouble() / (max - 1)
            // Redondeo (no truncado): con truncado el ultimo indice podia caer en
            // lastIndex - 1 y el recorrido perdia su punto final.
            return (0 until max).map { lista[Math.round(it * paso).toInt().coerceIn(0, lista.lastIndex)] }
        }

        /** Totales de las sesiones de los ultimos 7 dias: (sesiones, minutos, km, kcal). */
        fun resumenSemana(sesiones: List<WorkoutSession>, ahora: Long = System.currentTimeMillis()): Resumen {
            val desde = ahora - 7L * 24 * 3600 * 1000
            val semana = sesiones.filter { it.startedAt >= desde }
            return Resumen(
                sesiones = semana.size,
                minutos = semana.sumOf { it.durationSeconds } / 60,
                km = semana.sumOf { it.distanceKm },
                kcal = semana.sumOf { it.caloriesKcal }
            )
        }
    }

    data class Resumen(val sesiones: Int, val minutos: Int, val km: Double, val kcal: Int)
}
