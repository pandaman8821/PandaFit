// ============================================================================
//  Panda Fit - AiModels.kt
//  Modelos compartidos por TODOS los clientes de IA (Gemini, OpenAI, DeepSeek y
//  Claude): errores tipados y el turno de conversacion.
//
//  Se mantienen en su propio archivo para que ningun proveedor dependa de otro.
// ============================================================================
package com.pandafit.app.ai

// ---------------------------------------------------------------------------
//  Errores tipados: la interfaz muestra mensajes claros en espanol y siempre
//  con una accion sugerida.
// ---------------------------------------------------------------------------
sealed class AiError(message: String) : Exception(message) {
    /** El usuario no ha guardado su clave del proveedor activo. */
    class NoApiKey : AiError("Falta tu clave del proveedor de IA")

    /** La clave no es valida o fue revocada. */
    class Unauthorized : AiError("La clave no es valida o fue revocada")

    /** El proveedor esta saturado (HTTP 429). */
    class RateLimited(val detalle: String = "") : AiError("Limite de uso (HTTP 429) $detalle")

    /** Sin conexion con el servicio. */
    class Network(detail: String) : AiError("Sin conexion con el proveedor ($detail)")

    /** Error del servidor (HTTP 4xx/5xx). */
    class Server(val code: Int, val detail: String) : AiError("El proveedor respondio $code ($detail)") {
        fun detalle404() = code == 404 || detail.contains("not found", true) || detail.contains("NOT_FOUND")
    }

    /** El modelo no devolvio texto. */
    class Empty : AiError("El proveedor no devolvio texto")

    /** Mensaje para el usuario, siempre con una accion sugerida. */
    val userMessage: String
        get() = when (this) {
            is NoApiKey ->
                "Abre Ajustes y pega tu clave del proveedor de IA (se guarda cifrada)."
            is Unauthorized ->
                "Tu clave no es valida o fue revocada. Compruebala en Ajustes."
            is RateLimited ->
                // 0.0.9: en la API gratuita de Gemini algunos modelos (como 2.5 Pro)
                // tienen cuota muy baja o cero: se explica en vez de "saturado".
                if (detalle.contains("free", true) || detalle.contains("quota", true) || detalle.contains("limit: 0"))
                    "Tu cuenta no tiene cuota para este modelo (en el plan gratuito puede no estar incluido o se agotó)."
                else "Llegaste al límite de uso del proveedor por ahora. Espera un poco o elige otro modelo."
            is Server ->
                if (detalle404()) "Ese modelo no está disponible en tu cuenta."
                else "El proveedor devolvió un error con este modelo."
            is Network ->
                "No hay conexion. Te respondo con el motor local de Panda Fit."
            is Empty ->
                "El proveedor no devolvio respuesta. Prueba a reformular la pregunta."
        }

    /** 0.1.7: el mismo mensaje en el idioma del telefono. */
    fun texto(c: android.content.Context): String = c.getString(
        when (this) {
            is NoApiKey -> com.pandafit.app.R.string.err_no_key
            is Unauthorized -> com.pandafit.app.R.string.err_bad_key
            is RateLimited ->
                if (detalle.contains("free", true) || detalle.contains("quota", true) || detalle.contains("limit: 0"))
                    com.pandafit.app.R.string.err_no_quota
                else com.pandafit.app.R.string.err_rate_limit
            is Server -> if (detalle404()) com.pandafit.app.R.string.err_model_unavailable else com.pandafit.app.R.string.err_server
            is Network -> com.pandafit.app.R.string.err_network
            is Empty -> com.pandafit.app.R.string.err_empty
        }
    )
}

/** Turno de conversacion (rol + texto). No contiene datos personales. */
data class GeminiTurn(val fromUser: Boolean, val text: String)
