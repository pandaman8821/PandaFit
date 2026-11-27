// ============================================================================
//  Panda Fit - Servicios.kt (comun a las dos versiones)
//  0.1.8: la app tiene DOS versiones (sabores de Gradle):
//    * "completa": con Firebase (nube) y la ubicacion de Google Play Services.
//    * "fdroid":   100 % software libre para F-Droid: sin nube y con la
//                  ubicacion propia de Android (LocationManager).
//  Aqui solo estan las interfaces y la version libre de la ubicacion; cada
//  sabor crea las suyas en src/<sabor>/java/.../servicios/ServiciosSabor.kt.
// ============================================================================
package com.pandafit.app.servicios

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Nube remota (Firestore en la version completa; nada en la de F-Droid). */
interface NubeRemota {
    /** true si hay un proyecto de nube configurado. */
    val lista: Boolean

    /** Inicia sesion anonima (o confirma la existente). */
    suspend fun iniciarSesion(): Boolean

    /** Guarda [datos] en la ruta coleccion/documento/coleccion/documento... */
    suspend fun guardar(ruta: List<String>, datos: Map<String, Any>, fusionar: Boolean): Boolean

    /** Ultimos [limite] documentos de la coleccion [ruta], del mas nuevo al mas viejo. */
    suspend fun leer(ruta: List<String>, ordenarPor: String, limite: Long): List<Map<String, Any?>>?

    fun borrar(ruta: List<String>)
}

/** Sin nube (version F-Droid o sin google-services.json). */
object NubeApagada : NubeRemota {
    override val lista = false
    override suspend fun iniciarSesion() = false
    override suspend fun guardar(ruta: List<String>, datos: Map<String, Any>, fusionar: Boolean) = false
    override suspend fun leer(ruta: List<String>, ordenarPor: String, limite: Long): List<Map<String, Any?>>? = null
    override fun borrar(ruta: List<String>) {}
}

/** Ubicacion: una vez (clima) o continua (trayecto del entreno). */
interface FuenteUbicacion {
    /** Ubicacion actual o muy reciente; null si no llega a tiempo. */
    suspend fun actual(): Location?

    fun empezarSeguimiento(alRecibir: (Location) -> Unit)
    fun pararSeguimiento()
}

/** Ubicacion con el propio Android (sin Google Play Services). */
@SuppressLint("MissingPermission")
class UbicacionAndroid(context: Context) : FuenteUbicacion {

    private val lm = context.applicationContext.getSystemService(LocationManager::class.java)
    private val hilo = Executors.newSingleThreadExecutor()
    private var oyente: LocationListener? = null

    private fun proveedores(): List<String> = listOfNotNull(
        LocationManager.NETWORK_PROVIDER.takeIf { runCatching { lm?.isProviderEnabled(it) }.getOrNull() == true },
        LocationManager.GPS_PROVIDER.takeIf { runCatching { lm?.isProviderEnabled(it) }.getOrNull() == true },
        LocationManager.FUSED_PROVIDER.takeIf {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { lm?.isProviderEnabled(it) }.getOrNull() == true
        }
    )

    override suspend fun actual(): Location? {
        val m = lm ?: return null
        // 1) Ultima conocida si es de hace menos de 2 min.
        val reciente = proveedores().mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (reciente != null && System.currentTimeMillis() - reciente.time < 2 * 60_000L) return reciente
        // 2) Una ubicacion nueva de cada proveedor activo (la primera que llegue).
        for (p in proveedores()) {
            val nueva = withTimeoutOrNull(if (p == LocationManager.GPS_PROVIDER) 14_000L else 10_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancelar = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancelar.cancel() }
                    runCatching {
                        LocationManagerCompat.getCurrentLocation(m, p, cancelar, hilo) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (nueva != null) return nueva
        }
        // 3) La ultima conocida solo si tiene menos de 15 min.
        return reciente?.takeIf { System.currentTimeMillis() - it.time < 15 * 60_000L }
    }

    override fun empezarSeguimiento(alRecibir: (Location) -> Unit) {
        val m = lm ?: return
        pararSeguimiento()
        val l = LocationListener { alRecibir(it) }
        oyente = l
        val gps = runCatching { m.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val proveedor = if (gps) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        runCatching { m.requestLocationUpdates(proveedor, 2_000L, 1.5f, l, Looper.getMainLooper()) }
    }

    override fun pararSeguimiento() {
        oyente?.let { l -> runCatching { lm?.removeUpdates(l) } }
        oyente = null
    }
}
