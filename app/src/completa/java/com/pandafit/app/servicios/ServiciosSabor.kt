// ============================================================================
//  Panda Fit - ServiciosSabor.kt (version COMPLETA)
//  Firebase (Auth anonimo + Firestore + Crashlytics) y la ubicacion de Google
//  Play Services. La version "fdroid" tiene su propio archivo sin nada de esto.
// ============================================================================
package com.pandafit.app.servicios

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.pandafit.app.BuildConfig
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object ServiciosSabor {

    const val LIBRE = false

    /** Arranca Firebase si hay google-services.json (si no, no hace nada). */
    fun iniciar(app: Application) {
        runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app)
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        }.onFailure { Log.i("PandaFit", "Firebase no configurado: modo local (${it.message})") }
    }

    fun nube(context: Context): NubeRemota =
        if (runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)) NubeFirebase()
        else NubeApagada

    fun ubicacion(context: Context): FuenteUbicacion = UbicacionGoogle(context)
}

private class NubeFirebase : NubeRemota {

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val db: FirebaseFirestore? by lazy {
        runCatching {
            FirebaseFirestore.getInstance().apply {
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build()
            }
        }.getOrNull()
    }

    override val lista = true

    override suspend fun iniciarSesion(): Boolean {
        val a = auth ?: return false
        return runCatching {
            a.currentUser != null || withTimeoutOrNull(8_000) { a.signInAnonymously().await() }?.user != null
        }.getOrElse { Log.w(TAG, "Auth anonimo no disponible: ${it.message}"); false }
    }

    private fun documento(ruta: List<String>): DocumentReference? {
        val d = db ?: return null
        require(ruta.size % 2 == 0) { "ruta de documento con numero par de partes" }
        var ref = d.collection(ruta[0]).document(ruta[1])
        var i = 2
        while (i < ruta.size) { ref = ref.collection(ruta[i]).document(ruta[i + 1]); i += 2 }
        return ref
    }

    override suspend fun guardar(ruta: List<String>, datos: Map<String, Any>, fusionar: Boolean): Boolean {
        val doc = documento(ruta) ?: return false
        return runCatching {
            withTimeoutOrNull(12_000) {
                if (fusionar) doc.set(datos, SetOptions.merge()).await() else doc.set(datos).await()
            } != null
        }.getOrElse { Log.w(TAG, "Guardado en la nube fallido/encolado: ${it.message}"); false }
    }

    override suspend fun leer(ruta: List<String>, ordenarPor: String, limite: Long): List<Map<String, Any?>>? {
        val d = db ?: return null
        val coleccion = if (ruta.size == 1) d.collection(ruta[0])
        else documento(ruta.dropLast(1))?.collection(ruta.last()) ?: return null
        return runCatching {
            withTimeoutOrNull(12_000) {
                coleccion.orderBy(ordenarPor, Query.Direction.DESCENDING).limit(limite).get().await()
            }?.documents?.map { doc -> doc.data.orEmpty() + ("__id" to doc.id) }
        }.getOrElse { Log.w(TAG, "Lectura de la nube fallida: ${it.message}"); null }
    }

    override fun borrar(ruta: List<String>) {
        runCatching { documento(ruta)?.delete() }
    }

    companion object { private const val TAG = "PandaFitCloud" }
}

@SuppressLint("MissingPermission")
private class UbicacionGoogle(context: Context) : FuenteUbicacion {

    private val fused = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private var callback: LocationCallback? = null

    override suspend fun actual(): Location? {
        suspend fun pedir(prioridad: Int, ms: Long) = withTimeoutOrNull(ms + 2_000) {
            runCatching {
                fused.getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(prioridad)
                        .setMaxUpdateAgeMillis(2 * 60_000L)
                        .setDurationMillis(ms)
                        .build(),
                    null
                ).await()
            }.getOrNull()
        }
        return pedir(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000)
            ?: pedir(Priority.PRIORITY_HIGH_ACCURACY, 12_000)
            ?: runCatching { fused.lastLocation.await() }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - it.time < 15 * 60_000L }
    }

    override fun empezarSeguimiento(alRecibir: (Location) -> Unit) {
        pararSeguimiento()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(1.5f)
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let(alRecibir) }
        }
        callback = cb
        runCatching { fused.requestLocationUpdates(request, cb, Looper.getMainLooper()) }
            .onFailure { Log.w("PandaFitGps", "GPS no disponible: ${it.message}") }
    }

    override fun pararSeguimiento() {
        callback?.let { runCatching { fused.removeLocationUpdates(it) } }
        callback = null
    }
}
