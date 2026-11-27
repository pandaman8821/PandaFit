// ============================================================================
//  Panda Fit - HealthConnectHelper.kt
//  Integracion con HEALTH CONNECT (datos REALES de salud del telefono/reloj).
//
//    * Comprueba disponibilidad (Health Connect moderno esta integrado en el
//      sistema en Android 14+; en Android 9-13 hace falta la app de Health
//      Connect instalada desde Play Store).
//    * Pide permisos de lectura (pasos, distancia, calorias, pulso, SpO2, sueno)
//      con el contrato ActivityResultContract propio de la libreria.
//    * Lee los datos AGREGADOS del dia y los convierte a DailyMetrics.
//    * Puede escribir de vuelta lo medido por la banda Panda Fit.
//
//  PRIVACIDAD: todo se lee y se agrega en el dispositivo. A Gemini solo viajan
//  los numeros agregados (ver DailyMetrics.toAnonymousSummary()).
// ============================================================================
package com.pandafit.app.health

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.toDateKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class HealthConnectHelper(private val context: Context) {

    /** Cliente de Health Connect, o null si no esta disponible en el equipo. */
    private val client: HealthConnectClient? by lazy {
        runCatching {
            if (isAvailable()) HealthConnectClient.getOrCreate(context) else null
        }.getOrElse {
            Log.w(TAG, "Health Connect no disponible: ${it.message}")
            null
        }
    }

    // ========================================================================
    //  DISPONIBILIDAD
    // ========================================================================
    /** true si Health Connect existe (integrado o instalado aparte). */
    fun isAvailable(): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)

    /** true si falta instalar/actualizar la app de Health Connect. */
    fun needsInstall(): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) ==
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    }.getOrDefault(false)

    // ========================================================================
    //  PERMISOS
    // ========================================================================
    /** Permisos de solo lectura que Panda Fit necesita para el panel. */
    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    /** Permisos de escritura (devolvemos a Health Connect lo de la banda). */
    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class)
    )

    /** Contrato listo para `rememberLauncherForActivityResult` en Compose. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** true si el usuario ya concedio todos los permisos de lectura. */
    suspend fun hasAllReadPermissions(): Boolean = runCatching {
        val c = client ?: return false
        c.permissionController.getGrantedPermissions().containsAll(readPermissions)
    }.getOrDefault(false)

    /** true si ya concedio los de escritura. */
    suspend fun hasAllWritePermissions(): Boolean = runCatching {
        val c = client ?: return false
        c.permissionController.getGrantedPermissions().containsAll(writePermissions)
    }.getOrDefault(false)

    /** Intent para abrir Health Connect en Ajustes (si falta instalarlo). */
    fun installIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    // ========================================================================
    //  LECTURA AGREGADA DEL DIA
    // ========================================================================
    /**
     * Lee los datos REALES del dia [date] y devuelve [DailyMetrics].
     * Si el usuario no autorizo permisos, devuelve null (la app usa simulados).
     */
    suspend fun readToday(
        date: LocalDate = LocalDate.now(),
        stepGoal: Int = 10_000
    ): DailyMetrics? {
        val c = client ?: return null
        if (!hasAllReadPermissions()) return null

        return runCatching {
            val zone = ZoneId.systemDefault()
            val start: Instant = date.atStartOfDay(zone).toInstant()
            val end: Instant = date.plusDays(1).atStartOfDay(zone).toInstant()
            val filter = TimeRangeFilter.between(start, end)

            val aggregate = c.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                    ),
                    timeRangeFilter = filter
                )
            )

            val steps = (aggregate[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
            val distanceKm = (aggregate[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0)
            val totalKcal = (aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0)
            val activeKcal =
                (aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0)

            // --- Pulso (media, min y max) ------------------------------------
            val hrRecords = c.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = filter,
                    ascendingOrder = true
                )
            ).records
            val samples = hrRecords.flatMap { it.samples }.map { it.beatsPerMinute }
            val avgHr = if (samples.isEmpty()) 0 else samples.average().toInt()
            val minHr = samples.minOrNull()?.toInt() ?: 0
            val maxHr = samples.maxOrNull()?.toInt() ?: 0

            // --- Oxigeno en sangre (ultima medicion del dia) ------------------
            val spo2Records = c.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = filter,
                    ascendingOrder = false,
                    pageSize = 5
                )
            ).records
            val spo2 = spo2Records.firstOrNull()?.percentage?.value?.toInt() ?: 0

            // --- Sueno: sesiones que terminan dentro del dia ------------------
            val sleepRecords = c.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = filter,
                    ascendingOrder = false,
                    pageSize = 10
                )
            ).records
            val sleepHours = sleepRecords.sumOf { record ->
                java.time.Duration.between(record.startTime, record.endTime).toMinutes() / 60.0
            }
            // Calidad aproximada: 8 h = 100; se penaliza dormir menos de 6 h.
            val sleepQuality = if (sleepHours <= 0) 0
            else ((sleepHours / 8.0) * 100).toInt().coerceIn(20, 100)

            DailyMetrics(
                dateKey = date.toDateKey(),
                steps = steps,
                stepGoal = stepGoal,
                distanceKm = (distanceKm * 100).toInt() / 100.0,
                caloriesKcal = (if (activeKcal > 0) activeKcal else totalKcal).toInt(),
                activeMinutes = (activeKcal / 6.0).toInt().coerceIn(0, 300),
                sleepHours = (sleepHours * 10).toInt() / 10.0,
                sleepQuality = sleepQuality,
                restingHeartRate = if (minHr > 0) minHr else avgHr,
                avgHeartRate = avgHr,
                maxHeartRate = maxHr,
                spo2 = if (spo2 > 0) spo2 else 0,
                stressLevel = 0,
                source = "health_connect",
                updatedAt = System.currentTimeMillis()
            )
        }.getOrElse {
            Log.w(TAG, "Lectura de Health Connect fallo: ${it.message}")
            null
        }
    }

    /** Serie de los ultimos [days] dias leyendo Health Connect dia a dia. */
    suspend fun readHistory(days: Int = 7, stepGoal: Int = 10_000): List<DailyMetrics> {
        val today = LocalDate.now()
        return (days - 1 downTo 0).mapNotNull { back ->
            readToday(today.minusDays(back.toLong()), stepGoal)
        }
    }

    // ========================================================================
    //  ESCRITURA (devolver a Health Connect lo medido por Panda Fit)
    // ========================================================================
    /**
     * Escribe los pasos del dia medidos por la banda Panda Fit.
     * Health Connect deduplica por origen, asi que no se duplican datos.
     */
    suspend fun writeSteps(
        steps: Long,
        start: ZonedDateTime = LocalDate.now().atStartOfDay(ZoneId.systemDefault()),
        end: ZonedDateTime = LocalDate.now().atTime(LocalTime.now()).atZone(ZoneId.systemDefault())
    ): Boolean = runCatching {
        val c = client ?: return false
        if (!hasAllWritePermissions()) return false
        val record = StepsRecord(
            count = steps,
            startTime = start.toInstant(),
            endTime = end.toInstant(),
            startZoneOffset = start.offset,
            endZoneOffset = end.offset,
            // Metadata de "entrada manual": identifica el origen del dato en
            // Health Connect y evita duplicados entre apps.
            metadata = Metadata.manualEntry()
        )
        c.insertRecords(listOf(record))
        true
    }.getOrElse {
        Log.w(TAG, "Escritura de pasos fallo: ${it.message}")
        false
    }

    /** Escribe el pulso medido ahora mismo. */
    suspend fun writeHeartRate(bpm: Long, at: Instant = Instant.now()): Boolean = runCatching {
        val c = client ?: return false
        if (!hasAllWritePermissions()) return false
        val record = HeartRateRecord(
            startTime = at,
            endTime = at.plusSeconds(60),
            startZoneOffset = ZoneId.systemDefault().rules.getOffset(at),
            endZoneOffset = ZoneId.systemDefault().rules.getOffset(at.plusSeconds(60)),
            samples = listOf(HeartRateRecord.Sample(time = at, beatsPerMinute = bpm)),
            metadata = Metadata.manualEntry()
        )
        c.insertRecords(listOf(record))
        true
    }.getOrElse { false }

    /** Escribe el SpO2 medido ahora mismo. */
    suspend fun writeSpo2(percentage: Double, at: Instant = Instant.now()): Boolean =
        runCatching {
            val c = client ?: return false
            if (!hasAllWritePermissions()) return false
            val offset = ZoneId.systemDefault().rules.getOffset(at)
            val record = OxygenSaturationRecord(
                time = at,
                zoneOffset = offset,
                percentage = androidx.health.connect.client.units.Percentage(percentage),
                metadata = Metadata.manualEntry()
            )
            c.insertRecords(listOf(record))
            true
        }.getOrElse { false }

    companion object {
        private const val TAG = "PandaFitHealthConnect"
    }
}
