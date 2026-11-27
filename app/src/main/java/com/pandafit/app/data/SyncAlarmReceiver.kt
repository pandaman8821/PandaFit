// ============================================================================
//  Panda Fit - SyncAlarmReceiver.kt
//  Receptor de ARRANQUE DEL SISTEMA: reintenta la sincronizacion pendiente.
//
//    * BOOT_COMPLETED / LOCKED_BOOT_COMPLETED -> retryPending(): sube el dia de
//      hoy, los entrenamientos, los planes y el resumen de chat que quedaron en
//      la cola local (PendingSyncStore) mientras no habia red.
//
//  NOTA: la conectividad en vivo NO se escucha aqui. Desde Android 8/15 los
//  broadcasts implicitos de CONNECTIVITY_CHANGE ya no llegan a los receptores
//  declarados en el manifiesto; el ViewModel la observa en vivo con
//  ConnectivityManager.NetworkCallback (ver FirebaseHealthViewModel).
// ============================================================================
package com.pandafit.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pandafit.app.PandaFitApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val app = context.applicationContext as? PandaFitApplication ?: return
        if (!app.preferences.cloudSyncEnabled) return

        Log.i(TAG, "Arranque del sistema: reintentando sincronizacion pendiente")
        scope.launch {
            runCatching {
                app.repository.ensureSignedIn()
                val uploaded = app.repository.retryPending()
                Log.i(TAG, "Cola tras el arranque: $uploaded elemento(s) subidos")
            }.onFailure { Log.w(TAG, "Reintento tras arranque fallido: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "PandaFitSync"
    }
}
