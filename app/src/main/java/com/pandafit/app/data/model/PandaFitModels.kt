// ============================================================================
//  Panda Fit - Modelos.kt
//  Todos los modelos de datos de la app. Los nombres de los campos son los que
//  se guardan en Firebase Firestore, asi que NO los cambies sin migrar datos.
//
//  PRIVACIDAD: DateKey usa el formato "yyyy-MM-dd" (fecha LOCAL agregada) y
//  NO contiene horas exactas ni ubicaciones. El usuario de Firestore es anonimo
//  (Auth anonimo), por lo que la nube nunca guarda datos identificables.
// ============================================================================
package com.pandafit.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
//  Modo del usuario (hombre / mujer) para calcular metas y calorias
// ---------------------------------------------------------------------------
enum class GenderMode(val label: String) {
    MAN("Hombre"),
    WOMAN("Mujer");

    companion object {
        fun fromName(name: String?): GenderMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MAN
    }
}

// ---------------------------------------------------------------------------
//  Estado de la conexion BLE
// ---------------------------------------------------------------------------
enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING, ERROR }

// ---------------------------------------------------------------------------
//  Metricas diarias AGREGADAS: es lo unico que sale del telefono hacia Gemini.
//  No hay nombre, correo, telefono, ubicacion exacta ni identificadores.
// ---------------------------------------------------------------------------
@Serializable
data class DailyMetrics(
    @SerialName("date") val dateKey: String = LocalDate.now().toDateKey(),
    val steps: Int = 0,
    val stepGoal: Int = 10_000,
    val distanceKm: Double = 0.0,
    val caloriesKcal: Int = 0,
    val activeMinutes: Int = 0,
    val sleepHours: Double = 0.0,
    val sleepQuality: Int = 0,            // 0-100
    val restingHeartRate: Int = 0,        // ppm en reposo
    val avgHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val spo2: Int = 0,                    // % de oxigeno en sangre
    val stressLevel: Int = 0,             // 0-100
    val source: String = "simulado",      // "simulado" | "health_connect" | "ble" | "mixto"
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Progreso de pasos 0..1 (para el anillo de la pantalla principal). */
    val stepProgress: Float
        get() = if (stepGoal <= 0) 0f else (steps.toFloat() / stepGoal).coerceIn(0f, 1f)

    val sleepText: String get() = "${(sleepHours * 10).toInt() / 10.0} h"

    /**
     * Resumen AGREGADO y ANONIMO que se envia al asistente IA.
     * Es texto plano sin identificadores: sirve para cualquier modelo de Gemini
     * sin exponer datos personales.
     */
    fun toAnonymousSummary(): String = buildString {
        appendLine("Fecha (agregada): $dateKey")
        appendLine("Pasos: $steps de $stepGoal")
        appendLine("Distancia: ${"%.2f".format(distanceKm)} km")
        appendLine("Calorias activas: $caloriesKcal kcal")
        appendLine("Minutos activos: $activeMinutes")
        appendLine("Sueño: ${"%.1f".format(sleepHours)} h (calidad $sleepQuality/100)")
        appendLine("Pulso reposo: $restingHeartRate ppm | medio: $avgHeartRate | max: $maxHeartRate")
        appendLine("Oxigeno en sangre: $spo2 %")
        appendLine("Estres percibido (0-100): $stressLevel")
        appendLine("Origen de los datos: $source")
    }

    companion object {
        fun empty(goal: Int = 10_000) = DailyMetrics(stepGoal = goal)
    }
}

// ---------------------------------------------------------------------------
//  Serie historica (ultimos 7/30 dias) para graficas y para el contexto de la IA
// ---------------------------------------------------------------------------
@Serializable
data class MetricSeries(val days: List<DailyMetrics> = emptyList()) {
    val averageSteps: Int get() = if (days.isEmpty()) 0 else days.sumOf { it.steps } / days.size
    val averageSleep: Double get() =
        if (days.isEmpty()) 0.0 else days.sumOf { it.sleepHours } / days.size

    val averageSpo2: Int get() = if (days.isEmpty()) 0 else days.sumOf { it.spo2 } / days.size

    /** Contexto de 7 dias agregado y anonimo para Gemini. */
    fun toAnonymousSummary(days: Int = 7): String {
        if (this.days.isEmpty()) return "Sin historial disponible."
        return buildString {
            appendLine("Historial de los ultimos ${minOf(days, this@MetricSeries.days.size)} dias (agregado, anonimo):")
            this@MetricSeries.days.takeLast(days).forEach { d ->
                appendLine(
                    "- ${d.dateKey}: ${d.steps} pasos, ${"%.1f".format(d.sleepHours)} h sueño, " +
                        "${d.caloriesKcal} kcal, pulso reposo ${d.restingHeartRate}, SpO2 ${d.spo2}%"
                )
            }
            appendLine("Promedios: ${averageSteps} pasos/dia, ${"%.1f".format(averageSleep)} h sueño/dia, SpO2 ${averageSpo2}%")
        }
    }
}

// ---------------------------------------------------------------------------
//  Medicion puntual de la banda BLE (pulso / SpO2 en vivo)
// ---------------------------------------------------------------------------
data class LiveVitals(
    val heartRate: Int = 0,
    val spo2: Int = 0,
    val batteryPercent: Int = -1,
    val measuredAt: Long = System.currentTimeMillis(),
    val deviceName: String = ""
)

// ---------------------------------------------------------------------------
//  Dispositivo BLE descubierto por el escaner
// ---------------------------------------------------------------------------
data class PandaDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
) {
    /** Senal 0..4 barras para la interfaz. */
    val signalBars: Int
        get() = when {
            rssi >= -55 -> 4
            rssi >= -68 -> 3
            rssi >= -80 -> 2
            rssi >= -90 -> 1
            else -> 0
        }
}

// ---------------------------------------------------------------------------
//  Punto de GPS del entrenamiento
// ---------------------------------------------------------------------------
@Serializable
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val speedMps: Float = 0f,
    val altitude: Double = 0.0,
    val accuracyMeters: Float = 0f
)

// ---------------------------------------------------------------------------
//  Plan de entrenamiento generado por el asistente IA
// ---------------------------------------------------------------------------
@Serializable
data class ExerciseItem(
    val name: String,
    val sets: Int = 0,
    val reps: String = "",
    val durationMinutes: Int = 0,
    val restSeconds: Int = 0,
    val notes: String = ""
)

@Serializable
data class TrainingDay(
    val day: String,                 // "Lunes", "Martes"...
    val focus: String,               // "Fuerza tren inferior"
    val totalMinutes: Int,
    val intensity: String = "Media", // Baja | Media | Alta
    val exercises: List<ExerciseItem> = emptyList()
)

@Serializable
data class TrainingPlan(
    val id: String,
    val title: String,
    val goal: String,
    val daysPerWeek: Int,
    val weeklyMinutes: Int,
    val level: String = "Intermedio",
    val rationale: String = "",
    val days: List<TrainingDay> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "gemini"
)

// ---------------------------------------------------------------------------
//  Sesion de entrenamiento registrada
// ---------------------------------------------------------------------------
@Serializable
data class WorkoutSession(
    val id: String = "",
    val type: String = "Caminata",
    val startedAt: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val steps: Int = 0,
    val distanceKm: Double = 0.0,
    val caloriesKcal: Int = 0,
    val avgHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val trackPoints: List<TrackPoint> = emptyList()
)

// ---------------------------------------------------------------------------
//  Mensaje del chat con el asistente IA
// ---------------------------------------------------------------------------
enum class ChatRole { USER, ASSISTANT, SYSTEM }

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val plan: TrainingPlan? = null,
    val notice: String? = null
)

// ---------------------------------------------------------------------------
//  Resultado del analisis de salud local (sin red)
// ---------------------------------------------------------------------------
data class Insight(
    val title: String,
    val detail: String,
    val severity: Int = 0,        // 0 info, 1 aviso, 2 atencion
    val recommendation: String = ""
)

data class HealthReport(
    val dateKey: String,
    val score: Int,
    val insights: List<Insight> = emptyList(),
    val summary: String = ""
)

// ---------------------------------------------------------------------------
//  Utilidades de fecha compartidas
// ---------------------------------------------------------------------------
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun LocalDate.toDateKey(): String = format(DATE_FORMATTER)

fun LocalDateTime.toDateKey(): String = toLocalDate().toDateKey()

fun String.toLocalDateOrToday(): LocalDate = runCatching { LocalDate.parse(this, DATE_FORMATTER) }
    .getOrElse { LocalDate.now() }

/** Rangos de fecha (inicio, fin) del dia local, usados por Health Connect. */
fun LocalDate.dayRange(): Pair<Long, Long> {
    val start = atStartOfDay()
    val end = start.plusDays(1)
    return start.toEpochSecond(java.time.ZoneId.systemDefault().rules.getOffset(start)) * 1000L to
        end.toEpochSecond(java.time.ZoneId.systemDefault().rules.getOffset(end)) * 1000L
}
