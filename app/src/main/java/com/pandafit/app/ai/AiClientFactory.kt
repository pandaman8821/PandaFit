// ============================================================================
//  Panda Fit - AiClientFactory.kt
//  Clientes concretos de cada proveedor de IA y su fabrica.
//
//  * [GeminiAiClient]   -> Google Gemini (generativelanguage.googleapis.com)
//  * [OpenAiCompatClient] -> OpenAI y DeepSeek (misma forma de API)
//  * [ClaudeAiClient]   -> Anthropic Claude (api.anthropic.com/v1/messages)
//
//  Todos comparten [AiChatClient], asi que la app puede cambiar de proveedor sin
//  tocar la interfaz ni el ViewModel.
//
//  PRIVACIDAD: el contexto que se envia lo construye PromptBuilder (solo cifras
//  agregadas) y el texto libre pasa por PiiScrubber. Nada de esto cambia segun
//  el proveedor elegido.
// ============================================================================
package com.pandafit.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Fabrica de clientes. Se crea uno por proveedor y se reutiliza.
 *
 * @param apiKeyProvider clave del proveedor, o null si no hay configurada.
 * @param modelProvider  modelo elegido por el usuario (vacio = el por defecto).
 */
class AiClientFactory(
    private val apiKeyProvider: (AiProvider) -> String?,
    private val modelProvider: (AiProvider) -> String
) {
    private val cache = mutableMapOf<AiProvider, AiChatClient>()

    /**
     * Resuelve el modelo del proveedor activo y lo deja guardado en
     * preferencias (lo llama la app al verificar la clave).
     */
    suspend fun resolveModel(provider: AiProvider, forceRefresh: Boolean = true): String {
        val modelo = clientFor(provider).resolveModel(forceRefresh)
        modelSaver?.invoke(provider, modelo)
        return modelo
    }

    /** Callback opcional para persistir el modelo elegido. */
    var modelSaver: ((AiProvider, String) -> Unit)? = null

    fun clientFor(provider: AiProvider): AiChatClient = synchronized(cache) {
        cache.getOrPut(provider) {
            when (provider) {
                AiProvider.GEMINI -> GeminiAiClient(
                    { apiKeyProvider(AiProvider.GEMINI) },
                    { modelProvider(AiProvider.GEMINI) }
                )
                AiProvider.OPENAI, AiProvider.DEEPSEEK -> OpenAiCompatClient(
                    provider = provider,
                    apiKeyProvider = { apiKeyProvider(provider) },
                    modelProvider = { modelProvider(provider) }
                )
                AiProvider.CLAUDE -> ClaudeAiClient(
                    { apiKeyProvider(AiProvider.CLAUDE) },
                    { modelProvider(AiProvider.CLAUDE) }
                )
            }
        }
    }
}

// ============================================================================
//  GOOGLE GEMINI
// ============================================================================
/**
 * Cliente de Google Gemini. Habla con `generativelanguage.googleapis.com/v1beta`
 * y **autodetecta el modelo** con `ListModels`, de modo que la app sigue
 * funcionando cuando Google retira un modelo (que es lo habitual).
 */
class GeminiAiClient(
    private val apiKeyProvider: () -> String?,
    private val preferredModel: () -> String = { "" }
) : AiChatClient {

    override val provider: AiProvider = AiProvider.GEMINI
    private val json = AiHttp.json

    @Volatile
    private var resolvedModel: String = ""

    override fun hasApiKey(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun resolveModel(forceRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val preferido = preferredModel().trim()
        if (preferido.isNotEmpty()) return@withContext preferido
        if (!forceRefresh && resolvedModel.isNotEmpty()) return@withContext resolvedModel

        val disponibles = fetchModels()
        val elegido = disponibles.firstOrNull { it.contains("flash") && !it.contains("image") }
            ?: disponibles.firstOrNull { it.contains("pro") }
            ?: disponibles.firstOrNull()
            ?: AiProvider.GEMINI.defaultModel
        resolvedModel = elegido
        Log.i(TAG, "Modelo Gemini activo: $elegido (de ${disponibles.size} disponibles)")
        elegido
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        ModelosChat.filtrar(AiProvider.GEMINI, fetchModels())
    }

    private fun fetchModels(): List<String> {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        return runCatching {
            val body = AiHttp.getJson(
                "https://generativelanguage.googleapis.com/v1beta/models",
                mapOf("x-goog-api-key" to clave, "Accept" to "application/json")
            )
            val root = json.parseToJsonElement(body).jsonObject
            val modelos = root["models"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            modelos.mapNotNull { elemento ->
                val obj = elemento.jsonObject
                val nombre = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val metodos = obj["supportedGenerationMethods"] as? kotlinx.serialization.json.JsonArray
                val admite = metodos?.any {
                    it.jsonPrimitive.contentOrNull == "generateContent"
                } ?: true
                if (admite) nombre.removePrefix("models/") else null
            }
        }.getOrElse {
            Log.w(TAG, "ListModels fallo (${it.message}); se usa el modelo por defecto")
            emptyList()
        }
    }

    override suspend fun chat(request: AiRequest): String = withContext(Dispatchers.IO) {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val modelo = request.model ?: resolveModel(false)
        val payload = geminiPayload(request, modelo)
        val body = AiHttp.postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent",
            mapOf("x-goog-api-key" to clave),
            payload
        )
        extractGeminiText(body) ?: throw AiError.Empty()
    }

    override fun chatStream(request: AiRequest): Flow<String> = kotlinx.coroutines.flow.callbackFlow {
        val clave = apiKeyProvider()
        if (clave.isNullOrBlank()) {
            close(AiError.NoApiKey())
            return@callbackFlow
        }
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var conn: java.net.HttpURLConnection? = null
            try {
                val modelo = request.model ?: resolveModel(false)
                val url = java.net.URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/$modelo:streamGenerateContent?alt=sse"
                )
                // 0.1.3: en modo rapido se pide pensar poco. Si un modelo no admite
                // ese ajuste (400), se repite UNA vez sin el, sin que el usuario lo note.
                var conAjuste = true
                while (true) {
                    val payload = geminiPayload(request, modelo, ajustePensar = conAjuste)
                    val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15_000
                        readTimeout = 90_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("x-goog-api-key", clave)
                        setRequestProperty("Accept", "text/event-stream")
                    }
                    conn = c
                    c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    if (code in 200..299) break
                    val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code == 400 && conAjuste && err.contains("think", ignoreCase = true)) {
                        c.disconnect(); conAjuste = false; continue
                    }
                    throw when (code) {
                        401, 403 -> AiError.Unauthorized()
                        429 -> AiError.RateLimited(err.take(2000))
                        else -> AiError.Server(code, err.take(180))
                    }
                }
                val conn = conn!!
                java.io.BufferedReader(
                    java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { reader ->
                    var linea: String?
                    val eventos = StringBuilder()
                    while (reader.readLine().also { linea = it } != null) {
                        val actual = linea ?: continue
                        if (actual.startsWith("data:")) {
                            eventos.append(actual.removePrefix("data:").trim())
                        } else if (actual.isBlank() && eventos.isNotEmpty()) {
                            extractGeminiText(eventos.toString())?.let { trySend(it) }
                            eventos.clear()
                        }
                    }
                    if (eventos.isNotEmpty()) extractGeminiText(eventos.toString())?.let { trySend(it) }
                }
                close()
            } catch (e: AiError) {
                close(e)
            } catch (t: Throwable) {
                close(AiError.Network(t.message ?: "desconocido"))
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun geminiPayload(request: AiRequest, modelo: String = "", ajustePensar: Boolean = true): String =
        kotlinx.serialization.json.buildJsonObject {
            if (request.systemInstruction.isNotBlank()) {
                put("systemInstruction", kotlinx.serialization.json.buildJsonObject {
                    put("parts", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("text", request.systemInstruction)
                        })
                    })
                })
            }
            put("contents", kotlinx.serialization.json.buildJsonArray {
                request.turns.forEach { turn ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("role", if (turn.fromUser) "user" else "model")
                        put("parts", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject { put("text", turn.text) })
                        })
                    })
                }
            })
            put("generationConfig", kotlinx.serialization.json.buildJsonObject {
                put("temperature", request.temperature)
                put("topP", 0.95)
                // 0.0.10: los modelos 3.x/4 piensan a su manera (sin presupuesto fijo):
                // se les da mas margen para que la respuesta nunca salga cortada.
                put("maxOutputTokens", if (modelo.contains("2.5")) request.maxOutputTokens else maxOf(request.maxOutputTokens, 12_000))
                // 0.0.6: los modelos 2.5 "piensan" y ese razonamiento gasta del mismo
                // limite: sin tope, las respuestas largas salian cortadas. Se le da
                // un presupuesto propio y razonable para pensar bien.
                if (ajustePensar && modelo.contains("2.5")) put("thinkingConfig", kotlinx.serialization.json.buildJsonObject {
                    // 0.1.3: modo rapido = poco razonamiento (responde mucho antes).
                    put("thinkingBudget", if (request.rapido) 256 else 1536)
                }) else if (ajustePensar && request.rapido && modelo.startsWith("gemini-3")) {
                    // Gemini 3.x usa "thinkingLevel" en vez de presupuesto.
                    put("thinkingConfig", kotlinx.serialization.json.buildJsonObject { put("thinkingLevel", "low") })
                }
            })
            put("safetySettings", kotlinx.serialization.json.buildJsonArray {
                SAFETY_CATEGORIES.forEach { categoria ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("category", categoria)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            })
        }.toString()

    private fun extractGeminiText(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val candidatos = root["candidates"] as? kotlinx.serialization.json.JsonArray
            ?: return@runCatching null
        val partes = candidatos.firstOrNull()
            ?.jsonObject?.get("content")
            ?.jsonObject?.get("parts") as? kotlinx.serialization.json.JsonArray
            ?: return@runCatching null
        partes.joinToString("") { parte ->
            parte.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        private const val TAG = "PandaFitGemini"
        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )
    }
}

// ============================================================================
//  OPENAI Y DEEPSEEK (misma forma de API)
// ============================================================================
class OpenAiCompatClient(
    override val provider: AiProvider,
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String
) : AiChatClient {

    private val baseUrl: String
        get() = when (provider) {
            AiProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
            else -> "https://api.openai.com/v1"
        }

    override fun hasApiKey(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun resolveModel(forceRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val elegido = modelProvider().trim().ifEmpty { provider.defaultModel }
        if (!forceRefresh) return@withContext elegido

        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val disponibles = runCatching {
            AiJson.extractModelIds(
                AiHttp.getJson("$baseUrl/models", mapOf("Authorization" to "Bearer $clave"))
            )
        }.getOrElse {
            Log.w(TAG, "No se pudo listar modelos de ${provider.id}: ${it.message}")
            emptyList()
        }

        // Si el modelo guardado ya no existe, se cae a uno de los sugeridos.
        val valido = disponibles.firstOrNull { it == elegido }
            ?: disponibles.firstOrNull { actual -> provider.suggestedModels.any { it == actual } }
            ?: elegido
        Log.i(TAG, "Modelo ${provider.id}: $valido (de ${disponibles.size} disponibles)")
        valido
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        ModelosChat.filtrar(
            provider,
            AiJson.extractModelIds(AiHttp.getJson("$baseUrl/models", mapOf("Authorization" to "Bearer $clave")))
        )
    }

    override suspend fun chat(request: AiRequest): String = withContext(Dispatchers.IO) {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val modelo = request.model ?: resolveModel(false)
        val body = AiHttp.postJson(
            "$baseUrl/chat/completions",
            mapOf("Authorization" to "Bearer $clave"),
            AiJson.openAiPayload(request, modelo, stream = false)
        )
        AiJson.extractOpenAiText(body) ?: throw AiError.Empty()
    }

    override fun chatStream(request: AiRequest): Flow<String> = flow {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val modelo = request.model ?: resolveModel(false)
        val payload = AiJson.openAiPayload(request, modelo, stream = true)
        var algo = false
        try {
            AiHttp.streamSse(
                url = "$baseUrl/chat/completions",
                headers = mapOf("Authorization" to "Bearer $clave"),
                payload = payload,
                extract = AiJson::extractOpenAiDelta
            ).collect { algo = true; emit(it) }
        } catch (e: AiError.Server) {
            // 0.0.10: los modelos mas nuevos de OpenAI pueden no admitir el formato
            // clasico (chat/completions): se reintenta con la API "Responses".
            if (provider != AiProvider.OPENAI || algo || e.code !in setOf(400, 404)) throw e
            Log.i(TAG, "chat/completions no admitido para $modelo; uso /responses")
            val body = AiHttp.postJson(
                "$baseUrl/responses",
                mapOf("Authorization" to "Bearer $clave"),
                AiJson.openAiResponsesPayload(request, modelo),
                readTimeoutMs = 120_000
            )
            emit(AiJson.extractResponsesText(body) ?: throw AiError.Empty())
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "PandaFitOpenAI"
    }
}

// ============================================================================
//  ANTHROPIC CLAUDE
// ============================================================================
class ClaudeAiClient(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String
) : AiChatClient {

    override val provider: AiProvider = AiProvider.CLAUDE

    override fun hasApiKey(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun resolveModel(forceRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val elegido = modelProvider().trim().ifEmpty { provider.defaultModel }
        if (!forceRefresh) return@withContext elegido

        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val disponibles = runCatching {
            AiJson.extractClaudeModelIds(
                AiHttp.getJson(
                    "https://api.anthropic.com/v1/models",
                    mapOf("x-api-key" to clave, "anthropic-version" to ANTHROPIC_VERSION)
                )
            )
        }.getOrElse {
            Log.w(TAG, "No se pudo listar modelos de Claude: ${it.message}")
            emptyList()
        }
        val valido = disponibles.firstOrNull { it == elegido }
            ?: disponibles.firstOrNull { actual -> provider.suggestedModels.any { it == actual } }
            ?: elegido
        Log.i(TAG, "Modelo Claude: $valido (de ${disponibles.size} disponibles)")
        valido
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        ModelosChat.filtrar(
            AiProvider.CLAUDE,
            AiJson.extractClaudeModelIds(
                AiHttp.getJson(
                    "https://api.anthropic.com/v1/models",
                    mapOf("x-api-key" to clave, "anthropic-version" to ANTHROPIC_VERSION)
                )
            )
        )
    }

    override suspend fun chat(request: AiRequest): String = withContext(Dispatchers.IO) {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val modelo = request.model ?: resolveModel(false)
        val body = AiHttp.postJson(
            "https://api.anthropic.com/v1/messages",
            claudeHeaders(clave),
            AiJson.claudePayload(request, modelo, stream = false)
        )
        AiJson.extractClaudeText(body) ?: throw AiError.Empty()
    }

    override fun chatStream(request: AiRequest): Flow<String> = flow {
        val clave = apiKeyProvider() ?: throw AiError.NoApiKey()
        val modelo = request.model ?: resolveModel(false)
        val payload = AiJson.claudePayload(request, modelo, stream = true)
        emitAll(
            AiHttp.streamSse(
                url = "https://api.anthropic.com/v1/messages",
                headers = claudeHeaders(clave),
                payload = payload,
                extract = AiJson::extractClaudeDelta
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun claudeHeaders(clave: String): Map<String, String> = mapOf(
        "x-api-key" to clave,
        "anthropic-version" to ANTHROPIC_VERSION
    )

    companion object {
        private const val TAG = "PandaFitClaude"

        /** Version de la API de Anthropic (estable desde 2023). */
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
