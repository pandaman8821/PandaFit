// ============================================================================
//  Panda Fit - AiProvider.kt
//  PROVEEDORES DE IA SOPORTADOS y contrato comun de los clientes.
//
//  Panda Fit no obliga a usar un solo proveedor: el usuario elige el suyo y
//  aporta SU propia API Key (guardada cifrada con AES-256-GCM y la clave maestra
//  en el Android Keystore). La app NO almacena claves de nadie mas.
//
//  Se habla por REST con cada proveedor (sin SDKs que cambian de API cada pocos
//  meses) usando la forma de API que exponen:
//    * Gemini  -> generativelanguage.googleapis.com  (formato Google)
//    * OpenAI / DeepSeek -> /v1/chat/completions      (formato OpenAI)
//    * Claude  -> api.anthropic.com/v1/messages       (formato Anthropic)
// ============================================================================
package com.pandafit.app.ai

import androidx.annotation.StringRes
import com.pandafit.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Proveedores de IA que Panda Fit sabe usar. */
enum class AiProvider(
    /** Identificador estable que se guarda en las preferencias. */
    val id: String,
    /** Nombre visible en la interfaz. */
    val displayName: String,
    @param:StringRes val labelRes: Int,
    /** Prefijo tipico de sus claves (solo para validar en el cliente). */
    val keyHint: String,
    /** Modelo por defecto; se puede cambiar desde Ajustes. */
    val defaultModel: String,
    /** Modelos sugeridos en el selector. */
    val suggestedModels: List<String>,
    /** Donde se consigue la clave. */
    val consoleUrl: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        labelRes = R.string.provider_gemini,
        keyHint = "AIza",
        // 0.0.10: lista oficial de Google (nov. 2026). gemini-2.5-* esta retirado
        // (2.5 Pro solo para quien ya lo usaba; 2.5 Flash se redirige el 1-dic-2026).
        defaultModel = "gemini-3.8-flash",
        suggestedModels = listOf(
            "gemini-3.8-flash", "gemini-4-hydrogen", "gemini-4-carbon", "gemini-4-argon",
            "gemini-4-neon", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview",
            "gemini-3-flash-preview", "gemini-3.1-flash-lite"
        ),
        consoleUrl = "https://aistudio.google.com/apikey"
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI (ChatGPT)",
        labelRes = R.string.provider_openai,
        keyHint = "sk-",
        // 0.0.10: lista oficial de OpenAI (nov. 2026). gpt-4o / 4.1 / o4-mini: obsoletos.
        defaultModel = "gpt-6.2-sol",
        suggestedModels = listOf(
            "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.5", "gpt-5.4-mini"
        ),
        consoleUrl = "https://platform.openai.com/api-keys"
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        labelRes = R.string.provider_deepseek,
        keyHint = "sk-",
        // 0.0.10: deepseek-chat / deepseek-reasoner se retiraron (24-jul-2026).
        defaultModel = "deepseek-flash",
        suggestedModels = listOf("deepseek-flash", "deepseek-v4-pro"),
        consoleUrl = "https://platform.deepseek.com/api_keys"
    ),
    CLAUDE(
        id = "claude",
        displayName = "Anthropic Claude",
        labelRes = R.string.provider_claude,
        keyHint = "sk-ant-",
        // 0.0.10: familia Claude actual (nov. 2026). claude-sonnet-4-5 se retira el 30-nov-2026.
        defaultModel = "claude-sonnet-5-5",
        suggestedModels = listOf(
            "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-2"
        ),
        consoleUrl = "https://console.anthropic.com/settings/keys"
    );

    companion object {
        fun fromId(id: String?): AiProvider =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI

        /**
         * 0.0.10: modelos RETIRADOS -> su sustituto oficial. Si el usuario tenia
         * guardado uno que ya no existe, se cambia solo (y no da error).
         */
        val RETIRADOS: Map<String, String> = mapOf(
            "deepseek-chat" to "deepseek-flash",
            "deepseek-reasoner" to "deepseek-flash",
            "deepseek-v4-flash" to "deepseek-flash",
            "gemini-2.0-flash" to "gemini-3.6-flash",
            "gemini-2.0-flash-lite" to "gemini-3.1-flash-lite",
            "gemini-3-pro-preview" to "gemini-3.1-pro-preview",
            "gemini-3.5-flash" to "gemini-3.6-flash",
            "gemini-3.7-flash" to "gemini-3.8-flash",
            "gemini-2.5-pro" to "gemini-3.8-flash",
            "o4-mini" to "gpt-6.2-sol",
            "o3-mini" to "gpt-6.2-sol",
            "gpt-4o-mini" to "gpt-6.2-sol",
            "gpt-4o" to "gpt-6.2-sol",
            "gpt-4.1-mini" to "gpt-6.2-sol",
            "claude-sonnet-4-5" to "claude-sonnet-5-5",
            "claude-opus-4-1" to "claude-opus-5-5",
            "claude-haiku-4-5" to "claude-haiku-5-5",
            "claude-3-5-haiku-latest" to "claude-haiku-5-5"
        )

        fun actualizarModelo(modelo: String): String = RETIRADOS[modelo.trim()] ?: modelo
    }
}

/** Peticion de chat comun a todos los proveedores. */
data class AiRequest(
    val systemInstruction: String,
    val turns: List<GeminiTurn>,
    val temperature: Float = 0.75f,
    val maxOutputTokens: Int = 1200,
    /** 0.0.9: modelo concreto para esta peticion (null = el elegido por el usuario). */
    val model: String? = null,
    /** 0.1.3: modo rapido: pedir al modelo que razone poco (responde antes). */
    val rapido: Boolean = false
)

/**
 * Contrato que cumple cada proveedor. Se mantiene pequeno a proposito: la app
 * solo necesita una respuesta de texto (completa o por trozos).
 */
interface AiChatClient {
    val provider: AiProvider

    /** true si el usuario ya ha guardado una clave para este proveedor. */
    fun hasApiKey(): Boolean

    /**
     * Resuelve el modelo a usar (y lo valida contra el proveedor cuando se le
     * pide refrescar). Si el modelo guardado ya no existe, devuelve uno valido.
     */
    suspend fun resolveModel(forceRefresh: Boolean): String

    /** 0.0.8: modelos de chat disponibles en la cuenta del usuario (para elegir). */
    suspend fun listModels(): List<String> = emptyList()

    /** Respuesta completa. */
    suspend fun chat(request: AiRequest): String

    /** Respuesta por trozos (streaming). Si el proveedor no lo soporta, emite uno solo. */
    fun chatStream(request: AiRequest): kotlinx.coroutines.flow.Flow<String>
}

// ============================================================================
//  UTILIDADES COMPARTIDAS POR LOS CLIENTES
// ============================================================================
internal object AiHttp {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** POST JSON con cabeceras; devuelve el cuerpo o lanza [AiError]. */
    fun postJson(
        url: String,
        headers: Map<String, String>,
        payload: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 60_000
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            when {
                code == 401 || code == 403 -> throw AiError.Unauthorized()
                code == 404 -> throw AiError.Server(code, "modelo o ruta no encontrada")
                code == 429 -> throw AiError.RateLimited(body.take(2000))
                code == 402 -> throw AiError.Server(code, "sin credito en tu cuenta")
                code !in 200..299 -> throw AiError.Server(code, body.take(180))
            }
            body
        } catch (e: AiError) {
            throw e
        } catch (t: Throwable) {
            throw AiError.Network(t.message ?: "desconocido")
        } finally {
            conn.disconnect()
        }
    }

    /** GET JSON con cabeceras. */
    fun getJson(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 25_000
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            when {
                code == 401 || code == 403 -> throw AiError.Unauthorized()
                code == 429 -> throw AiError.RateLimited(body.take(2000))
                code !in 200..299 -> throw AiError.Server(code, body.take(180))
            }
            body
        } catch (e: AiError) {
            throw e
        } catch (t: Throwable) {
            throw AiError.Network(t.message ?: "desconocido")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Lee un flujo SSE y emite el texto de cada evento usando [extract].
     * Se comparte entre OpenAI, DeepSeek y Claude (todos usan `data:`).
     */
    fun streamSse(
        url: String,
        headers: Map<String, String>,
        payload: String,
        extract: (String) -> String?
    ): Flow<String> = callbackFlow {
        var abierta: HttpURLConnection? = null
        val trabajo = CoroutineScope(Dispatchers.IO).launch {
            try {
                val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                abierta = conexion
                conexion.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = conexion.responseCode
                if (code !in 200..299) {
                    val err = conexion.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw when (code) {
                        401, 403 -> AiError.Unauthorized()
                        429 -> AiError.RateLimited(err.take(2000))
                        else -> AiError.Server(code, err.take(180))
                    }
                }

                BufferedReader(InputStreamReader(conexion.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    val datos = StringBuilder()
                    while (reader.readLine().also { line = it } != null) {
                        val actual = line ?: continue
                        if (actual.startsWith("data:")) {
                            datos.append(actual.removePrefix("data:").trim())
                        } else if (actual.isBlank() && datos.isNotEmpty()) {
                            extract(datos.toString())?.let { trySend(it) }
                            datos.clear()
                        }
                    }
                    if (datos.isNotEmpty()) extract(datos.toString())?.let { trySend(it) }
                }
                close()
            } catch (e: AiError) {
                close(e)
            } catch (t: Throwable) {
                close(AiError.Network(t.message ?: "desconocido"))
            } finally {
                runCatching { abierta?.disconnect() }
            }
        }
        awaitClose { trabajo.cancel() }
    }
}

// ============================================================================
//  JSON: construccion de mensajes (compartida)
// ============================================================================
internal object AiJson {

    /** Formato OpenAI / DeepSeek: messages[{role, content}] + stream. */
    fun openAiPayload(request: AiRequest, model: String, stream: Boolean): String =
        buildJsonObject {
            put("model", model)
            if (request.systemInstruction.isNotBlank()) {
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", request.systemInstruction)
                        }
                    )
                }
            }
            putJsonArray("messages") {
                if (request.systemInstruction.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", request.systemInstruction)
                        }
                    )
                }
                request.turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            put("role", if (turn.fromUser) "user" else "assistant")
                            put("content", turn.text)
                        }
                    )
                }
            }
            val m = model.lowercase()
            if (m.startsWith("deepseek")) {
                // DeepSeek V4: piensa por defecto y el razonamiento gasta del mismo limite.
                put("temperature", request.temperature)
                put("max_tokens", maxOf(request.maxOutputTokens, 8192))
                // 0.1.1: penaliza repetir palabras -> vocabulario mas variado.
                put("frequency_penalty", 0.5)
                put("presence_penalty", 0.3)
                // 0.1.3: modo rapido = razonar poco (niveles oficiales: low / high / max).
                if (request.rapido) put("reasoning_effort", "low")
            } else {
                // 0.0.10: OpenAI actual usa max_completion_tokens; sus modelos que
                // razonan (gpt-5/6, serie o) rechazan una temperatura distinta de 1.
                if (!modeloQueRazona(m)) {
                    put("temperature", request.temperature)
                    put("frequency_penalty", 0.5)
                    put("presence_penalty", 0.3)
                }
                put("max_completion_tokens", if (modeloQueRazona(m)) maxOf(request.maxOutputTokens, 8192) else request.maxOutputTokens)
                // 0.1.3: modo rapido en los modelos que razonan.
                if (request.rapido && modeloQueRazona(m)) put("reasoning_effort", "low")
            }
            if (stream) put("stream", true)
        }.toString()

    /** Modelos de OpenAI que razonan (no admiten temperatura personalizada). */
    fun modeloQueRazona(m: String): Boolean =
        m.startsWith("gpt-5") || m.startsWith("gpt-6") || Regex("^o\\d").containsMatchIn(m)

    /** 0.0.10: API "Responses" de OpenAI (respaldo si chat/completions no vale). */
    fun openAiResponsesPayload(request: AiRequest, model: String): String =
        buildJsonObject {
            put("model", model)
            if (request.systemInstruction.isNotBlank()) put("instructions", request.systemInstruction)
            putJsonArray("input") {
                request.turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            put("role", if (turn.fromUser) "user" else "assistant")
                            put("content", turn.text)
                        }
                    )
                }
            }
            put("max_output_tokens", maxOf(request.maxOutputTokens, 8192))
        }.toString()

    /** Texto de una respuesta de la API Responses (output_text o output[].content[]). */
    fun extractResponsesText(raw: String): String? = runCatching {
        val root = AiHttp.json.parseToJsonElement(raw).jsonObject
        root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (root["output"] as? JsonArray).orEmpty().joinToString("") { item ->
                val partes = item.jsonObject["content"] as? JsonArray ?: return@joinToString ""
                partes.joinToString("") { p ->
                    val o = p.jsonObject
                    if (o["type"]?.jsonPrimitive?.contentOrNull == "output_text") o["text"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
                }
            }.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Formato Anthropic Claude: system aparte + messages[{role, content}]. */
    fun claudePayload(request: AiRequest, model: String, stream: Boolean): String =
        buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxOutputTokens)
            put("temperature", request.temperature)
            if (request.systemInstruction.isNotBlank()) {
                put("system", request.systemInstruction)
            }
            putJsonArray("messages") {
                request.turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            put("role", if (turn.fromUser) "user" else "assistant")
                            put("content", turn.text)
                        }
                    )
                }
            }
            if (stream) put("stream", true)
        }.toString()

    /** Extrae el texto de una respuesta completa de OpenAI/DeepSeek. */
    fun extractOpenAiText(raw: String): String? = runCatching {
        val root = AiHttp.json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: return@runCatching null
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Extrae el texto de un trozo SSE de OpenAI/DeepSeek. */
    fun extractOpenAiDelta(raw: String): String? = runCatching {
        if (raw == "[DONE]") return@runCatching null
        val root = AiHttp.json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: return@runCatching null
        val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Extrae el texto de una respuesta completa de Claude. */
    fun extractClaudeText(raw: String): String? = runCatching {
        val root = AiHttp.json.parseToJsonElement(raw).jsonObject
        val content = root["content"] as? JsonArray ?: return@runCatching null
        content.joinToString("") { parte ->
            val obj = parte.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
            } else {
                ""
            }
        }.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Extrae el texto de un trozo SSE de Claude (evento content_block_delta). */
    fun extractClaudeDelta(raw: String): String? = runCatching {
        val root = AiHttp.json.parseToJsonElement(raw).jsonObject
        val tipo = root["type"]?.jsonPrimitive?.contentOrNull
        if (tipo != "content_block_delta") return@runCatching null
        root["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Lista de modelos de OpenAI/DeepSeek (`data[].id`). */
    fun extractModelIds(raw: String): List<String> = runCatching {
        val root = AiHttp.json.parseToJsonElement(raw).jsonObject
        val data = root["data"] as? JsonArray ?: return emptyList()
        data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }.getOrDefault(emptyList())

    /** Lista de modelos de Claude (`data[].id`). */
    fun extractClaudeModelIds(raw: String): List<String> = extractModelIds(raw)

    internal fun jsonObjectOrNull(raw: String): JsonObject? =
        runCatching { AiHttp.json.parseToJsonElement(raw).jsonObject }.getOrNull()
}
