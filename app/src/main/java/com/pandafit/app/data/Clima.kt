// ============================================================================
//  Panda Fit - Clima.kt
//  0.0.3: CLIMA ACTUAL y CIUDAD para la pantalla de Inicio.
//    * Ubicacion APROXIMADA del telefono (precision de "ciudad", no de calle).
//    * Ciudad: el geocodificador del propio Android (sin servicios extra).
//    * Tiempo: Open-Meteo (gratis, sin clave). Solo se envian las coordenadas
//      REDONDEADAS a 2 decimales (~1 km); nada mas (ni nombre ni identificadores).
//    * Se guarda 30 min en el telefono para no pedirlo a cada rato.
//    * Consejo deportivo segun el tiempo (calor, lluvia, UV, viento, frio).
// ============================================================================
package com.pandafit.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.pandafit.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Tipo de cielo para elegir el dibujo del icono. */
enum class Cielo { DESPEJADO, POCO_NUBOSO, NUBLADO, NIEBLA, LLOVIZNA, LLUVIA, NIEVE, TORMENTA }

@Serializable
data class ClimaActual(
    val ciudad: String,
    val temp: Int,
    val sensacion: Int,
    val codigo: Int,
    val humedad: Int,
    val vientoKmh: Int,
    val maxima: Int,
    val minima: Int,
    val uvMax: Double,
    val probLluvia: Int,
    val esDeDia: Boolean,
    val obtenidoEn: Long = System.currentTimeMillis(),
    /** 0.1.6: donde se tomo (para saber si te has movido de ciudad). */
    val lat: Double? = null,
    val lon: Double? = null
) {
    val cielo: Cielo get() = Clima.cielo(codigo)
    val descripcion: String get() = Clima.descripcion(codigo, esDeDia)
}

object Clima {

    private const val PREFS = "panda_clima"
    private const val K_DATOS = "ultimo"
    private const val CADUCA_MS = 30 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    fun tienePermiso(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Lo ultimo guardado (aunque este caducado), para pintar algo al instante. */
    fun guardado(context: Context): ClimaActual? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_DATOS, null)
            ?.let { json.decodeFromString(ClimaActual.serializer(), it) }
    }.getOrNull()

    /**
     * Devuelve el clima actual. Usa lo guardado si tiene menos de 30 min
     * (salvo [forzar]). Lanza una excepcion con mensaje claro si no se puede.
     */
    suspend fun obtener(context: Context, forzar: Boolean = false): ClimaActual = withContext(Dispatchers.IO) {
        val previo = guardado(context)
        if (!tienePermiso(context)) throw ClimaError(R.string.wx_err_permission)
        // 0.1.6: SIEMPRE se mira donde estas ahora (con una ubicacion reciente).
        // Antes se devolvia el clima guardado 30 min sin mirar si te habias movido,
        // y si no habia ubicacion fresca se usaba la ultima conocida (dias atras).
        val (lat, lon) = ubicacion(context)
            ?: throw ClimaError(R.string.wx_err_location)
        val mismoSitio = previo?.lat != null && previo.lon != null &&
            distanciaKm(previo.lat, previo.lon, lat, lon) < RADIO_MISMO_SITIO_KM
        if (!forzar && previo != null && mismoSitio && System.currentTimeMillis() - previo.obtenidoEn < CADUCA_MS) {
            return@withContext previo
        }
        // El nombre anterior solo se reutiliza si sigues en el mismo sitio
        // (antes se quedaba la ciudad anterior aunque estuvieras en otra ciudad).
        val ciudad = ciudad(context, lat, lon) ?: (if (mismoSitio) previo?.ciudad else null) ?: "Tu zona"
        val nuevo = descargar(lat, lon, ciudad).copy(lat = lat, lon = lon)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_DATOS, json.encodeToString(ClimaActual.serializer(), nuevo)).apply()
        nuevo
    }

    /** A menos de esta distancia se considera "el mismo sitio" (misma ciudad). */
    const val RADIO_MISMO_SITIO_KM = 3.0

    /** Distancia entre dos puntos (haversine), en km. Funcion pura. */
    fun distanciaKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    /**
     * Ubicacion ACTUAL (aproximada). 0.1.6: se pide una ubicacion nueva o de hace
     * menos de 2 min; si no llega, se intenta con mas precision; la "ultima
     * conocida" solo vale si tiene menos de 15 min (antes valia aunque fuera de
     * hace dias, y por eso el clima seguia en la ciudad anterior).
     */
    // 0.1.8: la ubicacion la da cada version (Google en "completa", Android en "fdroid").
    private suspend fun ubicacion(context: Context): Pair<Double, Double>? {
        val loc = com.pandafit.app.servicios.ServiciosSabor.ubicacion(context).actual() ?: return null
        return loc.latitude to loc.longitude
    }

    @Suppress("DEPRECATION")
    private suspend fun ciudad(context: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val geo = Geocoder(context, Locale.getDefault())
        val direcciones = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(6_000) {
                suspendCancellableCoroutine { cont ->
                    geo.getFromLocation(lat, lon, 3, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) { cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { cont.resume(emptyList()) }
                    })
                }
            }
        } else geo.getFromLocation(lat, lon, 3)
        // 0.1.6: se busca el primer nombre de ciudad/municipio que no este vacio.
        val lista = direcciones.orEmpty()
        lista.firstNotNullOfOrNull { it.locality?.takeIf(String::isNotBlank) }
            ?: lista.firstNotNullOfOrNull { it.subAdminArea?.takeIf(String::isNotBlank) }
            ?: lista.firstNotNullOfOrNull { it.adminArea?.takeIf(String::isNotBlank) }
            ?: lista.firstNotNullOfOrNull { it.countryName?.takeIf(String::isNotBlank) }
    }.getOrNull()

    private fun descargar(lat: Double, lon: Double, ciudad: String): ClimaActual {
        // Privacidad: coordenadas redondeadas a ~1 km.
        val la = "%.2f".format(Locale.US, lat)
        val lo = "%.2f".format(Locale.US, lon)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,uv_index_max,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
        )
        val con = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "PandaFit-Android")
        }
        try {
            if (con.responseCode != 200) throw ClimaError(R.string.wx_err_service, con.responseCode)
            val texto = con.inputStream.bufferedReader().use { it.readText() }
            return interpretar(texto, ciudad)
        } finally {
            con.disconnect()
        }
    }

    /** Convierte la respuesta de Open-Meteo. Funcion pura (se prueba sin red). */
    fun interpretar(texto: String, ciudad: String, ahora: Long = System.currentTimeMillis()): ClimaActual {
        val raiz = Json.parseToJsonElement(texto).jsonObject
        val c = raiz["current"]!!.jsonObject
        val d = raiz["daily"]?.jsonObject
        fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.double ?: 0.0
        fun primero(k: String) = d?.get(k)?.jsonArray?.firstOrNull()?.jsonPrimitive?.let { runCatching { it.double }.getOrNull() } ?: 0.0
        return ClimaActual(
            ciudad = ciudad,
            temp = c.num("temperature_2m").roundToInt(),
            sensacion = c.num("apparent_temperature").roundToInt(),
            codigo = c["weather_code"]?.jsonPrimitive?.int ?: 0,
            humedad = c.num("relative_humidity_2m").roundToInt(),
            vientoKmh = c.num("wind_speed_10m").roundToInt(),
            maxima = primero("temperature_2m_max").roundToInt(),
            minima = primero("temperature_2m_min").roundToInt(),
            uvMax = primero("uv_index_max"),
            probLluvia = primero("precipitation_probability_max").roundToInt(),
            esDeDia = (c["is_day"]?.jsonPrimitive?.int ?: 1) == 1,
            obtenidoEn = ahora
        )
    }

    /** Codigos WMO de Open-Meteo -> tipo de cielo. */
    fun cielo(codigo: Int): Cielo = when (codigo) {
        0 -> Cielo.DESPEJADO
        1, 2 -> Cielo.POCO_NUBOSO
        3 -> Cielo.NUBLADO
        45, 48 -> Cielo.NIEBLA
        51, 53, 55, 56, 57 -> Cielo.LLOVIZNA
        61, 63, 65, 66, 67, 80, 81, 82 -> Cielo.LLUVIA
        71, 73, 75, 77, 85, 86 -> Cielo.NIEVE
        95, 96, 99 -> Cielo.TORMENTA
        else -> Cielo.NUBLADO
    }

    fun descripcion(codigo: Int, dia: Boolean): String = when (codigo) {
        0 -> if (dia) "Despejado" else "Noche despejada"
        1 -> "Casi despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        51, 53, 55 -> "Llovizna"
        56, 57 -> "Llovizna helada"
        61 -> "Lluvia débil"
        63 -> "Lluvia"
        65 -> "Lluvia fuerte"
        66, 67 -> "Lluvia helada"
        71, 73, 75, 77 -> "Nieve"
        80, 81, 82 -> "Chubascos"
        85, 86 -> "Chubascos de nieve"
        95 -> "Tormenta"
        96, 99 -> "Tormenta con granizo"
        else -> "Variable"
    }

    /** Consejo para entrenar hoy segun el tiempo. Funcion pura. */
    /** 0.1.7: descripcion traducible para la pantalla (la de texto queda para la IA). */
    fun descripcionRes(codigo: Int, dia: Boolean): Int = when (codigo) {
        0 -> if (dia) R.string.wx_clear else R.string.wx_clear_night
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        51, 53, 55 -> R.string.wx_drizzle
        56, 57 -> R.string.wx_freezing_drizzle
        61 -> R.string.wx_light_rain
        63 -> R.string.wx_rain
        65 -> R.string.wx_heavy_rain
        66, 67 -> R.string.wx_freezing_rain
        71, 73, 75, 77 -> R.string.wx_snow
        80, 81, 82 -> R.string.wx_showers
        85, 86 -> R.string.wx_snow_showers
        95 -> R.string.wx_storm
        96, 99 -> R.string.wx_hail_storm
        else -> R.string.wx_variable
    }

    /** 0.1.7: consejo traducible: (texto, numero que va dentro o null). */
    fun consejoRes(c: ClimaActual): Pair<Int, Int?> = when {
        c.cielo == Cielo.TORMENTA -> R.string.wx_tip_storm to null
        c.cielo == Cielo.LLUVIA || c.cielo == Cielo.NIEVE -> R.string.wx_tip_bad to null
        c.sensacion >= 32 -> R.string.wx_tip_very_hot to null
        c.sensacion >= 27 -> R.string.wx_tip_hot to null
        c.uvMax >= 8 && c.esDeDia -> R.string.wx_tip_uv to c.uvMax.roundToInt()
        c.vientoKmh >= 35 -> R.string.wx_tip_wind to null
        c.sensacion <= 3 -> R.string.wx_tip_cold to null
        c.probLluvia >= 60 -> R.string.wx_tip_rain_chance to c.probLluvia
        c.cielo == Cielo.NIEBLA -> R.string.wx_tip_fog to null
        else -> R.string.wx_tip_good to null
    }

    /** 0.1.7: errores con texto traducible. */
    class ClimaError(val res: Int, val codigo: Int = 0) : Exception("clima $res $codigo")

    fun consejo(c: ClimaActual): String = when {
        c.cielo == Cielo.TORMENTA -> "Hay tormenta: hoy mejor entrena en casa."
        c.cielo == Cielo.LLUVIA || c.cielo == Cielo.NIEVE -> "Mal tiempo fuera: buen día para fuerza o yoga en casa."
        c.sensacion >= 32 -> "Hace mucho calor: entrena temprano o al atardecer y bebe agua a menudo."
        c.sensacion >= 27 -> "Hace calor: lleva agua y ve a un ritmo cómodo."
        c.uvMax >= 8 && c.esDeDia -> "Sol muy fuerte (UV ${c.uvMax.roundToInt()}): protector solar y gorra si sales."
        c.vientoKmh >= 35 -> "Mucho viento: si sales, empieza con el viento de cara."
        c.sensacion <= 3 -> "Hace frío: abrígate por capas y calienta bien antes de correr."
        c.probLluvia >= 60 -> "Puede llover (${c.probLluvia} %): si sales, que sea pronto."
        c.cielo == Cielo.NIEBLA -> "Hay niebla: si sales, ropa con colores visibles."
        else -> "Buen tiempo para moverse: ¡aprovecha y sal a caminar o correr!"
    }
}
