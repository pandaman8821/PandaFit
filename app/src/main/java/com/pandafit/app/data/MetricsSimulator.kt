// ============================================================================
//  Panda Fit - MetricsSimulator.kt
//  Generador de datos de DEMO realistas (pasos por hora, sueno, pulso, SpO2...).
//
//  Se usa cuando:
//    * El usuario activa "Datos simulados (demo)" en el perfil, o
//    * No hay banda BLE conectada ni Health Connect autorizado.
//
//  El simulador es DETERMINISTA por dia (misma semilla = mismos datos), asi que
//  las graficas no "saltan" al recomponer la interfaz.
// ============================================================================
package com.pandafit.app.data

import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.GenderMode
import com.pandafit.app.data.model.MetricSeries
import com.pandafit.app.data.model.toDateKey
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

object MetricsSimulator {

    /** Curva de actividad tipica de un dia (cuanta gente camina a cada hora). */
    private val HOURLY_WEIGHTS = intArrayOf(
        2, 1, 1, 1, 2, 6, 22, 46, 58, 52, 48, 60,   // 00-11
        64, 55, 46, 42, 48, 62, 74, 58, 40, 26, 12, 5 // 12-23
    )

    /**
     * Metricas de HOY con el avance real del reloj: a las 7 de la manana no
     * puede haber 12.000 pasos.
     */
    fun today(
        gender: GenderMode = GenderMode.MAN,
        stepGoal: Int = 10_000,
        now: LocalTime = LocalTime.now(),
        date: LocalDate = LocalDate.now()
    ): DailyMetrics {
        val rnd = Random(dateSeed(date))
        val genderBoost = if (gender == GenderMode.MAN) 1.04f else 0.97f
        val baseGoal = (stepGoal * genderBoost * (0.86f + rnd.nextFloat() * 0.40f)).roundToInt()

        val hour = now.hour
        val fractionOfHour = now.minute / 60f
        var accumulated = 0f
        val totalWeight = HOURLY_WEIGHTS.sum().toFloat()
        for (h in 0..hour) {
            val w = HOURLY_WEIGHTS[h].toFloat()
            accumulated += if (h == hour) w * fractionOfHour else w
        }
        val dayProgress = (accumulated / totalWeight).coerceIn(0f, 1f)
        val noise = 1f + (rnd.nextFloat() - 0.5f) * 0.06f
        val steps = max(0, (baseGoal * dayProgress * noise).roundToInt())

        // Distancia: zancada segun modo (hombre 0.78 m, mujer 0.70 m)
        val strideMeters = if (gender == GenderMode.MAN) 0.78 else 0.70
        val distanceKm = steps * strideMeters / 1000.0

        // Calorias: ~0.045 kcal por paso y kg, ajustado por modo
        val kcalPerStep = if (gender == GenderMode.MAN) 0.048 else 0.043
        val calories = (steps * kcalPerStep * (0.95f + rnd.nextFloat() * 0.12f)).roundToInt()

        // Minutos activos: bloques de actividad acumulados
        val activeMinutes = min((hour * 60 * dayProgress * 0.22f).roundToInt(), 180)

        // Sueno de la noche anterior (se calcula una vez por dia)
        val sleep = sleepFor(date.minusDays(1), gender, rnd)

        // Pulso: sube con la actividad del dia
        val resting = (if (gender == GenderMode.MAN) 58 else 62) + rnd.nextInt(-5, 7)
        val avg = resting + (8 * dayProgress).roundToInt() + rnd.nextInt(0, 5)
        val maxHr = (if (gender == GenderMode.MAN) 178 else 184) - rnd.nextInt(0, 14)

        // SpO2: sano, con micro variacion
        val spo2 = (97 + rnd.nextInt(0, 3)).coerceIn(94, 100)

        // Estres derivado del sueno y la carga del dia
        val stress = (
            38 + max(10.0 - sleep.sleepHours, 0.0) * 7 +
                dayProgress * 12 + rnd.nextInt(-4, 5)
            ).roundToInt().coerceIn(5, 95)

        return DailyMetrics(
            dateKey = date.toDateKey(),
            steps = steps,
            stepGoal = stepGoal,
            distanceKm = (distanceKm * 100).roundToInt() / 100.0,
            caloriesKcal = calories,
            activeMinutes = activeMinutes,
            sleepHours = sleep.sleepHours,
            sleepQuality = sleep.sleepQuality,
            restingHeartRate = resting,
            avgHeartRate = avg,
            maxHeartRate = maxHr,
            spo2 = spo2,
            stressLevel = stress,
            source = "simulado",
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Historial determinista de [days] dias (incluye hoy). */
    fun history(
        days: Int = 7,
        gender: GenderMode = GenderMode.MAN,
        stepGoal: Int = 10_000
    ): MetricSeries {
        val today = LocalDate.now()
        val list = (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            if (back == 0) today(gender, stepGoal, LocalTime.now(), date)
            else fullDay(date, gender, stepGoal)
        }
        return MetricSeries(list)
    }

    /** Dia completo (para dias pasados). */
    fun fullDay(date: LocalDate, gender: GenderMode, stepGoal: Int): DailyMetrics {
        val rnd = Random(dateSeed(date))
        val weekend = date.dayOfWeek.value >= 6
        val genderBoost = if (gender == GenderMode.MAN) 1.05f else 0.96f
        val weekendBoost = if (weekend) 1.12f else 1f
        val steps = (stepGoal * genderBoost * weekendBoost * (0.82f + rnd.nextFloat() * 0.52f))
            .roundToInt()
        val stride = if (gender == GenderMode.MAN) 0.78 else 0.70
        val kcalPerStep = if (gender == GenderMode.MAN) 0.048 else 0.043
        val sleep = sleepFor(date.minusDays(1), gender, rnd)
        val resting = (if (gender == GenderMode.MAN) 58 else 62) + rnd.nextInt(-5, 7)

        return DailyMetrics(
            dateKey = date.toDateKey(),
            steps = steps,
            stepGoal = stepGoal,
            distanceKm = (steps * stride / 1000.0 * 100).roundToInt() / 100.0,
            caloriesKcal = (steps * kcalPerStep * (0.95f + rnd.nextFloat() * 0.12f)).roundToInt(),
            activeMinutes = min((steps / 120.0).roundToInt(), 200),
            sleepHours = sleep.sleepHours,
            sleepQuality = sleep.sleepQuality,
            restingHeartRate = resting,
            avgHeartRate = resting + rnd.nextInt(6, 16),
            maxHeartRate = (if (gender == GenderMode.MAN) 178 else 184) - rnd.nextInt(0, 16),
            spo2 = (97 + rnd.nextInt(0, 3)).coerceIn(94, 100),
            stressLevel = (34 + max(9.0 - sleep.sleepHours, 0.0) * 8 + rnd.nextInt(0, 12))
                .roundToInt().coerceIn(5, 95),
            source = "simulado",
            updatedAt = date.atStartOfDay()
                .plusHours(23)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
    }

    // ------------------------------------------------------------------ SUENO
    private data class SleepBlock(val sleepHours: Double, val sleepQuality: Int)

    /** Sueno determinista por noche (6.1 - 8.6 h con calidad coherente). */
    private fun sleepFor(night: LocalDate, gender: GenderMode, rnd: Random): SleepBlock {
        val weekend = night.dayOfWeek.value >= 6
        val base = if (gender == GenderMode.MAN) 7.0 else 7.3
        val extra = if (weekend) 0.8 else 0.0
        val hours = min(max(base + extra + rnd.nextDouble(-1.0, 1.1), 4.8), 9.6)
        val quality = ((hours - 4.5) / 5.0 * 100).roundToInt().coerceIn(35, 98)
        return SleepBlock((hours * 10).roundToInt() / 10.0, quality)
    }

    private fun dateSeed(date: LocalDate): Int =
        date.year * 1000 + date.dayOfYear  // determinista por dia

    /** Lectura de pulso "en vivo" para la pantalla de entrenamiento. */
    fun liveHeartRate(elapsedSeconds: Int): Int {
        val warmup = min(elapsedSeconds / 60f, 6f) / 6f            // 0..1
        val wave = kotlin.math.sin(elapsedSeconds / 9.0) * 4
        return (92 + warmup * 62 + wave).roundToInt().coerceIn(70, 188)
    }

    /** SpO2 "en vivo" (se mantiene en rango normal con micro oscilacion). */
    fun liveSpo2(elapsedSeconds: Int): Int =
        (97 + (kotlin.math.sin(elapsedSeconds / 21.0) * 1.4).roundToInt()).coerceIn(94, 100)

    /** Bateria simulada de la banda. */
    fun liveBattery(): Int = 60 + (System.currentTimeMillis() / 60_000 % 40).toInt()
}
