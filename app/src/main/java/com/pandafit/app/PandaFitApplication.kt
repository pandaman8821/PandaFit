// ============================================================================
//  Panda Fit - PandaFitApplication.kt
//  Punto de entrada de la app. Aqui viven los objetos de larga vida:
//
//    * preferences : preferencias (la API Key de Gemini va CIFRADA aparte).
//    * repository  : Firebase Firestore + Auth anonimo (nube).
//    * bleManager  : conexion BLE compartida por toda la app (una sola GATT).
//    * gemini      : cliente unificado de Google Gemini.
//
//  Se inicializa Firebase de forma DEFENSIVA: si falta google-services.json o
//  el dispositivo no tiene Play Services, Panda Fit arranca igual (modo local +
//  datos simulados) en lugar de cerrarse.
// ============================================================================
package com.pandafit.app

import android.app.Application
import android.util.Log
import com.pandafit.app.ai.AiClientFactory
import com.pandafit.app.ble.BleManager
import com.pandafit.app.ble.PandaFitBackgroundService
import com.pandafit.app.data.PandaFitRepository
import com.pandafit.app.data.SharedPreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PandaFitApplication : Application() {

    /** Preferencias + API Key cifrada. */
    lateinit var preferences: SharedPreferencesHelper
        private set

    /** Nube: Firestore + Auth anonimo. */
    lateinit var repository: PandaFitRepository
        private set

    /** BLE compartido (una unica conexion GATT para toda la app). */
    lateinit var bleManager: BleManager
        private set

    /** Podometro del propio telefono (pasos reales sin pulsera). */
    lateinit var phoneSteps: com.pandafit.app.data.PhoneStepCounter
        private set

    /** Registro del dia (pasos por hora, pulso, presion, SpO2) para el panel "Hoy". */
    lateinit var dayLog: com.pandafit.app.data.DayLog
        private set

    /** Historial local de entrenamientos (ultimas 30 sesiones). */
    lateinit var workoutHistory: com.pandafit.app.data.WorkoutHistory
        private set

    /** v26: archivo de dias (resumen diario de los ultimos 400 dias). */
    lateinit var archivo: com.pandafit.app.data.ArchivoDiario
        private set

    /** v26: medallas conseguidas y retos semanales. */
    lateinit var logros: com.pandafit.app.data.Logros
        private set

    /**
     * v26: apunta en el archivo el resumen de HOY con lo medido (pulsera o
     * telefono). Lo llama el servicio cada minuto y la app al abrirse.
     * Los datos de ejemplo NUNCA se archivan.
     */
    fun archivarHoy() {
        runCatching {
            val hoy = java.time.LocalDate.now().toString()
            val log = dayLog.estado.value
            val cache = preferences.cachedToday
            val real = cache.source != "simulado" && cache.dateKey == hoy
            val pasos = maxOf(phoneSteps.pasosHoy.value, bleManager.stepsFromBand.value ?: 0, if (real) cache.steps else 0)
            val sueno = bleManager.sleepFromBand.value?.let {
                com.pandafit.app.data.SuenoDia(it.profundoMin, it.ligeroMin, it.despiertoMin, it.dormirMin, it.despertarMin)
            } ?: if (real && cache.sleepHours > 0) com.pandafit.app.data.SuenoDia(horasTotal = cache.sleepHours) else null
            // Primera vez: se rellenan los dias reales que ya habia en la cache de 7 dias.
            if (archivo.dias.value.isEmpty()) {
                preferences.cachedHistory.days.filter { it.source != "simulado" && it.dateKey != hoy }.forEach { d ->
                    archivo.actualizar(d.dateKey) {
                        it.copy(
                            pasos = d.steps, metaPasos = d.stepGoal, km = d.distanceKm, kcal = d.caloriesKcal,
                            pulsoMedio = d.avgHeartRate, pulsoMin = d.restingHeartRate, pulsoMax = d.maxHeartRate,
                            spo2 = d.spo2,
                            sueno = if (d.sleepHours > 0) com.pandafit.app.data.SuenoDia(horasTotal = d.sleepHours) else null
                        )
                    }
                }
            }
            val pulsos = log.pulsos.map { it.ppm }
            if (pasos <= 0 && pulsos.isEmpty() && sueno == null && log.presion == null && log.vasosAgua == 0) return
            archivo.actualizar(hoy) { d ->
                d.copy(
                    pasos = maxOf(d.pasos, pasos),
                    metaPasos = preferences.stepGoal,
                    // 0.1.7: distancia con la zancada segun TU estatura (antes 75 cm fijos).
                    km = maxOf(d.km, if (real) cache.distanceKm else com.pandafit.app.data.Cuerpo.kmDePasos(
                        pasos, preferences.userHeightCm, preferences.genderMode == com.pandafit.app.data.model.GenderMode.WOMAN
                    )),
                    kcal = maxOf(d.kcal, if (real) cache.caloriesKcal else (pasos * 0.046).toInt()),
                    pulsoMedio = if (pulsos.isNotEmpty()) pulsos.sum() / pulsos.size else d.pulsoMedio,
                    pulsoMin = pulsos.minOrNull() ?: d.pulsoMin,
                    pulsoMax = pulsos.maxOrNull() ?: d.pulsoMax,
                    presiones = log.presion?.let { p ->
                        val t = java.time.Instant.ofEpochMilli(p.en).atZone(java.time.ZoneId.systemDefault())
                        com.pandafit.app.data.ArchivoDiario.conPresion(
                            d.presiones,
                            com.pandafit.app.data.PresionDia(p.sistolica, p.diastolica, t.hour * 60 + t.minute)
                        )
                    } ?: d.presiones,
                    spo2 = log.spo2?.valor ?: d.spo2,
                    vasosAgua = log.vasosAgua,
                    sueno = sueno ?: d.sueno
                )
            }
            logros.revisar(this)
        }.onFailure { Log.w(TAG, "No se pudo archivar el dia: ${it.message}") }
    }

    /** v26: la IA conoce tambien el sueno de la semana, los retos y las medallas. */
    private fun contextoSemanaIa(): String = runCatching {
        buildString {
            appendLine()
            // 0.0.6: perfil (sin identificadores) y entrenos recientes.
            appendLine(
                "Perfil: ${preferences.genderMode.label.lowercase()}, ${preferences.userAge} años, " +
                    "${preferences.userWeightKg} kg, meta de agua ${preferences.waterGoalGlasses} vasos."
            )
            val sesiones = workoutHistory.sesiones.value
            if (sesiones.isNotEmpty()) {
                val zona = java.time.ZoneId.systemDefault()
                appendLine("Entrenos recientes (más nuevo primero):")
                sesiones.take(6).forEach { s ->
                    val f = java.time.Instant.ofEpochMilli(s.startedAt).atZone(zona).toLocalDate()
                    appendLine(
                        "- $f ${s.type}: ${s.durationSeconds / 60} min" +
                            (if (s.distanceKm > 0) ", ${"%.2f".format(s.distanceKm)} km" else "") +
                            ", ${s.caloriesKcal} kcal" +
                            (if (s.avgHeartRate > 0) ", pulso medio ${s.avgHeartRate}" else "")
                    )
                }
            } else appendLine("Entrenos recientes: ninguno guardado todavía.")
            com.pandafit.app.data.SuenoSemana.analizar(archivo.dias.value, preferences.sleepGoalHours)?.let { a ->
                appendLine(
                    "Sueño ultimos 7 dias: media ${com.pandafit.app.data.SuenoSemana.horas(a.mediaMin)}, " +
                        "se acuesta hacia las ${com.pandafit.app.data.SuenoSemana.hhmm(a.acostarseMin)}, " +
                        "regularidad ${if (a.regularidad < 0) "sin datos" else "${a.regularidad}/100"}, " +
                        "profundo ${a.profundoPct}%, ${a.nochesMeta} de ${a.noches.size} noches en su meta."
                )
            }
            val retos = logros.retosSemana(this@PandaFitApplication)
            if (retos.isNotEmpty()) {
                appendLine("Retos de esta semana: " + retos.joinToString("; ") { "${it.titulo} ${it.progreso}/${it.objetivo} ${it.unidad}" })
            }
            appendLine("Medallas conseguidas: ${logros.conseguidas.value.size} de ${com.pandafit.app.data.Logros.MEDALLAS.size}")
            // 0.0.3: el tiempo de su zona (SIN la ciudad: la ubicacion no sale hacia la IA).
            com.pandafit.app.data.Clima.guardado(this@PandaFitApplication)
                ?.takeIf { System.currentTimeMillis() - it.obtenidoEn < 3 * 3600_000L }
                ?.let { c ->
                    appendLine(
                        "Tiempo ahora en su zona: ${c.descripcion}, ${c.temp} °C (sensacion ${c.sensacion} °C), " +
                            "max ${c.maxima} / min ${c.minima}, viento ${c.vientoKmh} km/h, lluvia ${c.probLluvia} %, UV max ${c.uvMax}."
                    )
                }
        }
    }.getOrDefault("")

    /** Fabrica de clientes de IA: Gemini, OpenAI, DeepSeek o Claude. */
    lateinit var aiClients: AiClientFactory
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        preferences = SharedPreferencesHelper(this)
        bleManager = BleManager(this)
        phoneSteps = com.pandafit.app.data.PhoneStepCounter(this).also { it.iniciar() }
        dayLog = com.pandafit.app.data.DayLog(this)
        workoutHistory = com.pandafit.app.data.WorkoutHistory(this)
        archivo = com.pandafit.app.data.ArchivoDiario(this)
        logros = com.pandafit.app.data.Logros(this)
        // La IA recibe tambien las mediciones detalladas de hoy (anonimas).
        com.pandafit.app.ai.PromptBuilder.extrasProvider = {
            com.pandafit.app.ai.ContextoDetallado.resumen(
                dayLog.estado.value, bleManager.sleepFromBand.value
            ) + contextoSemanaIa()
        }
        // 0.0.6: lo que Panda recuerda del usuario (charlas anteriores).
        com.pandafit.app.ai.PromptBuilder.memoriaProvider = {
            com.pandafit.app.ai.MemoriaPanda.bloqueParaIa(this)
        }
        repository = PandaFitRepository(this, preferences)
        // Multi-proveedor (Gemini, OpenAI, DeepSeek, Claude): la clave se lee
        // CIFRADA en el momento de cada peticion y el modelo se guarda por
        // proveedor. La app no almacena claves de nadie mas.
        aiClients = AiClientFactory(
            apiKeyProvider = { proveedor -> preferences.getApiKey(proveedor) },
            modelProvider = { proveedor -> preferences.getModel(proveedor) }
        ).also { fabrica ->
            fabrica.modelSaver = { proveedor, modelo -> preferences.setModel(proveedor, modelo) }
        }

        initializeFirebaseQuietly()
        appScope.launch { archivarHoy() }

        // Arranca el servicio en segundo plano (banda + GPS + nube).
        runCatching { PandaFitBackgroundService.start(this) }
            .onFailure { Log.w(TAG, "Servicio en segundo plano no arrancado: ${it.message}") }

        Log.i(TAG, "Panda Fit listo. Nube=${repository.firebaseReady} HCKey=${if (preferences.healthConnectGranted) "si" else "no"}")
    }

    /**
     * Inicializa Firebase sin romper la app si falta configuracion.
     * Tambien entra en sesion ANONIMA (sin datos personales) para la nube.
     */
    private fun initializeFirebaseQuietly() {
        // 0.1.8: solo la version "completa" tiene Firebase; la de F-Droid no hace nada.
        com.pandafit.app.servicios.ServiciosSabor.iniciar(this)

        appScope.launch {
            runCatching { repository.ensureSignedIn() }
                .onFailure { Log.w(TAG, "Auth anonimo no disponible: ${it.message}") }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Liberamos el GATT solo si no hay entrenamiento en curso.
        if (PandaFitBackgroundService.activeWorkout.value == null) {
            bleManager.release()
        }
    }

    companion object {
        private const val TAG = "PandaFitApp"

        @Volatile
        private var instance: PandaFitApplication? = null

        /** Acceso rapido desde servicios y composables. */
        fun get(): PandaFitApplication = instance
            ?: throw IllegalStateException("PandaFitApplication no inicializada")
    }
}
