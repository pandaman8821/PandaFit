// ============================================================================
//  Panda Fit - EnvioHealthConnect.kt
//  v26: ENVIA a Health Connect lo medido por Panda Fit, para que Google Fit,
//  Samsung Health y otras apps lo vean:
//    * pasos por hora de hoy,
//    * pulso por hora de hoy (muestras por minuto),
//    * el sueno de anoche con sus fases (de la pulsera),
//    * los entrenamientos guardados (tipo, duracion, distancia y kcal).
//  Cada registro lleva un identificador propio (clientRecordId): si se vuelve a
//  enviar, Health Connect ACTUALIZA el registro en vez de duplicarlo.
//  Solo se ejecuta si el usuario lo activa en Perfil y concede los permisos.
// ============================================================================
package com.pandafit.app.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.ble.BleProtocol
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object EnvioHealthConnect {

    private const val TAG = "EnvioHealthConnect"

    /** Permisos de escritura que pide el interruptor de Perfil. */
    val permisos: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    )

    private val reloj = Device(type = Device.TYPE_WATCH)
    private val telefono = Device(type = Device.TYPE_PHONE)

    private fun meta(id: String, version: Long, dispositivo: Device = reloj) =
        Metadata.autoRecorded(device = dispositivo, clientRecordId = id, clientRecordVersion = version)

    /** Devuelve cuantos registros se enviaron (0 si no hay permiso o nada nuevo). */
    suspend fun enviar(app: PandaFitApplication): Int = runCatching {
        if (HealthConnectClient.getSdkStatus(app) != HealthConnectClient.SDK_AVAILABLE) return 0
        val c = HealthConnectClient.getOrCreate(app)
        val concedidos = c.permissionController.getGrantedPermissions()
        if (!concedidos.containsAll(permisos)) return 0

        val zona = ZoneId.systemDefault()
        val hoy = LocalDate.now()
        val registros = mutableListOf<Record>()
        val log = app.dayLog.estado.value
        val ahora = Instant.now()

        // ---- pasos por hora de hoy
        log.pasosPorHora.forEachIndexed { h, pasos ->
            if (pasos <= 0) return@forEachIndexed
            val ini = hoy.atTime(h, 0).atZone(zona)
            var fin = ini.plusHours(1).toInstant()
            if (fin.isAfter(ahora)) fin = ahora
            if (!fin.isAfter(ini.toInstant())) return@forEachIndexed
            registros += StepsRecord(
                startTime = ini.toInstant(), startZoneOffset = ini.offset,
                endTime = fin, endZoneOffset = zona.rules.getOffset(fin),
                count = pasos.toLong(),
                metadata = meta("pandafit-pasos-$hoy-$h", pasos.toLong())
            )
        }

        // ---- pulso por hora de hoy
        log.pulsos.groupBy { it.minuto / 60 }.forEach { (h, muestras) ->
            val ini = hoy.atTime(h, 0).atZone(zona).toInstant()
            val ordenadas = muestras.sortedBy { it.minuto }
            val ultima = hoy.atTime(ordenadas.last().minuto / 60, ordenadas.last().minuto % 60).atZone(zona).toInstant()
            val fin = ultima.plusSeconds(1)
            registros += HeartRateRecord(
                startTime = ini, startZoneOffset = zona.rules.getOffset(ini),
                endTime = fin, endZoneOffset = zona.rules.getOffset(fin),
                samples = ordenadas.map {
                    HeartRateRecord.Sample(
                        time = hoy.atTime(it.minuto / 60, it.minuto % 60).atZone(zona).toInstant(),
                        beatsPerMinute = it.ppm.toLong()
                    )
                },
                metadata = meta("pandafit-pulso-$hoy-$h", ordenadas.size.toLong())
            )
        }

        // ---- sueno de anoche (con fases)
        app.bleManager.sleepFromBand.value?.let { s -> suenoComoRegistro(s, hoy, zona)?.let { registros += it } }

        // ---- entrenamientos aun no enviados
        val prefs = app.getSharedPreferences("panda_hc_envio", 0)
        val enviados = prefs.getStringSet("entrenos", emptySet()).orEmpty()
        val nuevos = app.workoutHistory.sesiones.value.filter { it.id.isNotBlank() && it.id !in enviados }
        nuevos.forEach { w ->
            val ini = Instant.ofEpochMilli(w.startedAt)
            val fin = ini.plusSeconds(w.durationSeconds.toLong().coerceAtLeast(60))
            val oIni = zona.rules.getOffset(ini); val oFin = zona.rules.getOffset(fin)
            registros += ExerciseSessionRecord(
                startTime = ini, startZoneOffset = oIni, endTime = fin, endZoneOffset = oFin,
                metadata = meta("pandafit-entreno-${w.id}", 1, telefono),
                exerciseType = tipoEjercicio(w.type),
                title = "${w.type} (Panda Fit)"
            )
            if (w.distanceKm > 0.01) registros += DistanceRecord(
                startTime = ini, startZoneOffset = oIni, endTime = fin, endZoneOffset = oFin,
                distance = Length.kilometers(w.distanceKm),
                metadata = meta("pandafit-distancia-${w.id}", 1, telefono)
            )
            if (w.caloriesKcal > 0) registros += ActiveCaloriesBurnedRecord(
                startTime = ini, startZoneOffset = oIni, endTime = fin, endZoneOffset = oFin,
                energy = Energy.kilocalories(w.caloriesKcal.toDouble()),
                metadata = meta("pandafit-kcal-${w.id}", 1, telefono)
            )
        }

        if (registros.isEmpty()) return 0
        c.insertRecords(registros)
        if (nuevos.isNotEmpty()) {
            prefs.edit().putStringSet("entrenos", (enviados + nuevos.map { it.id }).toList().takeLast(200).toSet()).apply()
        }
        prefs.edit().putLong("ultimo_envio", System.currentTimeMillis()).apply()
        Log.i(TAG, "Enviados ${registros.size} registros a Health Connect")
        registros.size
    }.getOrElse {
        Log.w(TAG, "Envio a Health Connect fallo: ${it.message}")
        0
    }

    /** Ultimo envio correcto (epoch ms) o 0. */
    fun ultimoEnvio(app: PandaFitApplication): Long = app.getSharedPreferences("panda_hc_envio", 0).getLong("ultimo_envio", 0L)

    private fun tipoEjercicio(tipo: String): Int = when (tipo) {
        "Carrera" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "Ciclismo" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "Fuerza" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "Yoga" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        else -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    }

    /**
     * Convierte el sueno de la pulsera (minutos del dia) en una sesion con fechas
     * reales: los tramos "de antes" de la hora de despertar son de HOY; los de
     * despues (p. ej. 23:10) son de AYER por la noche.
     */
    fun suenoComoRegistro(s: BleProtocol.SuenoBanda, hoy: LocalDate, zona: ZoneId): SleepSessionRecord? {
        if (s.tramos.isEmpty() || s.dormidoMin <= 0) return null
        val despertar = if (s.despertarMin >= 0) s.despertarMin else s.tramos.last().let { it.inicioMin + it.duracionMin } % 1440
        fun instante(minDia: Int): Instant {
            val dia = if (minDia > despertar + 60) hoy.minusDays(1) else hoy
            return dia.atStartOfDay(zona).plusMinutes(minDia.toLong()).toInstant()
        }
        val etapas = s.tramos.filter { it.duracionMin > 0 }.map { t ->
            val ini = instante(t.inicioMin)
            SleepSessionRecord.Stage(
                startTime = ini,
                endTime = ini.plusSeconds(t.duracionMin * 60L),
                stage = when (t.tipo) {
                    BleProtocol.TipoSueno.PROFUNDO -> SleepSessionRecord.STAGE_TYPE_DEEP
                    BleProtocol.TipoSueno.LIGERO -> SleepSessionRecord.STAGE_TYPE_LIGHT
                    else -> SleepSessionRecord.STAGE_TYPE_AWAKE
                }
            )
        }.sortedBy { it.startTime }
        if (etapas.isEmpty()) return null
        val ini = etapas.first().startTime
        val fin = etapas.maxOf { it.endTime }
        if (!fin.isAfter(ini)) return null
        return SleepSessionRecord(
            startTime = ini, startZoneOffset = zona.rules.getOffset(ini),
            endTime = fin, endZoneOffset = zona.rules.getOffset(fin),
            metadata = meta("pandafit-sueno-$hoy", s.dormidoMin.toLong()),
            title = "Sueño (Panda Fit)",
            stages = etapas
        )
    }
}
