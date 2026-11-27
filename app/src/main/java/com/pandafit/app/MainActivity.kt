// ============================================================================
//  Panda Fit - MainActivity.kt
//  Unica Activity de la app: dibuja la interfaz completa con Jetpack Compose.
//
//    * Edge-to-edge (barras del sistema transparentes) para que el cristal
//      llegue hasta los bordes, como en las apps premium de 2026.
//    * Tema reactivo: sigue al sistema (claro/oscuro automatico) y cambia al
//      instante cuando el usuario elige Claro/Oscuro en Perfil.
//    * Pide en orden los permisos que Panda Fit necesita:
//        1. NOTIFICACIONES (servicio en segundo plano visible)
//        2. BLUETOOTH_CONNECT / BLUETOOTH_SCAN (Android 12+) o ubicacion
//        3. ACTIVITY_RECOGNITION
//        4. Ubicacion precisa (GPS del entrenamiento)
//      Nada bloquea el arranque: si el usuario deniega, la app funciona con
//      datos simulados y Health Connect.
// ============================================================================
package com.pandafit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.pandafit.app.ble.PandaFitBackgroundService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandafit.app.ui.navigation.PandaFitNavHost
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.ui.theme.ThemeController

class MainActivity : ComponentActivity() {

    /**
     * Lanzador UNICO de permisos. Android solo atiende una peticion de permisos
     * a la vez: si se lanzan dos seguidas, la segunda se descarta sin dialogo.
     * Por eso todos los permisos de arranque van juntos en una sola peticion.
     *
     * Al volver del dialogo se (re)arranca el servicio en segundo plano: en el
     * primer arranque se intento antes de tener BLUETOOTH_CONNECT/ubicacion y
     * Android 14+ no le deja pasar a foreground sin ellos.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        runCatching { PandaFitBackgroundService.start(this) }
        // Con ACTIVITY_RECOGNITION recien concedido, arranca el podometro.
        (application as PandaFitApplication).phoneSteps.iniciar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()          // Cristal de borde a borde.
        if (savedInstanceState == null) requestStartupPermissions()

        // Estado inicial del tema desde las preferencias.
        val prefs = (application as PandaFitApplication).preferences
        ThemeController.update(prefs.darkThemeOverride, prefs.dynamicColorEnabled)

        setContent {
            val mode by ThemeController.mode.collectAsStateWithLifecycle()
            val dynamic by ThemeController.dynamicColor.collectAsStateWithLifecycle()

            PandaFitTheme(
                darkTheme = when (mode) {
                    0 -> false
                    1 -> true
                    else -> isSystemInDarkTheme()
                },
                dynamicColor = dynamic
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PandaFitNavHost()
                }
            }
        }
    }

    /** Pide los permisos necesarios en cuanto abre la app. */
    private fun requestStartupPermissions() {
        val toRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toRequest += Manifest.permission.BLUETOOTH_SCAN
            toRequest += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            toRequest += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            toRequest += Manifest.permission.ACTIVITY_RECOGNITION
        }

        toRequest += Manifest.permission.ACCESS_FINE_LOCATION
        toRequest += Manifest.permission.ACCESS_COARSE_LOCATION

        val missing = toRequest.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
