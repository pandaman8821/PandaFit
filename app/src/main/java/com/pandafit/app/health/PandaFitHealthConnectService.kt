// ============================================================================
//  Panda Fit - PandaFitHealthConnectService.kt
//  Servicio declarado en el manifiesto para que Health Connect sepa que Panda
//  Fit es un cliente de salud legitimo. Se usa para recibir el aviso de
//  "reintenta la lectura de datos" despues de un reinicio o de una migracion.
//
//  Es un stub seguro: no hace nada por si mismo, solo cumple el contrato.
// ============================================================================
package com.pandafit.app.health

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.pandafit.app.PandaFitApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PandaFitHealthConnectService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tras un reinicio Health Connect pide a las apps que vuelvan a leer.
        scope.launch {
            runCatching {
                val app = application as? PandaFitApplication ?: return@launch
                val helper = HealthConnectHelper(this@PandaFitHealthConnectService)
                if (!helper.isAvailable()) return@launch
                if (!helper.hasAllReadPermissions()) return@launch
                val fresh = helper.readToday(stepGoal = app.preferences.stepGoal)
                if (fresh != null) {
                    app.preferences.cachedToday = fresh
                    app.preferences.healthConnectGranted = true
                    app.repository.syncDay(fresh)
                }
            }.onFailure { Log.w(TAG, "Relectura de Health Connect fallida: ${it.message}") }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PandaFitHCService"
    }
}
