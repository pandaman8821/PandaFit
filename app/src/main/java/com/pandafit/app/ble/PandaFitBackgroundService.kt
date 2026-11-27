// ============================================================================
//  Panda Fit - PandaFitBackgroundService.kt
//  SERVICIO EN SEGUNDO PLANO: mantiene todo vivo aunque cierres la app.
//
//    * BLE: escucha pulso/SpO2/pasos de la banda de forma continua.
//    * GPS: graba el trayecto con alta precision cuando hay entrenamiento.
//    * NUBE: cada 30 s guarda las metricas en Firestore. Si no hay red, la cola
//      offline de Firestore las sube sola cuando vuelva la conexion. Si se cae
//      el Bluetooth, los datos ya guardados en el telefono NO se pierden.
//    * NOTIFICACION: obligatoria para ser un foreground service (y util: el
//      usuario ve el pulso en vivo).
//
//  Arranque: PandaFitApplication o HomeScreen llaman a start(context).
// ============================================================================
package com.pandafit.app.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pandafit.app.MainActivity
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.data.MetricsSimulator
import com.pandafit.app.data.model.BleState
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.TrackPoint
import com.pandafit.app.data.model.WorkoutSession
import com.pandafit.app.data.model.toDateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class PandaFitBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 0.1.8: GPS de Google (version completa) o de Android (version F-Droid).
    private lateinit var fusedLocation: com.pandafit.app.servicios.FuenteUbicacion
    private var collectionJob: Job? = null
    private var locationJob: Job? = null
    private var workout: WorkoutSession = WorkoutSession()
    private var workoutStartElapsed: Long = 0L
    private var lastSavedSteps: Int = 0

    /** Maximo de pulso visto durante la sesion actual (para el resumen real). */
    private var maxHeartRateSeen: Int = 0

    // v26: cronometro con pausa automatica + entrenador por voz.
    private var motor: com.pandafit.app.data.MotorEntreno? = null
    private var voz: VozEntrenador? = null
    private var entrenoJob: Job? = null
    private var sumaPulso = 0L
    private var muestrasPulso = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocation = com.pandafit.app.servicios.ServiciosSabor.ubicacion(this)
        createChannels()
        startForegroundSafely()
        // Recupera un trayecto que quedara a medias tras un cierre inesperado.
        restoreTrackPoints()
        startCollecting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WORKOUT -> startWorkout(intent.getStringExtra(EXTRA_WORKOUT_TYPE) ?: "Caminata")
            ACTION_STOP_WORKOUT -> stopWorkout()
            ACTION_PAUSE_WORKOUT -> motor?.let {
                it.pausaManual = true
                voz?.decir(frases().pausa)
                _entreno.value = it.estado()
            }
            ACTION_RESUME_WORKOUT -> motor?.let {
                it.reanudar(System.currentTimeMillis())
                voz?.decir(frases().seguimos)
                _entreno.value = it.estado()
            }
            ACTION_STOP -> stopEverything()
        }
        startForegroundSafely()
        return START_STICKY
    }

    override fun onDestroy() {
        // Si el sistema mata el servicio a mitad de entrenamiento, el trayecto
        // queda guardado en disco (persistTrackPoints) para poder recuperarlo.
        if (workoutStartElapsed > 0L && trackPoints.isNotEmpty()) {
            persistTrackPoints()
            Log.i(TAG, "Servicio destruido durante un entreno: trayecto guardado")
        }
        collectionJob?.cancel()
        locationJob?.cancel()
        entrenoJob?.cancel()
        voz?.cerrar()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ========================================================================
    //  NOTIFICACION + FOREGROUND
    // ========================================================================
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKGROUND,
                getString(com.pandafit.app.R.string.channel_background_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.pandafit.app.R.string.channel_background_desc)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AI,
                getString(com.pandafit.app.R.string.channel_ai_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(com.pandafit.app.R.string.channel_ai_desc)
            }
        )
    }

    private fun buildNotification(hr: Int, spo2: Int, steps: Int, active: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (active) {
            resources.getQuantityString(
                com.pandafit.app.R.plurals.notif_workout_text, steps, steps, hr, spo2
            )
        } else {
            getString(com.pandafit.app.R.string.notif_service_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_BACKGROUND)
            .setContentTitle(getString(com.pandafit.app.R.string.notif_service_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundSafely() {
        val notification = buildNotification(0, 0, 0, active = workoutStartElapsed > 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                val fineLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (fineLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Sin permiso del tipo de foreground service, Android lanza. NO se
            // puede seguir como "servicio invisible": se registra y se cierra
            // para no provocar un ANR ni un relanzamiento en bucle.
            Log.e(TAG, "No se pudo pasar a foreground; se detiene el servicio: ${t.message}")
            stopSelf()
        }
    }

    // ========================================================================
    //  RECOLECCION CONTINUA
    // ========================================================================
    private fun startCollecting() {
        // Cast seguro: si algo raro pasara, el servicio no debe caerse.
        val ble = (application as? PandaFitApplication)?.bleManager ?: run {
            Log.w(TAG, "Application no disponible; se detiene la recoleccion")
            stopSelf()
            return
        }
        collectionJob?.cancel()
        collectionJob = scope.launch {
            var tick = 0
            while (true) {
                delay(5_000)
                tick++
                val vitals = ble.vitals.value
                // Acumula el pulso maximo real del entrenamiento en curso.
                if (workoutStartElapsed > 0L && vitals.heartRate > maxHeartRateSeen) {
                    maxHeartRateSeen = vitals.heartRate
                }
                val steps = ble.stepsFromBand.value ?: 0
                notifyUpdate(vitals.heartRate, vitals.spo2, steps)
                // Cada 30 s persistimos + sincronizamos con la nube.
                if (tick % 6 == 0) persistAndSync(ble)
                // v21: avisos de actividad tambien con la app CERRADA (cada minuto).
                // Antes solo se revisaban con la app abierta. Las preferencias
                // con la fecha/hora de cada aviso evitan duplicados con la app.
                if (tick % 12 == 0) {
                    runCatching {
                        val app = application as? PandaFitApplication
                        if (app != null) {
                            // Primero se apuntan los pasos por hora: con la app cerrada
                            // nadie mas lo hace y el aviso de "moverse" saltaria en falso.
                            val tel = app.phoneSteps.pasosHoy.value
                            val banda = ble.stepsFromBand.value ?: 0
                            val medidos = maxOf(tel, banda)
                            if (medidos > 0) {
                                app.dayLog.registrarPasos(medidos, fuente = if (banda >= tel) "pulsera" else "telefono")
                            }
                            // 0.1.6: sueno cada hora (6-13 h) hasta que te levantes y camines.
                            ble.revisarSuenoProgramado(medidos)
                            val log = app.dayLog.estado.value
                            val avisosPrefs = getSharedPreferences("panda_avisos", MODE_PRIVATE)
                            com.pandafit.app.data.AvisosActividad.revisar(
                                this@PandaFitBackgroundService,
                                medidos, app.preferences.stepGoal, log.pasosPorHora,
                                vasosAgua = log.vasosAgua, metaAgua = app.preferences.waterGoalGlasses,
                                despertarMin = ble.sleepFromBand.value?.despertarMin
                                    ?: avisosPrefs.getInt("ultimo_despertar", -1),
                                metaSueno = app.preferences.sleepGoalHours
                            )
                            // v26: resumen del dia al archivo (logros, sueno semanal, PDF, copia).
                            app.archivarHoy()
                            com.pandafit.app.data.AvisosActividad.revisarSuenoManana(
                                this@PandaFitBackgroundService, app.archivo.dias.value, app.preferences.sleepGoalHours
                            )
                            if (tick % 180 == 0 && app.preferences.healthConnectWrite) {
                                // v26: cada ~15 min se envia lo medido a Health Connect.
                                com.pandafit.app.health.EnvioHealthConnect.enviar(app)
                            }
                            // v22: widget de la pantalla de inicio y resumen del domingo.
                            com.pandafit.app.widget.PandaWidget.actualizarDesdeApp(this@PandaFitBackgroundService)
                            com.pandafit.app.data.AvisosActividad.revisarSemanal(
                                this@PandaFitBackgroundService,
                                app.preferences.cachedHistory.days,
                                app.preferences.stepGoal,
                                com.pandafit.app.data.WorkoutHistory.resumenSemana(app.workoutHistory.sesiones.value).sesiones
                            )
                        }
                    }
                }
            }
        }
    }

    private fun notifyUpdate(hr: Int, spo2: Int, steps: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(hr, spo2, steps, active = workoutStartElapsed > 0)
        )
    }

    private suspend fun persistAndSync(ble: BleManager) {
        val app = application as? PandaFitApplication ?: return
        try {
            val today = LocalDate.now().toDateKey()
            val cached = app.preferences.cachedToday
            val vitals = ble.vitals.value
            val bandSteps = ble.stepsFromBand.value
            val liveHr = if (vitals.heartRate > 0) vitals.heartRate else cached.avgHeartRate
            val liveSpo2 = if (vitals.spo2 > 0) vitals.spo2 else cached.spo2

            val updated = if (bandSteps != null && bandSteps > 0) {
                // Hay banda: los pasos y el pulso reales mandan.
                cached.copy(
                    dateKey = today,
                    steps = maxOf(bandSteps, cached.steps),
                    stepGoal = app.preferences.stepGoal,
                    avgHeartRate = liveHr,
                    maxHeartRate = maxOf(liveHr, cached.maxHeartRate),
                    restingHeartRate = if (cached.restingHeartRate > 0) cached.restingHeartRate else liveHr,
                    spo2 = liveSpo2,
                    // 0.1.7: zancada segun la estatura del Perfil (antes 75 cm fijos).
                    distanceKm = com.pandafit.app.data.Cuerpo.kmDePasos(
                        maxOf(bandSteps, cached.steps), app.preferences.userHeightCm,
                        app.preferences.genderMode == com.pandafit.app.data.model.GenderMode.WOMAN
                    ),
                    caloriesKcal = (maxOf(bandSteps, cached.steps) * 0.046).toInt(),
                    source = "ble",
                    updatedAt = System.currentTimeMillis()
                )
            } else if (vitals.heartRate > 0 || vitals.spo2 > 0) {
                cached.copy(
                    avgHeartRate = liveHr,
                    spo2 = liveSpo2,
                    source = if (cached.source == "health_connect") "mixto" else cached.source,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                // Sin banda: refrescamos el modelo simulado para que la pantalla
                // principal nunca se quede congelada.
                MetricsSimulator.today(app.preferences.genderMode, app.preferences.stepGoal)
                    .copy(source = cached.source)
            }

            lastSavedSteps = updated.steps
            app.preferences.cachedToday = updated
            app.repository.syncDay(updated)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo persistir el ciclo: ${t.message}")
        }
    }

    // ========================================================================
    //  GPS DE ALTA PRECISION
    // ========================================================================
    private fun alRecibirUbicacion(location: Location) {
        run {
            val point = TrackPoint(
                lat = location.latitude,
                lon = location.longitude,
                timestamp = System.currentTimeMillis(),
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 0f
            )
            trackPoints = trackPoints + point
            _lastTrackPoint.value = point
            motor?.let { m -> if (m.punto(point)) _entreno.value = m.estado() }
            // El trayecto se va guardando en disco: si el proceso muere a mitad
            // de entrenamiento NO se pierden los kilometros ya hechos.
            if (trackPoints.size % 10 == 0) persistTrackPoints()
        }
    }

    /**
     * Guarda el trayecto actual en un archivo propio del servicio.
     * Se escribe cada 10 puntos (~20 s) para no castigar la bateria.
     */
    private fun persistTrackPoints() {
        runCatching {
            val json = kotlinx.serialization.json.Json { encodeDefaults = true }
            getSharedPreferences(PREFS_TRAIL, MODE_PRIVATE).edit()
                .putString(KEY_TRAIL, json.encodeToString(trackPoints))
                .putLong(KEY_TRAIL_STARTED, workoutStartElapsed)
                .putString(KEY_TRAIL_TYPE, workout.type)
                .apply()
        }.onFailure { Log.w(TAG, "No se pudo guardar el trayecto: ${it.message}") }
    }

    /** Recupera un trayecto que quedo a medias tras un cierre inesperado. */
    private fun restoreTrackPoints() {
        runCatching {
            val prefs = getSharedPreferences(PREFS_TRAIL, MODE_PRIVATE)
            val raw = prefs.getString(KEY_TRAIL, null) ?: return
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val saved: List<TrackPoint> = json.decodeFromString(raw)
            if (saved.size >= 2) {
                trackPoints = saved
                val started = prefs.getLong(KEY_TRAIL_STARTED, 0L)
                if (started > 0L) {
                    // Se conserva el entrenamiento anterior para no perderlo:
                    // se cierra como sesion valida al arrancar.
                    workoutStartElapsed = started
                    val tipo = prefs.getString(KEY_TRAIL_TYPE, null) ?: "Caminata"
                    workout = WorkoutSession(type = tipo, id = java.util.UUID.randomUUID().toString(), startedAt = started)
                    iniciarMotor(tipo, anunciar = false)
                    motor?.restaurar(((System.currentTimeMillis() - started) / 1000).toInt(), saved)
                    _activeWorkout.value = workout
                    Log.i(TAG, "Trayecto recuperado: ${saved.size} puntos")
                }
            }
        }.onFailure { Log.w(TAG, "Trayecto no recuperable: ${it.message}") }
    }

    private fun clearTrackPoints() {
        trackPoints = emptyList()
        runCatching {
            getSharedPreferences(PREFS_TRAIL, MODE_PRIVATE).edit()
                .remove(KEY_TRAIL)
                .remove(KEY_TRAIL_STARTED)
                .apply()
        }
    }

    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            Log.w(TAG, "GPS sin permiso: se graba el entrenamiento sin trayecto")
            return
        }
        locationJob?.cancel()
        locationJob = scope.launch {
            runCatching { fusedLocation.empezarSeguimiento(::alRecibirUbicacion) }
                .onFailure { Log.w(TAG, "GPS no disponible: ${it.message}") }
        }
    }

    private fun stopLocationUpdates() {
        runCatching { fusedLocation.pararSeguimiento() }
        locationJob?.cancel()
    }

    // ========================================================================
    //  ENTRENAMIENTO
    // ========================================================================
    private fun startWorkout(type: String) {
        // Un entrenamiento nuevo empieza con el trayecto a cero.
        clearTrackPoints()
        maxHeartRateSeen = 0
        workout = WorkoutSession(type = type, id = java.util.UUID.randomUUID().toString())
        workoutStartElapsed = System.currentTimeMillis()
        _activeWorkout.value = workout
        sumaPulso = 0; muestrasPulso = 0
        iniciarMotor(type, anunciar = true)
        startLocationUpdates()
        // v10: iniciar un entreno ya NO pone al reloj a medir pulso y oxigeno
        // (antes llamaba a requestImmediateMeasurement y el reloj media "solo").
        // Se escucha el pulso que publique el reloj; para medirlo hay un boton
        // "Medir pulso" en la pantalla del entreno.
    }

    /** 0.1.7: frases del entrenador en el idioma del telefono. */
    private fun frases() = com.pandafit.app.data.FrasesCoach(com.pandafit.app.ai.Idioma.actual())

    private fun nombreActividadRes(tipo: String): Int = when (tipo) {
        "Carrera" -> com.pandafit.app.R.string.act_run
        "Ciclismo" -> com.pandafit.app.R.string.act_bike
        "Fuerza" -> com.pandafit.app.R.string.act_strength
        "Yoga" -> com.pandafit.app.R.string.act_yoga
        else -> com.pandafit.app.R.string.act_walk
    }

    /** Crea el cronometro (con pausa automatica) y, si se quiere, la voz. */
    private fun iniciarMotor(tipo: String, anunciar: Boolean) {
        val prefs = (application as? PandaFitApplication)?.preferences
        val usaGps = tipo in TIPOS_CON_GPS
        val m = com.pandafit.app.data.MotorEntreno(tipo, usaGps, autoPausa = prefs?.autoPauseEnabled ?: true, idioma = com.pandafit.app.ai.Idioma.actual())
        motor = m
        if (prefs?.voiceCoachEnabled != false) {
            if (voz == null) voz = VozEntrenador(this)
            if (anunciar) voz?.decir(frases().empezamos(getString(nombreActividadRes(tipo)).lowercase()))
        }
        _entreno.value = m.estado()
        entrenoJob?.cancel()
        entrenoJob = scope.launch {
            while (true) {
                delay(1_000)
                val pulso = (application as? PandaFitApplication)?.bleManager?.vitals?.value?.heartRate ?: 0
                if (pulso in 30..220 && !m.pausaManual && !m.pausaAuto) { sumaPulso += pulso; muestrasPulso++ }
                m.tic(System.currentTimeMillis(), pulso).forEach { voz?.decir(it) }
                _entreno.value = m.estado()
            }
        }
    }

    private suspend fun stopWorkoutInternal() {
        if (workoutStartElapsed == 0L) return
        val m = motor
        entrenoJob?.cancel()
        // v26: la duracion es el tiempo EN MOVIMIENTO (sin pausas) y la distancia
        // la del GPS filtrado (antes contaba tambien el ruido del GPS parado).
        val seconds = m?.segundos ?: ((System.currentTimeMillis() - workoutStartElapsed) / 1000).toInt()
        val app = application as? PandaFitApplication
        val points = trackPoints.toList()
        val distance = if (m != null && m.km > 0) m.km else geoDistanceKm(points)
        val type = workout.type
        val currentHr = app?.bleManager?.vitals?.value?.heartRate ?: 0
        val met = MET[type] ?: 4.0
        val peso = (app?.preferences?.userWeightKg ?: 70).toDouble()
        val finished = workout.copy(
            durationSeconds = seconds,
            steps = app?.preferences?.cachedToday?.steps ?: 0,
            distanceKm = (distance * 100).toInt() / 100.0,
            caloriesKcal = (met * peso * seconds / 3600.0).toInt(),
            avgHeartRate = if (muestrasPulso > 0) (sumaPulso / muestrasPulso).toInt() else currentHr,
            // El maximo es el mayor pulso visto durante TODO el entrenamiento,
            // no la ultima lectura instantanea.
            maxHeartRate = maxOf(maxHeartRateSeen, currentHr),
            trackPoints = points
        )
        _activeWorkout.value = null
        _lastWorkout.value = finished
        // v20: queda tambien en el historial local del telefono.
        runCatching { app?.workoutHistory?.anadir(finished) }
        app?.repository?.syncWorkout(finished)
        workoutStartElapsed = 0L
        maxHeartRateSeen = 0
        clearTrackPoints()
        stopLocationUpdates()
        motor = null
        _entreno.value = null
        voz?.let { v ->
            val km = finished.distanceKm
            v.decir(frases().terminado(km, seconds))
            scope.launch { delay(12_000); if (motor == null) { v.cerrar(); if (voz === v) voz = null } }
        }
        notifyUpdate(0, 0, finished.steps)
        Log.i(
            TAG,
            "Entrenamiento guardado: ${finished.type} ${seconds}s ${finished.distanceKm} km " +
                "${finished.trackPoints.size} puntos GPS"
        )
    }

    private fun stopWorkout() {
        scope.launch { stopWorkoutInternal() }
    }

    /** Suma haversine entre los puntos del trayecto. */
    private fun geoDistanceKm(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(a.lat)) * kotlin.math.cos(Math.toRadians(b.lat)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            total += 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
        }
        return total
    }

    private fun stopEverything() {
        stopWorkout()
        stopLocationUpdates()
        stopSelf()
    }

    companion object {
        private const val TAG = "PandaFitService"
        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_BACKGROUND = "panda_fit_canal"
        private const val CHANNEL_AI = "panda_fit_ia"

        const val ACTION_START_WORKOUT = "com.pandafit.app.START_WORKOUT"
        const val ACTION_STOP_WORKOUT = "com.pandafit.app.STOP_WORKOUT"
        const val ACTION_STOP = "com.pandafit.app.STOP_SERVICE"
        const val EXTRA_WORKOUT_TYPE = "tipo_entrenamiento"
        const val ACTION_PAUSE_WORKOUT = "com.pandafit.app.PAUSE_WORKOUT"
        const val ACTION_RESUME_WORKOUT = "com.pandafit.app.RESUME_WORKOUT"
        private const val KEY_TRAIL_TYPE = "trayecto_tipo"

        /** Actividades que usan GPS (distancia, ritmo, mapa y pausa automatica). */
        val TIPOS_CON_GPS = setOf("Caminata", "Carrera", "Ciclismo")
        /** MET por actividad para estimar kcal con el peso del perfil. */
        private val MET = mapOf("Caminata" to 3.5, "Carrera" to 9.8, "Ciclismo" to 7.5, "Fuerza" to 5.0, "Yoga" to 2.5)

        private val _entreno = MutableStateFlow<com.pandafit.app.data.EntrenoEstado?>(null)
        /** v26: entreno en curso (tiempo en movimiento, km, pausa, recorrido). */
        val entreno: StateFlow<com.pandafit.app.data.EntrenoEstado?> = _entreno.asStateFlow()

        fun pauseWorkout(context: Context) = sendAction(context, ACTION_PAUSE_WORKOUT)
        fun resumeWorkout(context: Context) = sendAction(context, ACTION_RESUME_WORKOUT)

        /** Archivo donde se guarda el trayecto GPS en curso (nada de coordenadas en RAM eterna). */
        private const val PREFS_TRAIL = "panda_fit_trayecto"
        private const val KEY_TRAIL = "trayecto_json"
        private const val KEY_TRAIL_STARTED = "trayecto_iniciado"

        @Volatile
        private var instance: PandaFitBackgroundService? = null

        private val _lastTrackPoint = MutableStateFlow<TrackPoint?>(null)
        /** Ultimo punto GPS (lo lee TrainingScreen sin acoplarse al servicio). */
        val lastTrackPoint: StateFlow<TrackPoint?> = _lastTrackPoint.asStateFlow()

        private val _activeWorkout = MutableStateFlow<WorkoutSession?>(null)
        /** Entrenamiento en curso, o null. */
        val activeWorkout: StateFlow<WorkoutSession?> = _activeWorkout.asStateFlow()

        private val _lastWorkout = MutableStateFlow<WorkoutSession?>(null)
        /** Ultimo entrenamiento terminado (resumen). */
        val lastWorkout: StateFlow<WorkoutSession?> = _lastWorkout.asStateFlow()

        private var trackPoints: List<TrackPoint> = emptyList()

        /** Arranca el servicio (idempotente). */
        fun start(context: Context) {
            // Android 14+ no deja pasar a foreground (tipo connectedDevice) sin
            // BLUETOOTH_CONNECT. Arrancarlo sin el provoca el cierre "did not
            // then call startForeground". MainActivity lo vuelve a llamar en
            // cuanto el usuario concede los permisos.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Servicio aplazado: falta permiso BLUETOOTH_CONNECT")
                return
            }
            // minSdk 26: startForegroundService existe siempre.
            runCatching {
                context.startForegroundService(
                    Intent(context, PandaFitBackgroundService::class.java)
                )
            }.onFailure { Log.w(TAG, "No se pudo arrancar el servicio: ${it.message}") }
        }

        fun startWorkout(context: Context, type: String) {
            val intent = Intent(context, PandaFitBackgroundService::class.java).apply {
                action = ACTION_START_WORKOUT
                putExtra(EXTRA_WORKOUT_TYPE, type)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "No se pudo iniciar el entreno: ${it.message}") }
        }

        fun stopWorkout(context: Context) {
            // startService puede lanzar IllegalStateException si la app esta en
            // background en API 26+: se intenta con startService y, si falla,
            // con startForegroundService (el servicio ya es foreground).
            sendAction(context, ACTION_STOP_WORKOUT)
        }

        fun stop(context: Context) {
            sendAction(context, ACTION_STOP)
        }

        /** Envia una accion al servicio de forma segura en cualquier API. */
        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, PandaFitBackgroundService::class.java)
                .setAction(action)
            runCatching { context.startForegroundService(intent) }.onFailure {
                Log.w(TAG, "No se pudo enviar $action: ${it.message}")
            }
        }
    }
}
