// ============================================================================
//  Panda Fit - PhoneStepCounter.kt
//  PODOMETRO DEL TELEFONO (Sensor.TYPE_STEP_COUNTER).
//
//  Sin pulsera ni Health Connect, todas las metricas eran cifras de ejemplo y
//  no avanzaban al caminar. Este contador usa el sensor de pasos de hardware
//  del propio movil (lo tienen casi todos desde Android 4.4), que:
//    * gasta muy poca bateria (lo cuenta un chip aparte),
//    * sigue contando con la app cerrada (el valor es acumulado desde que se
//      encendio el telefono, asi que al volver se recupera la diferencia).
//
//  Pasos de HOY = suma de incrementos del contador desde medianoche. Se guarda
//  en preferencias para sobrevivir a cierres de la app y reinicios del movil.
//  Necesita el permiso ACTIVITY_RECOGNITION (Android 10+), que MainActivity
//  ya pide al arrancar.
// ============================================================================
package com.pandafit.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pandafit.app.data.model.toDateKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

class PhoneStepCounter(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _pasosHoy = MutableStateFlow(pasosGuardadosDeHoy())
    /** Pasos de hoy contados por el telefono (0 si no hay sensor o permiso). */
    val pasosHoy: StateFlow<Int> = _pasosHoy.asStateFlow()

    private var registrado = false

    /** true si el telefono tiene sensor de pasos de hardware. */
    val disponible: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    private fun tienePermiso(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

    /** Empieza a escuchar el sensor (idempotente; sin permiso no hace nada). */
    fun iniciar() {
        if (registrado || !tienePermiso()) return
        val manager = sensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: run {
            Log.i(TAG, "Este telefono no tiene sensor de pasos")
            return
        }
        registrado = runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        Log.i(TAG, "Podometro del telefono: ${if (registrado) "activo" else "no disponible"}")
    }

    fun detener() {
        if (!registrado) return
        runCatching { sensorManager?.unregisterListener(this) }
        registrado = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val contador = event.values.firstOrNull()?.toLong() ?: return
        _pasosHoy.value = registrarLectura(contador)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Convierte una lectura ACUMULADA del sensor en pasos de hoy.
     * - Primera lectura de la historia: solo fija la referencia (no se pueden
     *   saber los pasos anteriores a instalar la app).
     * - Contador menor que el anterior: el movil se reinicio y el sensor
     *   volvio a 0; lo leido ahora son pasos nuevos.
     * - Dia nuevo: se empieza de 0.
     */
    @Synchronized
    private fun registrarLectura(contador: Long): Int {
        val hoy = LocalDate.now().toDateKey()
        val ultimo = prefs.getLong(K_ULTIMO, -1L)
        var pasos = if (prefs.getString(K_FECHA, null) == hoy) prefs.getInt(K_PASOS, 0) else 0
        val incremento = calcularIncremento(ultimo, contador)
        pasos += incremento
        prefs.edit()
            .putString(K_FECHA, hoy)
            .putInt(K_PASOS, pasos)
            .putLong(K_ULTIMO, contador)
            .apply()
        return pasos
    }

    private fun pasosGuardadosDeHoy(): Int =
        if (prefs.getString(K_FECHA, null) == LocalDate.now().toDateKey()) {
            prefs.getInt(K_PASOS, 0)
        } else 0

    /** Al cambiar de dia, refresca el valor publicado (vuelve a 0). */
    fun refrescarDia() {
        _pasosHoy.value = pasosGuardadosDeHoy()
    }

    companion object {
        private const val TAG = "PandaFitPasos"
        private const val PREFS = "panda_podometro"
        private const val K_FECHA = "fecha"
        private const val K_PASOS = "pasos_hoy"
        private const val K_ULTIMO = "ultimo_contador"

        /** Limite de seguridad para un solo salto (evita lecturas absurdas). */
        private const val SALTO_MAXIMO = 60_000L

        /**
         * Pasos nuevos entre dos lecturas acumuladas del sensor. Funcion pura
         * para poder probarla sin telefono.
         */
        fun calcularIncremento(anterior: Long, actual: Long): Int = when {
            actual < 0 -> 0
            anterior < 0 -> 0                                   // primera lectura
            actual >= anterior -> (actual - anterior).coerceAtMost(SALTO_MAXIMO).toInt()
            else -> actual.coerceAtMost(SALTO_MAXIMO).toInt()   // reinicio del movil
        }
    }
}
