// ============================================================================
//  Panda Fit - PendingSyncStore.kt
//  COLA LOCAL DE SINCRONIZACION (nada se pierde si falla la red).
//
//  Por que existe:
//    Firestore ya tiene cola offline, pero eso solo cubre lo que se le entrega.
//    Si NO hay sesion anonima todavia, si el token falla o si el proceso muere
//    justo al terminar un entrenamiento, esos datos se quedarian solo en memoria.
//    Aqui se guardan en disco las operaciones pendientes (entrenamientos, planes
//    y resumen de chat) y se reintentan cuando vuelve la conexion o el sistema
//    se reinicia.
//
//  Implementacion: JSON en SharedPreferences (los entrenamientos no llevan el
//  trayecto GPS completo, solo el resumen: 0 datos de ubicacion persistidos).
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pandafit.app.data.model.TrainingPlan
import com.pandafit.app.data.model.WorkoutSession
import kotlinx.serialization.json.Json

class PendingSyncStore(context: Context) {

    /** Version serializable y minima de un entrenamiento (sin coordenadas). */
    @kotlinx.serialization.Serializable
    data class PendingWorkout(
        val id: String,
        val type: String,
        val startedAt: Long,
        val durationSeconds: Int,
        val steps: Int,
        val distanceKm: Double,
        val caloriesKcal: Int,
        val avgHeartRate: Int,
        val maxHeartRate: Int,
        val gpsPoints: Int
    )

    @kotlinx.serialization.Serializable
    private data class PendingPlan(
        val id: String,
        val title: String,
        val goal: String,
        val daysPerWeek: Int,
        val weeklyMinutes: Int,
        val level: String,
        val rationale: String,
        val createdAt: Long,
        val source: String
    )

    @kotlinx.serialization.Serializable
    private data class PendingChat(
        val messages: Int,
        val lastIntent: String,
        val at: Long
    )

    @kotlinx.serialization.Serializable
    private data class Queue(
        val workouts: List<PendingWorkout> = emptyList(),
        val plans: List<PendingPlan> = emptyList(),
        val chat: PendingChat? = null
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun read(): Queue = runCatching {
        prefs.getString(KEY_QUEUE, null)?.let { json.decodeFromString<Queue>(it) }
    }.getOrNull() ?: Queue()

    private fun write(queue: Queue) {
        runCatching { prefs.edit().putString(KEY_QUEUE, json.encodeToString(queue)).apply() }
            .onFailure { Log.w(TAG, "No se pudo guardar la cola: ${it.message}") }
    }

    val hasPending: Boolean
        get() = read().let { it.workouts.isNotEmpty() || it.plans.isNotEmpty() || it.chat != null }

    val pendingCount: Int
        get() = read().let {
            it.workouts.size + it.plans.size + (if (it.chat != null) 1 else 0)
        }

    // ------------------------------------------------------------- ENCOLAR
    fun enqueueWorkout(session: WorkoutSession) {
        val queue = read()
        val item = PendingWorkout(
            id = session.id.ifBlank { "local_" + System.currentTimeMillis() },
            type = session.type,
            startedAt = session.startedAt,
            durationSeconds = session.durationSeconds,
            steps = session.steps,
            distanceKm = session.distanceKm,
            caloriesKcal = session.caloriesKcal,
            avgHeartRate = session.avgHeartRate,
            maxHeartRate = session.maxHeartRate,
            gpsPoints = session.trackPoints.size
        )
        write(queue.copy(workouts = (queue.workouts.filterNot { it.id == item.id } + item).takeLast(50)))
    }

    fun enqueuePlan(plan: TrainingPlan) {
        val queue = read()
        val item = PendingPlan(
            id = plan.id,
            title = plan.title,
            goal = plan.goal,
            daysPerWeek = plan.daysPerWeek,
            weeklyMinutes = plan.weeklyMinutes,
            level = plan.level,
            rationale = plan.rationale,
            createdAt = plan.createdAt,
            source = plan.source
        )
        write(queue.copy(plans = (queue.plans.filterNot { it.id == item.id } + item).takeLast(20)))
    }

    fun enqueueChat(messages: Int, lastIntent: String) {
        write(read().copy(chat = PendingChat(messages, lastIntent, System.currentTimeMillis())))
    }

    // ------------------------------------------------------------ CONSUMIR
    fun workouts(): List<PendingWorkout> = read().workouts

    fun plans(): List<TrainingPlan> = read().plans.map {
        TrainingPlan(
            id = it.id,
            title = it.title,
            goal = it.goal,
            daysPerWeek = it.daysPerWeek,
            weeklyMinutes = it.weeklyMinutes,
            level = it.level,
            rationale = it.rationale,
            days = emptyList(),
            createdAt = it.createdAt,
            source = it.source
        )
    }

    fun chat(): Pair<Int, String>? = read().chat?.let { it.messages to it.lastIntent }

    fun removeWorkout(id: String) = write(read().copy(workouts = read().workouts.filterNot { it.id == id }))
    fun removePlan(id: String) = write(read().copy(plans = read().plans.filterNot { it.id == id }))
    fun clearChat() = write(read().copy(chat = null))
    fun clear() = prefs.edit().remove(KEY_QUEUE).apply()

    companion object {
        private const val TAG = "PandaFitQueue"
        private const val PREFS_NAME = "panda_fit_cola_sync"
        private const val KEY_QUEUE = "cola_pendiente_json"
    }
}
