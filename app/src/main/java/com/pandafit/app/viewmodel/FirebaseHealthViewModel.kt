// ============================================================================
//  Panda Fit - FirebaseHealthViewModel.kt
//  FUENTE UNICA DE VERDAD de los datos de salud de Panda Fit.
//
//  Fusiona, en este orden de prioridad:
//     1. Datos REALES de Health Connect (si el usuario los autorizo).
//     2. Datos en vivo de la banda BLE (pulso/SpO2/pasos reales).
//     3. Datos de DEMO simulados (siempre hay algo que mostrar).
//  y los publica como un unico [PandaUiState] para que la interfaz no tenga
//  que combinar flujos (menos recomposiciones y mas bateria).
//
//  Ademas:
//   * Guarda TODO en el telefono antes de subirlo (cero perdida de datos).
//   * Sincroniza con FIREBASE FIRESTORE en cuanto hay red (cola offline propia).
//   * Expone el estado BLE y los insights locales calculados por FitnessEngine.
// ============================================================================
package com.pandafit.app.viewmodel

import android.app.Application
import com.pandafit.app.R
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.ai.FitnessEngine
import com.pandafit.app.data.MetricsSimulator
import com.pandafit.app.data.model.BleState
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.HealthReport
import com.pandafit.app.data.model.LiveVitals
import com.pandafit.app.data.model.MetricSeries
import com.pandafit.app.data.model.PandaDevice
import com.pandafit.app.data.model.WorkoutSession
import com.pandafit.app.data.model.toDateKey
import com.pandafit.app.health.HealthConnectHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Estado completo de la pantalla principal (un solo objeto). */
data class PandaUiState(
    val today: DailyMetrics = DailyMetrics.empty(),
    val history: MetricSeries = MetricSeries(),
    val vitals: LiveVitals = LiveVitals(),
    val bleState: BleState = BleState.DISCONNECTED,
    val bleDevices: List<PandaDevice> = emptyList(),
    val bleDeviceName: String = "",
    val healthConnectAvailable: Boolean = false,
    val healthConnectGranted: Boolean = false,
    val usingRealData: Boolean = false,
    val online: Boolean = true,
    val cloudSynced: Boolean = true,
    val report: HealthReport = HealthReport("", 0),
    val activeWorkout: WorkoutSession? = null,
    val lastWorkout: WorkoutSession? = null,
    val lastTrackPoint: com.pandafit.app.data.model.TrackPoint? = null,
    val gender: com.pandafit.app.data.model.GenderMode = com.pandafit.app.data.model.GenderMode.MAN
) {
    val stepProgress: Float get() = today.stepProgress
    val stepsLeft: Int get() = (today.stepGoal - today.steps).coerceAtLeast(0)
}

/** true si el dispositivo tiene una red con salida a internet ahora mismo. */
private fun ConnectivityManager.isCurrentlyOnline(): Boolean =
    activeNetwork?.let { network ->
        getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } ?: false

class FirebaseHealthViewModel(application: Application) : AndroidViewModel(application) {

    /** Contexto de la aplicacion (nunca la Activity: no se filtra). */
    private val appContext: Application = application

    private val pandaApp = application as PandaFitApplication
    private val prefs = pandaApp.preferences
    private val repository = pandaApp.repository
    private val ble = pandaApp.bleManager
    private val phoneSteps = pandaApp.phoneSteps
    private val dayLogStore = pandaApp.dayLog

    /** Registro del dia para el panel "Hoy" (pasos por hora, pulso, presion, SpO2). */
    val dayLog: StateFlow<com.pandafit.app.data.DayLog.Snapshot> = dayLogStore.estado

    /** Sueno de la ultima noche con sus fases (null si la pulsera no lo ha enviado). */
    val bandSleep: StateFlow<com.pandafit.app.ble.BleProtocol.SuenoBanda?> = ble.sleepFromBand

    /** Edad para las zonas de pulso (Perfil > Metas). */
    // Se lee de las preferencias compartidas: cada pantalla tiene su propio
    // ViewModel y asi Inicio ve al momento la edad cambiada en Perfil.
    val userAge: Int get() = prefs.userAge

    fun setUserAge(edad: Int) {
        prefs.userAge = edad
    }

    /** Ultima hora de despertar conocida (minuto del dia), para el aviso de dormir. */
    private val avisosPrefs = application.getSharedPreferences("panda_avisos", Context.MODE_PRIVATE)
    private fun ultimoDespertar(): Int = avisosPrefs.getInt("ultimo_despertar", -1)
    private fun guardarDespertar(min: Int) {
        if (avisosPrefs.getInt("ultimo_despertar", -1) != min) {
            avisosPrefs.edit().putInt("ultimo_despertar", min).apply()
        }
    }

    /** Hidratacion (v19): vasos de 250 ml de hoy. */
    val waterGoal: Int get() = prefs.waterGoalGlasses
    fun addWater(delta: Int) {
        dayLogStore.cambiarAgua(delta)
        com.pandafit.app.widget.PandaWidget.actualizarDesdeApp(appContext)
    }

    fun measureBloodPressure() = ble.requestBloodPressure()
    /** 0.1.7: texto traducible. */
    private fun txt(id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)

    fun measureHeartRate() = ble.requestHeartRate()
    fun measureSpo2() = ble.requestSpo2()
    fun readBandSleep() = ble.requestSleep()
    private val healthConnect = HealthConnectHelper(application)

    private val _today = MutableStateFlow(prefs.cachedToday)
    private val _history = MutableStateFlow(prefs.cachedHistory)
    private val _healthConnectGranted = MutableStateFlow(prefs.healthConnectGranted)
    private val _cloudSynced = MutableStateFlow(true)
    private val _syncMessage = MutableStateFlow("")

    val syncMessage: StateFlow<String> = _syncMessage.asStateFlow()

    private val _gender = MutableStateFlow(prefs.genderMode)

    /** Conectividad actual (para el indicador de nube y la cola offline). */
    private val _online = MutableStateFlow(true)

    /** Ultimo punto GPS publicado por el servicio en segundo plano. */
    private val _lastTrackPoint = MutableStateFlow<com.pandafit.app.data.model.TrackPoint?>(null)

    // ------------------------------------------------------------- CONECTIVIDAD
    /**
     * Observa la conectividad en vivo y actualiza [_online]. Se guarda como
     * StateFlow para poder combinarlo con el resto de fuentes del panel.
     */
    private fun observeConnectivity() {
        viewModelScope.launch {
            val manager = appContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            _online.value = manager.isCurrentlyOnline()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                }

                override fun onLost(network: Network) {
                    _online.value = manager.isCurrentlyOnline()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value =
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { manager.registerNetworkCallback(request, callback) }
            awaitCancellation()
        }
    }

    // ------------------------------------------------------------------ ESTADO
    // Las 8 fuentes se combinan en un unico StateFlow: la interfaz observa un
    // solo objeto y evita recomposiciones por cada flujo por separado.
    private data class RawState(
        val today: DailyMetrics,
        val history: MetricSeries,
        val vitals: LiveVitals,
        val bleState: BleState,
        val devices: List<PandaDevice>,
        val hcGranted: Boolean,
        val online: Boolean,
        val gender: com.pandafit.app.data.model.GenderMode
    )

    private val rawState: Flow<RawState> = combine(
        _today, _history, ble.vitals, ble.state, ble.devices, _healthConnectGranted,
        _online, _gender
    ) { values ->
        RawState(
            today = values[0] as DailyMetrics,
            history = values[1] as MetricSeries,
            vitals = values[2] as LiveVitals,
            bleState = values[3] as BleState,
            devices = (values[4] as List<*>).filterIsInstance<PandaDevice>(),
            hcGranted = values[5] as Boolean,
            online = values[6] as Boolean,
            gender = values[7] as com.pandafit.app.data.model.GenderMode
        )
    }

    val uiState: StateFlow<PandaUiState> = rawState
        .map { raw -> buildUiState(raw) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PandaUiState(today = prefs.cachedToday))

    private fun buildUiState(raw: RawState): PandaUiState {
        // Fusion: Health Connect manda en los agregados del dia; la banda BLE
        // aporta el pulso y el SpO2 en vivo.
        val today = raw.today
        val vitals = raw.vitals
        val merged = when {
            vitals.heartRate > 0 && vitals.spo2 > 0 -> today.copy(
                avgHeartRate = vitals.heartRate,
                maxHeartRate = maxOf(today.maxHeartRate, vitals.heartRate),
                spo2 = vitals.spo2,
                source = if (today.source == "health_connect") "mixto" else today.source
            )
            vitals.heartRate > 0 -> today.copy(
                avgHeartRate = vitals.heartRate,
                maxHeartRate = maxOf(today.maxHeartRate, vitals.heartRate)
            )
            else -> today
        }

        // v13: la barra de HOY de las graficas semanales usa los datos en vivo
        // (antes mostraba lo ultimo guardado y no cuadraba con la tarjeta).
        val semana = MetricSeries(
            (raw.history.days.filterNot { it.dateKey == merged.dateKey } + merged)
                .sortedBy { it.dateKey }
                .takeLast(7)
        )

        return PandaUiState(
            today = merged,
            history = semana,
            vitals = vitals,
            bleState = raw.bleState,
            bleDevices = raw.devices,
            bleDeviceName = ble.connectedName,
            healthConnectAvailable = healthConnect.isAvailable(),
            healthConnectGranted = raw.hcGranted,
            usingRealData = merged.source != "simulado",
            online = raw.online,
            cloudSynced = _cloudSynced.value,
            report = FitnessEngine.analyze(merged, raw.history, prefs.sleepGoalHours),
            lastTrackPoint = _lastTrackPoint.value,
            gender = raw.gender
        )
    }

    /** Entrenamiento en curso (lo publica el servicio en segundo plano). */
    val activeWorkout: StateFlow<WorkoutSession?> =
        com.pandafit.app.ble.PandaFitBackgroundService.activeWorkout

    /** Ultimo entrenamiento terminado. */
    val lastWorkout: StateFlow<WorkoutSession?> =
        com.pandafit.app.ble.PandaFitBackgroundService.lastWorkout

    init {
        bootstrap()
    }

    /**
     * Arranque: pinta datos al instante (cache local) y despues intenta mejorar
     * la calidad de los datos (Health Connect -> nube -> simulador).
     */
    private fun bootstrap() {
        // 1) Lo que ya hay en el telefono, inmediato y sin red.
        _today.value = prefs.cachedToday.copy(
            stepGoal = prefs.stepGoal,
            dateKey = LocalDate.now().toDateKey()
        )
        _history.value = prefs.cachedHistory

        // Conectividad en vivo -> estado de la nube.
        observeConnectivity()

        // Sincroniza en cuanto la red esta disponible: primero la cola local
        // (entrenamientos, planes y chat pendientes) y luego el dia de hoy.
        viewModelScope.launch {
            _online.collect { isOnline ->
                if (isOnline && prefs.cloudSyncEnabled) {
                    repository.ensureSignedIn()
                    repository.retryPending()
                    val ok = repository.syncDay(_today.value)
                    _cloudSynced.value = ok
                }
            }
        }

        // METRICAS EN VIVO: cada 3 s, si el podometro del telefono o la pulsera
        // han contado pasos nuevos, se recalculan pasos, distancia y calorias.
        // Antes solo se refrescaba cada 60 s y, sin pulsera, nunca avanzaban.
        viewModelScope.launch {
            phoneSteps.iniciar()
            var ultimos = -1
            while (true) {
                val pasosTel = phoneSteps.pasosHoy.value
                val pasosBanda = ble.stepsFromBand.value ?: 0
                val ahora = maxOf(pasosTel, pasosBanda)
                if (ahora != ultimos && ahora > 0) {
                    ultimos = ahora
                    dayLogStore.registrarPasos(
                        ahora,
                        fuente = if (pasosBanda >= pasosTel) "pulsera" else "telefono"
                    )
                    // v18: felicitacion al cumplir la meta (1 vez al dia).
                    com.pandafit.app.data.AvisosActividad.revisar(
                        appContext, ahora, prefs.stepGoal, dayLogStore.estado.value.pasosPorHora
                    )
                    val actual = _today.value
                    val mezcla = mezclarFuentes(
                        base = if (actual.source != "simulado") actual else DailyMetrics.empty(prefs.stepGoal),
                        real = null,
                        banda = bandMetric(actual)
                    )
                    _today.value = mezcla
                    prefs.cachedToday = mezcla
                }
                delay(3_000)
            }
        }

        // Mediciones de la pulsera -> registro del dia (curva de pulso, SpO2, presion).
        viewModelScope.launch {
            ble.vitals.collect { v ->
                val en = if (v.measuredAt > 0) v.measuredAt else System.currentTimeMillis()
                if (v.heartRate > 0) dayLogStore.registrarPulso(v.heartRate, en)
                if (v.spo2 > 0) dayLogStore.registrarSpo2(v.spo2, en)
            }
        }
        viewModelScope.launch {
            ble.bloodPressure.collect { p ->
                if (p != null) dayLogStore.registrarPresion(p.first, p.second)
            }
        }

        // Sueno del reloj: se aplica en cuanto llega (sin esperar al refresco
        // periodico de 60 s).
        viewModelScope.launch {
            ble.sleepFromBand.collect { sueno ->
                if (sueno == null) return@collect
                val actual = _today.value
                val mezcla = mezclarFuentes(
                    base = if (actual.source != "simulado") actual else DailyMetrics.empty(prefs.stepGoal),
                    real = null,
                    banda = bandMetric(actual)
                )
                _today.value = mezcla
                prefs.cachedToday = mezcla
            }
        }

        // Punto GPS del servicio en segundo plano.
        viewModelScope.launch {
            com.pandafit.app.ble.PandaFitBackgroundService.lastTrackPoint.collect {
                _lastTrackPoint.value = it
            }
        }

        viewModelScope.launch {
            // 2) Health Connect (datos reales).
            if (healthConnect.isAvailable() && prefs.healthConnectGranted) {
                refreshFromHealthConnect()
            }

            // 3) Nube: sesion anonima + reintento de la cola local + descarga.
            //    0.0.5: solo si Firebase esta configurado (si no, nada de "subiendo").
            if (prefs.cloudSyncEnabled && repository.firebaseReady) {
                _syncMessage.value = txt(R.string.sync_syncing)
                repository.ensureSignedIn()
                repository.retryPending()
                val remote = repository.fetchRecentDays(14)
                if (remote.days.isNotEmpty()) {
                    _history.value = remote
                    prefs.cachedHistory = remote
                    remote.days.firstOrNull { it.dateKey == LocalDate.now().toDateKey() }
                        ?.let { todayRemote ->
                            _today.value = todayRemote.copy(stepGoal = prefs.stepGoal)
                            prefs.cachedToday = _today.value
                        }
                }
                val pending = repository.pendingCount()
                _cloudSynced.value = pending == 0
                _syncMessage.value = when {
                    pending > 0 -> "$pending pendiente(s) de subir"
                    else -> ""
                }
            }

            // 4) Datos simulados para que la pantalla nunca este vacia.
            if (_today.value.steps == 0) {
                val simulated = MetricsSimulator.today(_gender.value, prefs.stepGoal)
                _today.value = simulated
                prefs.cachedToday = simulated
            }
            if (_history.value.days.isEmpty()) {
                _history.value = MetricsSimulator.history(7, _gender.value, prefs.stepGoal)
            }

            // 5) Reconexion instantanea a la banda conocida.
            prefs.lastBleDeviceAddress?.let { ble.connectLastKnown(it) }

            // 6) Refresco periodico del dia (pasos avanzan aunque no haya banda).
            startPeriodicRefresh()
        }
    }

    private suspend fun startPeriodicRefresh() {
        while (true) {
            delay(60_000)
            val current = _today.value
            if (current.dateKey != LocalDate.now().toDateKey()) {
                // Dia nuevo: se parte de cero y se rehace la mezcla de fuentes.
                phoneSteps.refrescarDia()
                ble.nuevoDia()
                dayLogStore.refrescarDia()
                _history.value = MetricsSimulator.history(7, _gender.value, prefs.stepGoal)
                _today.value = mezclarFuentes(DailyMetrics.empty(prefs.stepGoal), null, null)
            } else {
                // MEZCLA: los datos reales se conservan y lo generado solo
                // rellena los huecos. Si no hay ninguna fuente real, los pasos
                // generados siguen avanzando con el reloj.
                val hayFuenteReal = current.source != "simulado"
                val banda = bandMetric(current)
                _today.value = mezclarFuentes(
                    base = if (hayFuenteReal) current else DailyMetrics.empty(prefs.stepGoal),
                    real = null,
                    banda = banda
                )
            }
            prefs.cachedToday = _today.value
            // v18: recordatorio de moverse (y meta) con pasos MEDIDOS, nunca los de ejemplo.
            val medidos = maxOf(phoneSteps.pasosHoy.value, ble.stepsFromBand.value ?: 0)
            com.pandafit.app.data.AvisosActividad.revisar(
                appContext, medidos, prefs.stepGoal, dayLogStore.estado.value.pasosPorHora,
                vasosAgua = dayLogStore.estado.value.vasosAgua, metaAgua = prefs.waterGoalGlasses,
                despertarMin = ble.sleepFromBand.value?.despertarMin ?: ultimoDespertar(),
                metaSueno = prefs.sleepGoalHours
            )
            ble.sleepFromBand.value?.despertarMin?.takeIf { it >= 0 }?.let { guardarDespertar(it) }
            // v22: widget y resumen semanal (tambien los hace el servicio).
            com.pandafit.app.widget.PandaWidget.actualizarDesdeApp(appContext)
            com.pandafit.app.data.AvisosActividad.revisarSemanal(
                appContext, _history.value.days, prefs.stepGoal,
                com.pandafit.app.data.WorkoutHistory.resumenSemana(pandaApp.workoutHistory.sesiones.value).sesiones
            )
            if (prefs.cloudSyncEnabled && _online.value) {
                repository.syncDay(_today.value)
            }
        }
    }

    /**
     * Convierte lo que sabemos de la banda en un [DailyMetrics] parcial, para
     * poder mezclarlo con Health Connect y con lo generado.
     * Devuelve null si la banda no ha aportado nada todavia.
     */
    private fun bandMetric(base: DailyMetrics): DailyMetrics? {
        val vitales = ble.vitals.value
        val pasosBanda = ble.stepsFromBand.value
        val suenoBanda = ble.sleepFromBand.value
        // Podometro del propio telefono: pasos REALES aunque no haya pulsera.
        val pasosTelefono = phoneSteps.pasosHoy.value
        val hayBanda = (pasosBanda != null && pasosBanda > 0) ||
            vitales.heartRate > 0 || vitales.spo2 > 0 || suenoBanda != null
        if (!hayBanda && pasosTelefono <= 0) return null

        val pasos = maxOf(
            pasosBanda ?: 0,
            pasosTelefono,
            if (base.source == "health_connect") base.steps else 0
        )
        return DailyMetrics(
            dateKey = LocalDate.now().toDateKey(),
            steps = pasos,
            stepGoal = prefs.stepGoal,
            // Zancada media y gasto aproximado para los pasos que vienen del reloj.
            distanceKm = if (pasos > 0) (pasos * 0.74) / 1000.0 else 0.0,
            caloriesKcal = if (pasos > 0) (pasos * 0.046).toInt() else 0,
            // Sueno real del reloj (0x32), si ya lo ha enviado.
            sleepHours = suenoBanda?.horas ?: 0.0,
            sleepQuality = suenoBanda?.calidad ?: 0,
            restingHeartRate = 0,
            avgHeartRate = vitales.heartRate,
            maxHeartRate = vitales.heartRate,
            spo2 = vitales.spo2,
            stressLevel = 0,
            source = if (hayBanda) "ble" else "telefono",
            updatedAt = System.currentTimeMillis()
        )
    }

    // ========================================================================
    //  HEALTH CONNECT
    // ========================================================================
    suspend fun refreshFromHealthConnect() {
        try {
            val real = healthConnect.readToday(stepGoal = prefs.stepGoal)
            if (real != null) {
                // MEZCLA: los datos reales entran y lo generado rellena los
                // huecos (asi nunca se sustituye un dato real por uno simulado).
                val mezcla = mezclarFuentes(_today.value, real = real, banda = bandMetric(_today.value))
                _today.value = mezcla
                prefs.cachedToday = mezcla
                _healthConnectGranted.value = true
                prefs.healthConnectGranted = true
                val series = healthConnect.readHistory(7, prefs.stepGoal)
                if (series.isNotEmpty()) {
                    _history.value = MetricSeries(series)
                    prefs.cachedHistory = _history.value
                }
                repository.syncDay(mezcla)
            } else {
                _syncMessage.value = txt(R.string.sync_hc_no_perm)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Health Connect fallo: ${t.message}")
        }
    }

    fun onHealthConnectGranted(granted: Boolean) {
        _healthConnectGranted.value = granted
        prefs.healthConnectGranted = granted
        if (granted) viewModelScope.launch { refreshFromHealthConnect() }
    }

    fun healthConnectHelper(): HealthConnectHelper = healthConnect

    // ========================================================================
    //  BLE
    // ========================================================================
    fun startBleScan(aggressive: Boolean = true) = ble.startScan(aggressive)

    fun stopBleScan() = ble.stopScan()

    fun connectDevice(address: String) {
        ble.connect(address)
        prefs.lastBleDeviceAddress = address
    }

    fun disconnectDevice() {
        ble.disconnect()
        prefs.lastBleDeviceAddress = null
    }

    fun requestMeasurement() = ble.requestImmediateMeasurement()

    /** Pide al reloj una relectura completa de sus datos (boton "Sincronizar banda"). */
    fun syncBand() {
        ble.requestFullSync()
        _syncMessage.value = txt(R.string.sync_asking_watch)
        viewModelScope.launch {
            delay(2_500)
            _syncMessage.value = ""
        }
    }

    /**
     * DIAGNOSTICO BLE completo. Funciona tambien SIN conexion activa: muestra
     * estado, permisos, dispositivo objetivo, dispositivos vistos y servicios.
     * Es lo que se pinta en pantalla desde la tarjeta de conexion del inicio.
     */
    fun bleDiagnostics(): String = ble.diagnostico()

    /** Volcado de servicios BLE del reloj conectado (diagnostico). */
    fun dumpBleServices(): String = ble.dumpServices()

    fun bleLogs(): Flow<String> = ble.log
    fun bleSteps(): StateFlow<Int?> = ble.stepsFromBand

    // ========================================================================
    //  PERFIL Y PREFERENCIAS
    // ========================================================================
    fun setGender(gender: com.pandafit.app.data.model.GenderMode) {
        prefs.genderMode = gender
        _gender.value = gender
        if (_today.value.source == "simulado") {
            val fresh = MetricsSimulator.today(gender, prefs.stepGoal)
            _today.value = fresh
            prefs.cachedToday = fresh
        }
    }

    fun setStepGoal(goal: Int) {
        prefs.stepGoal = goal
        _today.value = _today.value.copy(stepGoal = goal)
        prefs.cachedToday = _today.value
    }

    /**
     * MEZCLA DE FUENTES: real gana, simulado rellena los huecos.
     *
     * El usuario pidio expresamente que los datos generados localmente convivan
     * con los reales en lugar de sustituirse. Reglas:
     *   * Health Connect  -> pasos, distancia, calorias, sueno y SpO2 si los trae.
     *   * Banda BLE       -> pasos, pulso y SpO2 en vivo; sueno si el reloj lo envia.
     *   * Simulado        -> SOLO rellena los campos que siguen a cero.
     * El origen resultante es "health_connect", "ble", "mixto" o "simulado".
     */
    private fun mezclarFuentes(
        base: DailyMetrics,
        real: DailyMetrics?,
        banda: DailyMetrics?
    ): DailyMetrics {
        val simulada = MetricsSimulator.today(_gender.value, prefs.stepGoal)
        var mezcla = base
        var huboReal = false
        var huboBanda = false

        // 1) Datos reales de Health Connect
        if (real != null) {
            huboReal = true
            mezcla = mezcla.copy(
                dateKey = real.dateKey,
                steps = maxOf(mezcla.steps, real.steps),
                distanceKm = maxOf(mezcla.distanceKm, real.distanceKm),
                caloriesKcal = maxOf(mezcla.caloriesKcal, real.caloriesKcal),
                activeMinutes = maxOf(mezcla.activeMinutes, real.activeMinutes),
                sleepHours = maxOf(mezcla.sleepHours, real.sleepHours),
                sleepQuality = maxOf(mezcla.sleepQuality, real.sleepQuality),
                restingHeartRate = if (real.restingHeartRate > 0) {
                    real.restingHeartRate
                } else {
                    mezcla.restingHeartRate
                },
                avgHeartRate = if (real.avgHeartRate > 0) real.avgHeartRate else mezcla.avgHeartRate,
                maxHeartRate = maxOf(mezcla.maxHeartRate, real.maxHeartRate),
                spo2 = if (real.spo2 > 0) real.spo2 else mezcla.spo2
            )
        }

        // 2) Datos de la banda BLE (K56 Pro y similares)
        if (banda != null) {
            huboBanda = true
            mezcla = mezcla.copy(
                steps = maxOf(mezcla.steps, banda.steps),
                distanceKm = if (banda.distanceKm > 0) {
                    maxOf(mezcla.distanceKm, banda.distanceKm)
                } else {
                    mezcla.distanceKm
                },
                caloriesKcal = maxOf(mezcla.caloriesKcal, banda.caloriesKcal),
                avgHeartRate = if (banda.avgHeartRate > 0) banda.avgHeartRate else mezcla.avgHeartRate,
                maxHeartRate = maxOf(mezcla.maxHeartRate, banda.maxHeartRate),
                spo2 = if (banda.spo2 > 0) banda.spo2 else mezcla.spo2,
                // El sueno MEDIDO por el reloj manda: con maxOf, un relleno
                // generado de 7,8 h tapaba las 6,2 h reales de la pulsera.
                sleepHours = if (banda.sleepHours > 0) banda.sleepHours else mezcla.sleepHours,
                sleepQuality = if (banda.sleepHours > 0) banda.sleepQuality else mezcla.sleepQuality
            )
        }

        // 3) Huecos: se rellenan con el dato generado, marcando "mixto"
        val antes = mezcla
        mezcla = mezcla.copy(
            stepGoal = prefs.stepGoal,
            distanceKm = if (mezcla.distanceKm <= 0.0) simulada.distanceKm else mezcla.distanceKm,
            caloriesKcal = if (mezcla.caloriesKcal <= 0) simulada.caloriesKcal else mezcla.caloriesKcal,
            activeMinutes = if (mezcla.activeMinutes <= 0) {
                simulada.activeMinutes
            } else {
                mezcla.activeMinutes
            },
            sleepHours = if (mezcla.sleepHours <= 0.0) simulada.sleepHours else mezcla.sleepHours,
            sleepQuality = if (mezcla.sleepQuality <= 0) {
                simulada.sleepQuality
            } else {
                mezcla.sleepQuality
            },
            restingHeartRate = if (mezcla.restingHeartRate <= 0) {
                simulada.restingHeartRate
            } else {
                mezcla.restingHeartRate
            },
            avgHeartRate = if (mezcla.avgHeartRate <= 0) simulada.avgHeartRate else mezcla.avgHeartRate,
            maxHeartRate = if (mezcla.maxHeartRate <= 0) simulada.maxHeartRate else mezcla.maxHeartRate,
            spo2 = if (mezcla.spo2 <= 0) simulada.spo2 else mezcla.spo2,
            stressLevel = if (mezcla.stressLevel <= 0) simulada.stressLevel else mezcla.stressLevel
        )
        val rellenoAplicado = mezcla != antes

        mezcla = mezcla.copy(
            source = when {
                huboReal && huboBanda -> "mixto"
                huboReal -> if (rellenoAplicado) "mixto" else "health_connect"
                huboBanda -> banda?.source ?: "ble"
                rellenoAplicado -> "simulado"
                else -> mezcla.source
            },
            updatedAt = System.currentTimeMillis()
        )
        return mezcla
    }

    /**
     * Rellena la pantalla con datos generados localmente cuando aun no hay banda
     * ni Health Connect, para que nunca se vea vacia. Ya NO es un interruptor
     * visible: en cuanto llegan datos reales, estos toman el control solos.
     */
    fun ensureVisibleData() {
        if (_today.value.steps > 0) return
        val fresh = MetricsSimulator.today(_gender.value, prefs.stepGoal)
        _today.value = fresh
        prefs.cachedToday = fresh
        _history.value = MetricsSimulator.history(7, _gender.value, prefs.stepGoal)
    }

    val cloudSyncEnabled: Boolean get() = prefs.cloudSyncEnabled

    fun setCloudSync(enabled: Boolean) {
        prefs.cloudSyncEnabled = enabled
        if (enabled) syncNow()
    }

    // ========================================================================
    //  SINCRONIZACION MANUAL
    // ========================================================================
    fun syncNow() {
        viewModelScope.launch {
            if (!repository.firebaseReady) {
                _syncMessage.value = txt(R.string.sync_no_cloud)
                delay(3_000)
                _syncMessage.value = ""
                return@launch
            }
            _syncMessage.value = txt(R.string.sync_uploading)
            repository.ensureSignedIn()
            val retried = repository.retryPending()
            val ok = repository.syncDay(_today.value)
            repository.syncHistory(_history.value)
            val pending = repository.pendingCount()
            _cloudSynced.value = ok && pending == 0
            _syncMessage.value = when {
                !prefs.cloudSyncEnabled -> txt(R.string.sync_disabled)
                pending > 0 -> "$pending elemento(s) en cola (sin red)"
                retried > 0 -> txt(R.string.sync_done_queue, retried)
                ok -> txt(R.string.sync_done)
                else -> txt(R.string.sync_later)
            }
            delay(2_500)
            _syncMessage.value = ""
        }
    }

    // ========================================================================
    //  ENTRENAMIENTO
    // ========================================================================
    fun startWorkout(type: String) {
        com.pandafit.app.ble.PandaFitBackgroundService.startWorkout(getApplication(), type)
    }

    fun stopWorkout() {
        com.pandafit.app.ble.PandaFitBackgroundService.stopWorkout(getApplication())
    }

    /** El servicio acaba de cerrar una sesion: la reflejamos en la nube. */
    fun onWorkoutFinished(session: WorkoutSession) {
        viewModelScope.launch { repository.syncWorkout(session) }
    }

    override fun onCleared() {
        super.onCleared()
        // El BleManager vive en la Application: NO se libera aqui o se perderia
        // la conexion al rotar la pantalla.
    }

    companion object {
        private const val TAG = "PandaFitVM"
    }
}
