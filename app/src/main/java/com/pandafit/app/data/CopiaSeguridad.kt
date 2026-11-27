// ============================================================================
//  Panda Fit - CopiaSeguridad.kt
//  v26: COPIA DE SEGURIDAD en un archivo .json que el usuario guarda donde
//  quiera (Drive, Descargas, WhatsApp a si mismo...) y RESTAURACION en otro
//  movil. Incluye: metas y perfil, archivo de dias, entrenamientos, medallas,
//  el dia de hoy y el sueno guardado.
//  NO incluye: claves de IA (van cifradas con una llave que solo existe en este
//  telefono), la direccion de la pulsera ni identificadores.
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

object CopiaSeguridad {

    /** Archivos de preferencias que forman parte de la copia. */
    private val ARCHIVOS = listOf(
        SharedPreferencesHelper.PREFS_NAME, "panda_archivo", "panda_entrenos", "panda_logros",
        "panda_dia", "panda_sueno", "panda_avisos"
    )

    /** Claves que NUNCA salen en la copia (secretas o ligadas a este movil). */
    fun excluida(clave: String): Boolean =
        clave.startsWith("clave_api_") || clave == "gemini_api_key_cifrada" ||
            clave.startsWith("ultima_banda_ble") || clave == "id_usuario_anonimo"

    fun crear(context: Context): String {
        val raiz = buildJsonObject {
            put("app", "PandaFit")
            put("formato", 1)
            put("creada", System.currentTimeMillis())
            put("archivos", buildJsonObject {
                ARCHIVOS.forEach { nombre ->
                    val prefs = context.getSharedPreferences(nombre, Context.MODE_PRIVATE)
                    put(nombre, buildJsonObject {
                        prefs.all.forEach { (k, v) ->
                            if (excluida(k) || v == null) return@forEach
                            put(k, valor(v) ?: return@forEach)
                        }
                    })
                }
            })
        }
        return raiz.toString()
    }

    private fun valor(v: Any): JsonObject? = when (v) {
        is String -> buildJsonObject { put("t", "s"); put("v", v) }
        is Int -> buildJsonObject { put("t", "i"); put("v", v) }
        is Long -> buildJsonObject { put("t", "l"); put("v", v) }
        is Float -> buildJsonObject { put("t", "f"); put("v", v) }
        is Boolean -> buildJsonObject { put("t", "b"); put("v", v) }
        is Set<*> -> buildJsonObject {
            put("t", "set")
            put("v", JsonArray(v.filterIsInstance<String>().map { JsonPrimitive(it) }))
        }
        else -> null
    }

    /** Escribe la copia en el archivo elegido por el usuario. */
    fun guardarEn(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(crear(context).toByteArray()) }
            ?: error("No se pudo abrir el archivo")
    }

    /**
     * Restaura una copia. Devuelve el numero de datos restaurados.
     * Lanza una excepcion con mensaje claro si el archivo no es una copia valida.
     */
    fun restaurarDe(context: Context, uri: Uri): Int {
        val texto = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("No se pudo leer el archivo")
        return restaurar(context, texto)
    }

    fun restaurar(context: Context, texto: String): Int {
        val raiz = runCatching { Json.parseToJsonElement(texto).jsonObject }.getOrNull()
        require(raiz != null && raiz["app"]?.jsonPrimitive?.content == "PandaFit") { "Ese archivo no es una copia de Panda Fit." }
        val archivos = raiz["archivos"]?.jsonObject ?: error("La copia está vacía.")
        var total = 0
        archivos.forEach { (nombre, contenido) ->
            if (nombre !in ARCHIVOS) return@forEach
            val editor = context.getSharedPreferences(nombre, Context.MODE_PRIVATE).edit()
            contenido.jsonObject.forEach { (k, e) ->
                if (excluida(k)) return@forEach
                val o = e.jsonObject
                val v = o["v"] ?: return@forEach
                when (o["t"]?.jsonPrimitive?.content) {
                    "s" -> editor.putString(k, v.jsonPrimitive.content)
                    "i" -> editor.putInt(k, v.jsonPrimitive.int)
                    "l" -> editor.putLong(k, v.jsonPrimitive.long)
                    "f" -> editor.putFloat(k, v.jsonPrimitive.float)
                    "b" -> editor.putBoolean(k, v.jsonPrimitive.boolean)
                    "set" -> editor.putStringSet(k, v.jsonArray.map { it.jsonPrimitive.content }.toSet())
                    else -> return@forEach
                }
                total++
            }
            editor.commit()
        }
        return total
    }
}
