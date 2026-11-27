// ============================================================================
//  Panda Fit - PandaFitRepository.kt
//  Capa de NUBE: Firebase Firestore + Auth anonimo (solo version "completa";
//  0.1.8: la nube va detras de servicios.NubeRemota y la version F-Droid no
//  tiene nube).
//
//  Objetivo de negocio: si se pierde la conexion Bluetooth (o el usuario cierra
//  la app), NADA se pierde: cada metrica se guarda primero en el telefono
//  (SharedPreferences/local) y despues se replica en la nube. Cuando vuelve
//  la red, la cola pendiente se sube sola.
//
//  PRIVACIDAD: el usuario de la nube es ANONIMO (sin correo, sin telefono) y
//  los documentos solo contienen metricas agregadas + un id anonimo local.
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import android.util.Log
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.MetricSeries
import com.pandafit.app.data.model.TrainingPlan
import com.pandafit.app.data.model.WorkoutSession
import com.pandafit.app.data.model.toLocalDateOrToday
import com.pandafit.app.servicios.NubeRemota
import com.pandafit.app.servicios.ServiciosSabor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class PandaFitRepository(
    context: Context,
    private val prefs: SharedPreferencesHelper
) {

    private val appContext = context.applicationContext
    private val nube: NubeRemota by lazy { ServiciosSabor.nube(appContext) }

    /** Cola local: nada se pierde si falla la red o el proceso muere. */
    private val pendingStore = PendingSyncStore(appContext)

    /** Id ANONIMO y local del usuario (no es un dato personal). */
    val anonymousUserId: String by lazy {
        var id = appContext.getSharedPreferences(SharedPreferencesHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ANON_ID, null)
        if (id.isNullOrBlank()) {
            id = "panda_" + UUID.randomUUID().toString().replace("-", "").take(16)
            appContext.getSharedPreferences(SharedPreferencesHelper.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ANON_ID, id).apply()
        }
        id
    }

    /** true si hay nube configurada (nombre historico: antes solo era Firebase). */
    val firebaseReady: Boolean get() = nube.lista

    /**
     * Inicia sesion anonima si la nube esta configurada.
     * Devuelve true si hay sesion (real o ya existente).
     */
    suspend fun ensureSignedIn(): Boolean = withContext(Dispatchers.IO) {
        nube.lista && nube.iniciarSesion()
    }

    private fun usuario(vararg resto: String) = listOf(COLLECTION_USERS, anonymousUserId) + resto

    // ------------------------------------------------------------ ESCRITURA
    /**
     * Sincroniza un dia completo. Se llama SIEMPRE (haya Bluetooth o no):
     * la nube mantiene su propia cola offline, asi que el dato nunca se pierde.
     */
    suspend fun syncDay(metrics: DailyMetrics, force: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            prefs.cachedToday = metrics
            if (!prefs.cloudSyncEnabled || !nube.lista) return@withContext false
            if (!ensureSignedIn()) return@withContext false
            nube.guardar(usuario(COLLECTION_DAYS, metrics.dateKey), metrics.toFirestoreMap(), fusionar = true)
        }

    /** Sube la serie historica (7 dias) de una vez. */
    suspend fun syncHistory(series: MetricSeries): Boolean = withContext(Dispatchers.IO) {
        prefs.cachedHistory = series
        var allOk = true
        series.days.forEach { day ->
            // Si un dia falla, no se descarta: queda en la cola offline de
            // la nube y en la cache local (prefs.cachedHistory).
            if (!syncDay(day)) allOk = false
        }
        allOk
    }

    /**
     * Guarda la sesion de entrenamiento.
     *
     * IMPORTANTE: SIEMPRE encola la sesion en disco ANTES de intentar subirla.
     * Asi, si no hay red / no hay sesion anonima / el proceso muere justo al
     * terminar el entreno, la sesion se reintenta despues (retryPending()).
     */
    suspend fun syncWorkout(session: WorkoutSession): Boolean = withContext(Dispatchers.IO) {
        // 0.0.5: sin nube configurada no se encola nada (antes la cola crecia
        // sin fin y Inicio mostraba "N pendiente(s) de subir").
        if (!firebaseReady) return@withContext false
        pendingStore.enqueueWorkout(session)
        val ok = uploadWorkout(session)
        if (ok) pendingStore.removeWorkout(session.id)
        Log.i(TAG, "Entrenamiento ${session.type}: ${if (ok) "subido a la nube" else "en cola local"}")
        ok
    }

    /** Sube un entrenamiento ya encolado (o nuevo). No toca la cola. */
    private suspend fun uploadWorkout(session: WorkoutSession): Boolean {
        if (!prefs.cloudSyncEnabled || !nube.lista) return false
        if (!ensureSignedIn()) return false
        val id = session.id.ifBlank { UUID.randomUUID().toString() }
        return nube.guardar(
            usuario(COLLECTION_WORKOUTS, id),
            mapOf(
                "tipo" to session.type,
                "inicio" to session.startedAt,
                "duracionSeg" to session.durationSeconds,
                "pasos" to session.steps,
                "km" to session.distanceKm,
                "kcal" to session.caloriesKcal,
                "pulsoMedio" to session.avgHeartRate,
                "pulsoMax" to session.maxHeartRate,
                // Solo el resumen del trayecto (sin coordenadas)
                "puntosGps" to session.trackPoints.size,
                "actualizado" to System.currentTimeMillis()
            ),
            fusionar = false
        )
    }

    /** Guarda el plan generado por el asistente IA (con cola local de respaldo). */
    suspend fun syncPlan(plan: TrainingPlan): Boolean = withContext(Dispatchers.IO) {
        if (!firebaseReady) return@withContext false
        pendingStore.enqueuePlan(plan)
        val ok = uploadPlan(plan)
        if (ok) pendingStore.removePlan(plan.id)
        ok
    }

    private suspend fun uploadPlan(plan: TrainingPlan): Boolean {
        if (!prefs.cloudSyncEnabled || !nube.lista) return false
        if (!ensureSignedIn()) return false
        return nube.guardar(
            usuario(COLLECTION_PLANS, plan.id),
            mapOf(
                "titulo" to plan.title,
                "objetivo" to plan.goal,
                "diasSemana" to plan.daysPerWeek,
                "minutosSemana" to plan.weeklyMinutes,
                "nivel" to plan.level,
                "motivo" to plan.rationale,
                "creado" to plan.createdAt,
                "origen" to plan.source
            ),
            fusionar = false
        )
    }

    /** Historial de chat del asistente (se puede desactivar por privacidad). */
    suspend fun syncChatSummary(messagesCount: Int, lastUserIntent: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!firebaseReady) return@withContext false
            pendingStore.enqueueChat(messagesCount, lastUserIntent)
            if (!prefs.cloudSyncEnabled) return@withContext false
            // Antes faltaba ensureSignedIn(): en el primer arranque con la app
            // recien instalada el resumen se perdia en silencio.
            if (!ensureSignedIn()) return@withContext false
            val ok = nube.guardar(
                usuario(),
                mapOf(
                    "chat.mensajes" to messagesCount,
                    "chat.ultimaIntencion" to lastUserIntent.take(80),
                    "chat.actualizado" to System.currentTimeMillis()
                ),
                fusionar = true
            )
            if (ok) pendingStore.clearChat()
            ok
        }

    /**
     * Reintenta TODO lo que quedo pendiente (entrenamientos, planes, chat y el
     * dia de hoy). Se llama al arrancar la app, al recuperar la red y tras un
     * reinicio del sistema.
     *
     * @return numero de elementos subidos correctamente.
     */
    suspend fun retryPending(): Int = withContext(Dispatchers.IO) {
        var uploaded = 0

        // 1) El dia de hoy (siempre, es barato y es el dato mas importante).
        if (syncDay(prefs.cachedToday)) uploaded++

        // 2) Entrenamientos en cola.
        pendingStore.workouts().forEach { pending ->
            val session = WorkoutSession(
                id = pending.id,
                type = pending.type,
                startedAt = pending.startedAt,
                durationSeconds = pending.durationSeconds,
                steps = pending.steps,
                distanceKm = pending.distanceKm,
                caloriesKcal = pending.caloriesKcal,
                avgHeartRate = pending.avgHeartRate,
                maxHeartRate = pending.maxHeartRate,
                trackPoints = emptyList()
            )
            if (uploadWorkout(session)) {
                pendingStore.removeWorkout(pending.id)
                uploaded++
            } else {
                // Sin red/auth: se deja la cola intacta y se para el bucle.
                return@withContext uploaded
            }
        }

        // 3) Planes en cola.
        pendingStore.plans().forEach { plan ->
            if (uploadPlan(plan)) {
                pendingStore.removePlan(plan.id)
                uploaded++
            }
        }

        // 4) Resumen de chat.
        pendingStore.chat()?.let { (messages, intent) ->
            if (syncChatSummary(messages, intent)) uploaded++
        }

        if (uploaded > 0) Log.i(TAG, "Cola sincronizada: $uploaded elemento(s)")
        uploaded
    }

    /** Elementos por subir. Sin nube configurada: siempre 0. */
    fun pendingCount(): Int = if (firebaseReady) pendingStore.pendingCount else 0

    // --------------------------------------------------------------- LECTURA
    /** Descarga los ultimos [days] dias (para rellenar huecos tras reinstalar). */
    suspend fun fetchRecentDays(days: Long = 30): MetricSeries = withContext(Dispatchers.IO) {
        if (!nube.lista || !ensureSignedIn()) return@withContext prefs.cachedHistory
        val docs = nube.leer(usuario(COLLECTION_DAYS), "updatedAt", days)
            ?: return@withContext prefs.cachedHistory
        fun Map<String, Any?>.int(k: String, def: Int = 0) = (this[k] as? Number)?.toInt() ?: def
        fun Map<String, Any?>.dbl(k: String) = (this[k] as? Number)?.toDouble() ?: 0.0
        val list = docs.mapNotNull { d ->
            runCatching {
                DailyMetrics(
                    dateKey = d["dateKey"] as? String ?: d["__id"] as String,
                    steps = d.int("steps"),
                    stepGoal = d.int("stepGoal", 10_000),
                    distanceKm = d.dbl("distanceKm"),
                    caloriesKcal = d.int("caloriesKcal"),
                    activeMinutes = d.int("activeMinutes"),
                    sleepHours = d.dbl("sleepHours"),
                    sleepQuality = d.int("sleepQuality"),
                    restingHeartRate = d.int("restingHeartRate"),
                    avgHeartRate = d.int("avgHeartRate"),
                    maxHeartRate = d.int("maxHeartRate"),
                    spo2 = d.int("spo2"),
                    stressLevel = d.int("stressLevel"),
                    source = d["source"] as? String ?: "nube",
                    updatedAt = (d["updatedAt"] as? Number)?.toLong() ?: 0L
                )
            }.getOrNull()
        }.sortedBy { it.dateKey.toLocalDateOrToday() }
        MetricSeries(list).also { prefs.cachedHistory = it }
    }

    fun deleteCloudData(): Boolean = runCatching {
        if (!nube.lista) return@runCatching false
        nube.borrar(usuario())
        pendingStore.clear()
        true
    }.getOrElse { false }

    /** Convierte el modelo a mapa de la nube (nombres estables, sin renombrar). */
    private fun DailyMetrics.toFirestoreMap(): Map<String, Any> = mapOf(
        "dateKey" to dateKey,
        "steps" to steps,
        "stepGoal" to stepGoal,
        "distanceKm" to distanceKm,
        "caloriesKcal" to caloriesKcal,
        "activeMinutes" to activeMinutes,
        "sleepHours" to sleepHours,
        "sleepQuality" to sleepQuality,
        "restingHeartRate" to restingHeartRate,
        "avgHeartRate" to avgHeartRate,
        "maxHeartRate" to maxHeartRate,
        "spo2" to spo2,
        "stressLevel" to stressLevel,
        "source" to source,
        "updatedAt" to updatedAt
    )

    companion object {
        private const val TAG = "PandaFitCloud"
        private const val KEY_ANON_ID = "id_usuario_anonimo"
        const val COLLECTION_USERS = "usuarios"
        const val COLLECTION_DAYS = "dias"
        const val COLLECTION_WORKOUTS = "entrenamientos"
        const val COLLECTION_PLANS = "planes"
    }
}
