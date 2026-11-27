// ============================================================================
//  Panda Fit - MemoriaPanda.kt
//  0.0.6: MEMORIA del asistente (lo que lo hace mas "humano"):
//    * RECUERDOS: cosas duraderas que el usuario cuenta (una lesion, su
//      objetivo, que entrena por la tarde, que no le gusta correr...). La IA
//      las marca al final de su respuesta con [RECUERDA: ...]; la app las quita
//      del texto, las guarda en el telefono (max. 25) y se las vuelve a dar en
//      las siguientes charlas. Se pueden borrar desde los ajustes del chat.
//    * CHAT: los ultimos 40 mensajes se conservan al cerrar la app.
//  Antes de guardar, los recuerdos pasan por el filtro de datos personales.
// ============================================================================
package com.pandafit.app.ai

import android.content.Context
import com.pandafit.app.data.model.ChatMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object MemoriaPanda {

    private const val PREFS = "panda_memoria"
    private const val MAX_RECUERDOS = 25
    private const val MAX_CHAT = 40
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listaTextos = ListSerializer(String.serializer())
    private val listaChat = ListSerializer(ChatMessage.serializer())

    private val ETIQUETA = Regex("\\[\\s*RECUERDA\\s*:([^\\]]*)\\]", RegexOption.IGNORE_CASE)
    /** Etiqueta a medio escribir mientras llega el texto en streaming. */
    private val ETIQUETA_ABIERTA = Regex("\\[\\s*R(?:E(?:C(?:U(?:E(?:R(?:D(?:A[^\\]]*)?)?)?)?)?)?)?\\s*$", RegexOption.IGNORE_CASE)

    /** Separa el texto visible de los recuerdos marcados. Funcion pura. */
    fun extraer(texto: String): Pair<String, List<String>> {
        val recuerdos = ETIQUETA.findAll(texto).map { it.groupValues[1].trim() }.filter { it.length in 3..200 }.toList()
        return sinEtiquetas(texto) to recuerdos
    }

    /** Quita las etiquetas (completas o a medio llegar). Funcion pura. */
    fun sinEtiquetas(texto: String): String =
        sinNull(texto).replace(ETIQUETA, "").replace(ETIQUETA_ABIERTA, "").replace(Regex("\\n{3,}"), "\n\n").trimEnd()

    /**
     * 0.1.5: quita el "nullnullnull..." que dejaban los trozos de razonamiento de
     * DeepSeek V4 (llegaban con el texto vacio y se escribia la palabra "null").
     */
    fun sinNull(texto: String): String =
        texto.replace(Regex("^(?:null)+"), "").replace(Regex("(?:null){2,}"), "")

    fun recuerdos(context: Context): List<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recuerdos", null)
            ?.let { json.decodeFromString(listaTextos, it) }
    }.getOrNull().orEmpty()

    fun anadir(context: Context, recuerdo: String) {
        val limpio = PromptBuilder.PiiScrubber.scrub(recuerdo).trim().removeSuffix(".")
        if (limpio.isBlank()) return
        val actuales = recuerdos(context)
        // Sin duplicados (ignorando mayusculas); el nuevo va al final.
        val lista = (actuales.filterNot { it.equals(limpio, ignoreCase = true) } + limpio).takeLast(MAX_RECUERDOS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("recuerdos", json.encodeToString(listaTextos, lista)).apply()
    }

    fun borrarRecuerdos(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("recuerdos").apply()
    }

    /** Bloque para la IA con lo que ya sabe del usuario ("" si nada). */
    fun bloqueParaIa(context: Context): String {
        val r = recuerdos(context)
        if (r.isEmpty()) return ""
        return buildString {
            appendLine("[LO QUE YA SABES DEL USUARIO, POR CHARLAS ANTERIORES]")
            r.forEach { appendLine("- $it") }
        }
    }

    fun guardarChat(context: Context, mensajes: List<ChatMessage>) {
        val ultimos = mensajes.filter { !it.isStreaming && it.text.isNotBlank() }.takeLast(MAX_CHAT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("chat", json.encodeToString(listaChat, ultimos)).apply()
    }

    fun cargarChat(context: Context): List<ChatMessage> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("chat", null)
            ?.let { json.decodeFromString(listaChat, it) }
    }.getOrNull().orEmpty()
        // 0.1.5: limpia los mensajes guardados que quedaron con "nullnull...".
        .map { m -> if (m.text.contains("nullnull") || m.text.startsWith("null")) m.copy(text = sinNull(m.text)) else m }
        .filter { it.text.isNotBlank() }
}
