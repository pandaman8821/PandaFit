// ============================================================================
//  Panda Fit - Red.kt
//  0.0.7: explica en espanol claro POR QUE no hay conexion con la IA/la voz:
//    * el telefono no tiene internet, o
//    * el telefono SI tiene internet pero Panda Fit no puede usarlo (datos de
//      la app bloqueados, ahorro de datos, DNS privado o bloqueador de anuncios
//      que corta "googleapis.com"/"openai.com"), o
//    * el servicio respondio con un error (clave, cuota...).
//  Antes salia el texto tecnico en ingles ("Unable to resolve host...").
// ============================================================================
package com.pandafit.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object Red {

    enum class Fallo { SIN_INTERNET, APP_BLOQUEADA, LENTO, CUOTA, SERVICIO }

    /** true si el telefono tiene una red con internet comprobado. */
    fun telefonoConInternet(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /** Clasifica el error. Funcion pura (salvo [telefonoOnline]). */
    fun clasificar(error: Throwable?, telefonoOnline: Boolean): Fallo {
        val causa = generateSequence(error) { it.cause }.toList()
        val texto = causa.joinToString(" ") { it.message.orEmpty() }
        val esRed = causa.any { it is UnknownHostException || it is ConnectException } ||
            texto.contains("resolve host", true) || texto.contains("No address associated", true) ||
            texto.contains("Sin conexion", true) || texto.contains("Failed to connect", true)
        return when {
            // 0.0.8: limite de uso de la cuenta (la voz gratuita de Gemini tiene muy pocas al dia).
            texto.contains("HTTP 429") || texto.contains("RESOURCE_EXHAUSTED", true) ||
                texto.contains("quota", true) || texto.contains("rate limit", true) -> Fallo.CUOTA
            causa.any { it is SocketTimeoutException } || texto.contains("timeout", true) -> Fallo.LENTO
            causa.any { it is SSLException } -> if (telefonoOnline) Fallo.APP_BLOQUEADA else Fallo.SIN_INTERNET
            esRed && telefonoOnline -> Fallo.APP_BLOQUEADA
            esRed -> Fallo.SIN_INTERNET
            else -> Fallo.SERVICIO
        }
    }

    fun clasificar(context: Context, error: Throwable?): Fallo = clasificar(error, telefonoConInternet(context))

    /** Mensaje corto y claro para el chat (sin jerga tecnica). */
    fun mensaje(f: Fallo, que: String): String = when (f) {
        Fallo.SIN_INTERNET -> "Sin internet ahora mismo: $que. Cuando vuelva la conexión lo uso otra vez."
        Fallo.APP_BLOQUEADA ->
            "Tu teléfono tiene internet, pero Panda Fit no puede usarlo: $que. Revisa en Ajustes > Apps > " +
                "Panda Fit > Datos móviles / Wi‑Fi que tenga acceso a internet, y que el ahorro de datos, " +
                "una VPN, un bloqueador de anuncios o el DNS privado no bloqueen Google."
        Fallo.LENTO -> "La conexión va muy lenta: $que. Prueba de nuevo en un momento."
        Fallo.CUOTA ->
            "Tu cuenta de IA llegó a su límite de uso por ahora (la voz gratuita de Gemini permite pocas lecturas al día): $que. " +
                "Se restablece solo; si quieres, desactiva la voz natural en los ajustes del chat."
        Fallo.SERVICIO -> "El servicio de IA no respondió bien: $que."
    }

    /** 0.1.7: el mismo mensaje en el idioma del telefono ([queRes] = que se hace mientras). */
    fun mensaje(c: Context, f: Fallo, queRes: Int): String {
        val que = c.getString(queRes)
        return c.getString(
            when (f) {
                Fallo.SIN_INTERNET -> com.pandafit.app.R.string.net_offline
                Fallo.APP_BLOQUEADA -> com.pandafit.app.R.string.net_blocked
                Fallo.LENTO -> com.pandafit.app.R.string.net_slow
                Fallo.CUOTA -> com.pandafit.app.R.string.net_quota
                Fallo.SERVICIO -> com.pandafit.app.R.string.net_service
            },
            que
        )
    }
}
