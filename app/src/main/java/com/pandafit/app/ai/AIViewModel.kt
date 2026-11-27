// ============================================================================
//  Panda Fit - AIViewModel.kt
//  CEREBRO DEL ASISTENTE IA FITNESS.
//
//  Responsabilidades:
//    * Chat en tiempo real con el proveedor de IA que elija el usuario:
//      **Google Gemini, OpenAI, DeepSeek o Anthropic Claude** (con streaming).
//    * Inyectar el CONTEXTO AGREGADO y ANONIMO de la app en cada peticion.
//    * Generar el plan de entrenamiento personalizado y convertirlo en objeto
//      (TrainingPlanParser) para mostrarlo en tarjetas.
//    * Sugerencias diarias de motivacion, recordatorios y resumen de progreso.
//    * Modo voz con TextToSpeech (espanol, velocidad natural).
//    * Politica de seguridad: el usuario aporta SU PROPIA clave de cada
//      proveedor, guardada CIFRADA (AES-256-GCM + Keystore). La app no almacena
//      claves de terceros ni las comparte entre proveedores.
//    * Si no hay red o no hay clave: responde el MOTOR LOCAL (FitnessEngine),
//      asi el asistente nunca se queda mudo y no sale nada del telefono.
// ============================================================================
package com.pandafit.app.ai

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.data.model.ChatMessage
import com.pandafit.app.data.model.ChatRole
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.MetricSeries
import com.pandafit.app.data.model.TrainingPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Estado completo de la pantalla del Asistente IA. */
data class AiUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isThinking: Boolean = false,
    /** Proveedor de IA activo. */
    val provider: AiProvider = AiProvider.GEMINI,
    /** true si el proveedor activo ya tiene clave guardada. */
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val modelName: String = "",
    /** Modelos sugeridos del proveedor activo (para el selector). */
    val models: List<String> = emptyList(),
    val voiceEnabled: Boolean = false,
    val speaking: Boolean = false,
    val error: String? = null,
    val privacyNotice: String = PromptBuilder.PRIVACY_NOTICE,
    val lastPlan: TrainingPlan? = null,
    val usedLocalEngine: Boolean = false,
    /** 0.0.6: voz natural de IA y su estilo. */
    val naturalVoice: Boolean = true,
    val voiceStyle: String = "MASCULINA",
    /** 0.0.6: cuantas cosas recuerda Panda del usuario. */
    val memories: Int = 0,
    /** 0.0.8: cargando la lista de modelos de la cuenta del usuario. */
    val modelsLoading: Boolean = false,
    /** 0.0.8: true si [models] viene de la cuenta (no solo los sugeridos). */
    val modelsFromAccount: Boolean = false,
    /** 0.1.3: modo rapido de la IA. */
    val fastMode: Boolean = true
)

class AIViewModel(application: Application) : AndroidViewModel(application) {

    private val pandaApp = application as PandaFitApplication
    private val prefs = pandaApp.preferences
    private val repository = pandaApp.repository
    private val factory: AiClientFactory = pandaApp.aiClients

    // ------------------------------------------------------------------ VOZ
    // 0.0.6: voz natural de IA (Gemini/OpenAI) con respaldo en la del telefono.
    private val voz = VozPanda(
        context = application,
        scope = viewModelScope,
        clave = { p -> prefs.getApiKey(p) },
        onHablando = { hablando -> _state.value = _state.value.copy(speaking = hablando) }
    )

    // ----------------------------------------------------------------- ESTADO
    private val proveedorInicial = prefs.aiProvider

    private val _state = MutableStateFlow(
        AiUiState(
            provider = proveedorInicial,
            hasApiKey = prefs.hasApiKey(proveedorInicial),
            maskedApiKey = prefs.maskedApiKey(proveedorInicial),
            modelName = prefs.getModel(proveedorInicial)
                .ifBlank { proveedorInicial.defaultModel },
            models = proveedorInicial.suggestedModels,
            voiceEnabled = prefs.voiceModeEnabled,
            naturalVoice = prefs.naturalVoiceEnabled,
            fastMode = prefs.aiFastMode,
            voiceStyle = prefs.voiceStyle,
            memories = MemoriaPanda.recuerdos(application).size
        )
    )
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    /** Contexto anonimo que se envia a la IA (visible para el usuario). */
    private val _sentContext = MutableStateFlow("")
    val sentContext: StateFlow<String> = _sentContext.asStateFlow()

    private val today: DailyMetrics get() = prefs.cachedToday
    private val history: MetricSeries get() = prefs.cachedHistory

    private val cliente: AiChatClient get() = factory.clientFor(_state.value.provider)

    init {
        viewModelScope.launch { resolveModelName() }
        // 0.0.6: la conversacion se conserva al cerrar la app (ultimos 40 mensajes).
        val guardados = MemoriaPanda.cargarChat(application)
        if (guardados.isNotEmpty()) {
            _state.value = _state.value.copy(messages = guardados)
        } else {
            addAssistantMessage(welcomeMessage())
        }
    }

    // ========================================================================
    //  PROVEEDOR DE IA
    // ========================================================================

    /** Cambia de proveedor (Gemini / OpenAI / DeepSeek / Claude). */
    fun selectProvider(provider: AiProvider) {
        if (provider == _state.value.provider) return
        prefs.aiProvider = provider
        // Se relee la clave y el modelo DE ESE proveedor: cada uno tiene la suya.
        _state.value = _state.value.copy(
            provider = provider,
            hasApiKey = prefs.hasApiKey(provider),
            maskedApiKey = prefs.maskedApiKey(provider),
            modelName = prefs.getModel(provider).ifBlank { provider.defaultModel },
            models = provider.suggestedModels,
            modelsFromAccount = false,
            error = null
        )
        loadModels()
        addAssistantMessage(
            str(com.pandafit.app.R.string.ai_msg_provider_changed, provider.displayName) + " " +
                if (prefs.hasApiKey(provider)) {
                    str(com.pandafit.app.R.string.ai_msg_key_was_saved)
                } else {
                    str(com.pandafit.app.R.string.ai_msg_add_key, provider.displayName)
                }
        )
        viewModelScope.launch { resolveModelName() }
    }

    /** Cambia el modelo del proveedor activo. */
    fun selectModel(model: String) {
        val proveedor = _state.value.provider
        prefs.setModel(proveedor, model)
        _state.value = _state.value.copy(modelName = model, error = null)
    }

    /**
     * 0.0.8: pide a la cuenta del usuario sus modelos de chat disponibles para
     * poder elegir cualquiera (antes solo se usaba el de por defecto).
     */
    fun loadModels() {
        val proveedor = _state.value.provider
        if (!prefs.hasApiKey(proveedor) || _state.value.modelsLoading) return
        _state.value = _state.value.copy(modelsLoading = true)
        viewModelScope.launch {
            val lista = runCatching { factory.clientFor(proveedor).listModels() }.getOrDefault(emptyList())
            if (_state.value.provider != proveedor) return@launch
            val actual = _state.value.modelName
            _state.value = _state.value.copy(
                modelsLoading = false,
                modelsFromAccount = lista.isNotEmpty(),
                models = if (lista.isNotEmpty()) (listOf(actual) + lista).distinct()
                else (listOf(actual) + proveedor.suggestedModels).distinct()
            )
        }
    }

    // ========================================================================
    //  VOZ (TextToSpeech)
    // ========================================================================
    fun toggleVoice() {
        val enabled = !_state.value.voiceEnabled
        prefs.voiceModeEnabled = enabled
        _state.value = _state.value.copy(voiceEnabled = enabled)
        if (enabled) speak(str(com.pandafit.app.R.string.ai_msg_voice_on))
        else stopSpeaking()
    }

    /** 0.1.3: modo rapido (respuestas antes) o detallado (piensa mas). */
    fun setFastMode(on: Boolean) {
        prefs.aiFastMode = on
        _state.value = _state.value.copy(fastMode = on)
    }

    /** 0.0.6: activa/desactiva la voz natural de IA. */
    fun setNaturalVoice(on: Boolean) {
        prefs.naturalVoiceEnabled = on
        _state.value = _state.value.copy(naturalVoice = on)
    }

    /** 0.0.6: estilo de la voz natural ("MASCULINA" / "FEMENINA"). */
    fun setVoiceStyle(estilo: String) {
        prefs.voiceStyle = estilo
        _state.value = _state.value.copy(voiceStyle = estilo)
    }

    /** Boton "Probar voz". */
    fun testVoice() {
        voz.probar(_state.value.naturalVoice, _state.value.provider, estiloVoz())
        mostrarAvisoVoz()
    }

    private fun estiloVoz() =
        if (_state.value.voiceStyle == "FEMENINA") VozPanda.Estilo.FEMENINA else VozPanda.Estilo.MASCULINA

    /**
     * Lee un texto en voz alta COMPLETO (0.0.6: antes se cortaba a 900
     * caracteres), con la voz natural de IA si esta activada.
     */
    fun speak(text: String) {
        if (!_state.value.voiceEnabled) return
        // 0.1.4: las lecturas AUTOMATICAS usan la voz del telefono (instantanea y
        // sin limites). La voz natural de IA se reserva para el boton "Escuchar"
        // de cada mensaje: asi el cupo gratuito se gasta solo en lo que quieres oir.
        voz.hablar(text, natural = false, proveedor = _state.value.provider, estilo = estiloVoz())
    }

    /** 0.1.4: boton "Escuchar" de un mensaje: voz natural de IA (si esta activada). */
    fun listen(text: String) {
        voz.hablar(text, _state.value.naturalVoice, _state.value.provider, estiloVoz())
        mostrarAvisoVoz()
    }

    private fun mostrarAvisoVoz() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(4_000)
            // 0.0.7: si el chat ya aviso de que no hay conexion, no se repite.
            if (_state.value.usedLocalEngine) return@launch
            val aviso = voz.ultimoAviso ?: return@launch
            if (_state.value.error == null) {
                _state.value = _state.value.copy(error = aviso)
                // El aviso se quita solo para no tapar el chat.
                kotlinx.coroutines.delay(9_000)
                if (_state.value.error == aviso) _state.value = _state.value.copy(error = null)
            }
        }
    }

    fun stopSpeaking() {
        voz.parar()
    }

    // ========================================================================
    //  CLAVES (politica de seguridad: una clave por proveedor, cifrada)
    // ========================================================================
    /**
     * Guarda la clave del proveedor activo, CIFRADA (AES-256-GCM + Keystore).
     * Devuelve un mensaje para la interfaz.
     */
    fun saveApiKey(rawKey: String): String {
        val proveedor = _state.value.provider
        val clave = rawKey.trim()
        if (clave.length < 20) {
            val msg = str(com.pandafit.app.R.string.ai_msg_key_short, proveedor.displayName)
            _state.value = _state.value.copy(error = msg)
            return msg
        }
        // Aviso (no bloqueo) si el prefijo no es el habitual del proveedor.
        val prefijoOk = clave.startsWith(proveedor.keyHint, ignoreCase = true)
        val guardada = prefs.saveApiKey(proveedor, clave)
        _state.value = _state.value.copy(
            hasApiKey = guardada,
            maskedApiKey = prefs.maskedApiKey(proveedor),
            error = if (guardada) null else str(com.pandafit.app.R.string.ai_msg_key_encrypt_fail)
        )
        if (guardada) loadModels()
        return when {
            !guardada -> str(com.pandafit.app.R.string.ai_msg_key_save_error)
            !prefijoOk -> str(com.pandafit.app.R.string.ai_msg_key_saved_warn, proveedor.displayName, proveedor.keyHint)
            else -> str(com.pandafit.app.R.string.ai_msg_key_saved, proveedor.displayName)
        }
    }

    fun clearApiKey() {
        val proveedor = _state.value.provider
        prefs.clearApiKey(proveedor)
        _state.value = _state.value.copy(hasApiKey = false, maskedApiKey = "", error = null)
    }

    /**
     * Comprueba contra el proveedor que la clave funciona y guarda el modelo
     * valido (si un modelo ya no existe, se cambia por uno disponible).
     */
    fun validateApiKey() {
        viewModelScope.launch {
            val proveedor = _state.value.provider
            _state.value = _state.value.copy(isThinking = true, error = null)
            try {
                val modelo = try {
                    factory.resolveModel(proveedor, forceRefresh = true)
                } catch (e: AiError) {
                    // Listar modelos no esta disponible en todos los servicios:
                    // si falla, se usa el modelo por defecto del proveedor.
                    Log.w(TAG, "No se pudo listar modelos de ${proveedor.id}: ${e.message}")
                    prefs.setModel(proveedor, proveedor.defaultModel)
                    proveedor.defaultModel
                }
                _state.value = _state.value.copy(
                    modelName = modelo,
                    models = (listOf(modelo) + proveedor.suggestedModels).distinct(),
                    isThinking = false,
                    error = null
                )
                addAssistantMessage(
                    str(com.pandafit.app.R.string.ai_msg_key_verified, proveedor.displayName, modelo)
                )
            } catch (e: AiError) {
                _state.value = _state.value.copy(isThinking = false, error = e.texto(getApplication()))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isThinking = false,
                    error = str(com.pandafit.app.R.string.ai_msg_key_verify_fail, t.message ?: "")
                )
            }
        }
    }

    // ========================================================================
    //  CHAT
    // ========================================================================
    fun onInputChange(value: String) {
        _state.value = _state.value.copy(input = value)
    }

    /** Envia el mensaje escrito por el usuario. */
    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.isThinking) return
        _state.value = _state.value.copy(input = "")
        launchChat(userText = text, intent = null)
    }

    /** Ejecuta una accion rapida (chips de la pantalla). */
    fun runIntent(intent: AiIntent) {
        if (_state.value.isThinking) return
        // 0.1.7: la burbuja muestra el texto del boton en el idioma del telefono.
        launchChat(userText = intent.prompt, intent = intent, userBubble = str(intent.labelRes))
    }

    /** Genera un plan de entrenamiento personalizado (con o sin IA). */
    fun generatePlan() = runIntent(AiIntent.TRAINING_PLAN)

    private fun launchChat(
        userText: String,
        intent: AiIntent?,
        userBubble: String? = null
    ) {
        // Privacidad: se enmascara el texto libre (email, telefono, MAC, enlaces)
        // ANTES de guardarlo y de enviarlo, para que nunca salga un dato personal.
        val safeText = PromptBuilder.PiiScrubber.scrub(userText)
        addUserMessage(userBubble ?: safeText)

        val contextBlock = PromptBuilder.freeChatPrompt(
            userMessage = safeText,
            today = today,
            history = history,
            stepGoal = prefs.stepGoal,
            sleepGoalHours = prefs.sleepGoalHours
        ) + if (prefs.aiFastMode && intent?.wantsPlan != true) {
            // 0.1.3: respuestas mas directas = llegan antes y se leen antes en voz alta.
            "\n[MODO RÁPIDO] Ve al grano. Si te piden un informe o resumen, unas 120-180 " +
                "palabras con lo esencial (3-4 puntos como mucho). En la charla normal, 2-4 frases."
        } else ""
        _sentContext.value = PromptBuilder.contextBlock(
            today, history, prefs.stepGoal, prefs.sleepGoalHours
        )

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isThinking = true,
                error = null,
                usedLocalEngine = false
            )

            val planWanted = intent?.wantsPlan == true
            val system = PromptBuilder.systemInstruction(prefs.genderMode)

            // El asistente nunca deja de responder: si la IA falla, motor local.
            try {
                if (!cliente.hasApiKey()) throw AiError.NoApiKey()
                val turnos = buildTurns(contextBlock)
                val tokens = if (planWanted) 6000 else 3500  // 0.0.6: margen para pensar + responder completo
                responderConReintentos(system, turnos, tokens)
                if (planWanted) attachParsedPlan()
                _state.value = _state.value.copy(isThinking = false)
            } catch (e: AiError) {
                // 0.0.7: si es la red, se explica la causa real (sin internet o app bloqueada).
                val motivo = if (e is AiError.Network) {
                    Red.mensaje(getApplication(), Red.clasificar(getApplication(), e), com.pandafit.app.R.string.net_what_ai)
                } else e.texto(getApplication())
                fallbackToLocal(intent, userText, motivo)
            } catch (t: Throwable) {
                fallbackToLocal(intent, userText, Red.mensaje(getApplication(), Red.clasificar(getApplication(), t), com.pandafit.app.R.string.net_what_ai))
            }
        }
    }

    /**
     * 0.1.2: responde aunque el proveedor ponga limites:
     *   1. Si pide esperar pocos segundos (limite POR MINUTO), espera y reintenta.
     *   2. Si no, prueba modelos mas ligeros del mismo proveedor (tienen limites
     *      gratuitos mas altos) y avisa con el motivo real que dio el proveedor.
     */
    private suspend fun responderConReintentos(system: String, turnos: List<GeminiTurn>, tokens: Int) {
        val proveedor = _state.value.provider
        val elegido = _state.value.modelName
        var error: AiError = try {
            streamAnswer(system = system, turns = turnos, maxTokens = tokens)
            return
        } catch (e: AiError) { e }

        if (error is AiError.RateLimited) {
            val cuerpo = (error as AiError.RateLimited).detalle
            val espera = Limite.segundosDeEspera(cuerpo)
            // 0.1.3: solo se espera si es poco (<= 10 s); si no, se pasa directo a
            // un modelo mas ligero (antes podia esperar hasta 30 s).
            if (!Limite.esDiario(cuerpo) && espera != null && espera <= 10) {
                _state.value = _state.value.copy(error = str(com.pandafit.app.R.string.ai_msg_wait_limit, espera))
                kotlinx.coroutines.delay(espera * 1000L + 700)
                _state.value = _state.value.copy(error = null)
                error = try {
                    streamAnswer(system = system, turns = turnos, maxTokens = tokens)
                    return
                } catch (e: AiError) { e }
            }
        }

        val reintentable = error is AiError.RateLimited || error is AiError.Server
        if (!reintentable) throw error
        val app = getApplication<Application>()
        val motivo = when (val e = error) {
            is AiError.RateLimited -> Limite.mensajeProveedor(e.detalle)?.let { "${e.texto(app)} ($it)" } ?: e.texto(app)
            else -> error.texto(app)
        }
        for (alternativo in modelosDeRespaldo(proveedor).filter { it != elegido }) {
            error = try {
                streamAnswer(
                    system = system, turns = turnos, maxTokens = tokens, modelo = alternativo,
                    aviso = str(com.pandafit.app.R.string.ai_msg_fallback_model, elegido, motivo, alternativo)
                )
                return
            } catch (e: AiError) { e }
            if (error !is AiError.RateLimited && error !is AiError.Server) break
        }
        throw error
    }

    /** Modelos a probar si el elegido falla: los ligeros tienen mas cuota gratis. */
    private fun modelosDeRespaldo(p: AiProvider): List<String> = when (p) {
        AiProvider.GEMINI -> listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        AiProvider.OPENAI -> listOf("gpt-6.2-sol", "gpt-6-luna")
        AiProvider.DEEPSEEK -> listOf("deepseek-flash")
        AiProvider.CLAUDE -> listOf("claude-sonnet-5-5", "claude-haiku-5-5")
    }

    /** Crea el mensaje del asistente y va rellenandolo con los trozos del stream. */
    private suspend fun streamAnswer(
        system: String,
        turns: List<GeminiTurn>,
        maxTokens: Int,
        /** 0.0.9: modelo concreto (reintento con el modelo por defecto). */
        modelo: String? = null,
        /** 0.0.9: aviso que se muestra bajo la respuesta (p. ej. "se uso otro modelo"). */
        aviso: String? = null
    ) {
        val messageId = newId()
        val placeholder = ChatMessage(
            id = messageId,
            role = ChatRole.ASSISTANT,
            text = "",
            isStreaming = true,
            notice = aviso
        )
        _state.value = _state.value.copy(messages = _state.value.messages + placeholder)

        val builder = StringBuilder()
        var lastEmit = 0L
        try {
            cliente.chatStream(
                AiRequest(
                    systemInstruction = system,
                    turns = turns,
                    maxOutputTokens = maxTokens,
                    // Un poco mas de variedad en la charla; los planes, mas precisos.
                    // 0.1.1: algo mas de creatividad en la charla (vocabulario mas variado).
                    temperature = if (maxTokens > 5000) 0.6f else 0.95f,
                    model = modelo,
                    // 0.1.3: modo rapido (salvo los planes, que necesitan pensar bien).
                    rapido = prefs.aiFastMode && maxTokens <= 5000
                )
            )
                .catch { throwable ->
                    // Si ya hay texto, se conserva; si no, se propaga para que
                    // salte el motor local.
                    if (builder.isEmpty()) throw throwable
                }
                .collect { chunk ->
                    builder.append(chunk)
                    // Refresco de UI cada ~90 ms: suaviza la animacion y ahorra CPU.
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 90) {
                        updateMessage(messageId, ConEnes.corregir(builder.toString()), streaming = true)
                        lastEmit = now
                    }
                }
        } finally {
            if (builder.isNotEmpty()) {
                updateMessage(messageId, ConEnes.corregir(builder.toString().trim()), streaming = false)
            } else {
                // 0.0.9: si fallo sin escribir nada, se quita la burbuja vacia ("escribiendo...").
                _state.value = _state.value.copy(messages = _state.value.messages.filterNot { it.id == messageId })
            }
        }

        // v27: si el modelo escribe "sueno", se corrige a "sueño" (texto y voz).
        // 0.0.6: se guardan los datos duraderos que la IA marca con [RECUERDA: ...].
        val (limpio, nuevos) = MemoriaPanda.extraer(builder.toString())
        if (nuevos.isNotEmpty()) {
            nuevos.forEach { MemoriaPanda.anadir(getApplication(), it) }
            _state.value = _state.value.copy(memories = MemoriaPanda.recuerdos(getApplication()).size)
        }
        val finalText = ConEnes.corregir(limpio.trim())
        if (finalText.isEmpty()) throw AiError.Empty()
        speak(finalText)
        repository.syncChatSummary(_state.value.messages.size, "chat")
    }

    /** Respuesta local (sin IA): siempre disponible y privada. */
    private suspend fun fallbackToLocal(intent: AiIntent?, userText: String, reason: String) {
        val report = FitnessEngine.analyze(today, history, prefs.sleepGoalHours)
        val answer = if (intent?.wantsPlan == true) {
            val plan = FitnessEngine.localPlan(today, history)
            _state.value = _state.value.copy(lastPlan = plan)
            repository.syncPlan(plan)
            buildLocalPlanText(plan)
        } else {
            FitnessEngine.localAnswer(intent, userText, report, today)
        }

        addAssistantMessage(
            text = answer,
            notice = reason + " " + str(com.pandafit.app.R.string.ai_msg_local_engine)
        )
        _state.value = _state.value.copy(
            isThinking = false,
            usedLocalEngine = true,
            error = null
        )
        speak(answer)
    }

    /** Convierte en objeto el plan que hay dentro del ultimo mensaje del asistente. */
    private fun attachParsedPlan() {
        val last = _state.value.messages.lastOrNull { it.role == ChatRole.ASSISTANT } ?: return
        val plan = TrainingPlanParser.parse(last.text) ?: FitnessEngine.localPlan(today, history)
        val updated = _state.value.messages.map {
            if (it.id == last.id) it.copy(plan = plan) else it
        }
        _state.value = _state.value.copy(messages = updated, lastPlan = plan)
        viewModelScope.launch { repository.syncPlan(plan) }
    }

    private fun buildLocalPlanText(plan: TrainingPlan): String = buildString {
        appendLine("**${plan.title.uppercase()}**")
        appendLine("Objetivo: ${plan.goal}")
        appendLine(
            "${plan.daysPerWeek} dias/semana \u2022 ${plan.weeklyMinutes} min/semana " +
                "\u2022 nivel ${plan.level}"
        )
        appendLine()
        plan.days.forEach { day ->
            if (day.exercises.isEmpty()) {
                appendLine("**${day.day}**: ${day.focus}")
            } else {
                appendLine(
                    "**${day.day} \u2013 ${day.focus}** (${day.totalMinutes} min, " +
                        "intensidad ${day.intensity})"
                )
                day.exercises.forEach { ex ->
                    val detail = when {
                        ex.sets > 0 && ex.reps.isNotEmpty() -> "${ex.sets} x ${ex.reps}"
                        ex.durationMinutes > 0 -> "${ex.durationMinutes} min"
                        ex.notes.isNotEmpty() -> ex.notes
                        else -> ""
                    }
                    appendLine("- ${ex.name}${if (detail.isNotEmpty()) ": $detail" else ""}")
                }
            }
        }
        appendLine()
        appendLine("**MOTIVO**: ${plan.rationale}")
    }

    // ========================================================================
    //  MENSAJES
    // ========================================================================
    /** Historial convertido a turnos (solo texto, nunca datos personales). */
    private fun buildTurns(contextoBase: String): List<GeminiTurn> {
        // 0.1.1: para que no repita: como empezo sus ultimas respuestas y que
        // palabras ya uso demasiado.
        val previas = _state.value.messages.filter { it.role == ChatRole.ASSISTANT && it.text.isNotBlank() }
            .takeLast(6).map { it.text }
        val contextBlock = contextoBase + Repeticion.aviso(previas)
        val turns = _state.value.messages
            .filter { it.text.isNotBlank() && !it.isStreaming }
            .takeLast(16)   // mas memoria de la charla para poder retomar temas
            .map { GeminiTurn(fromUser = it.role == ChatRole.USER, text = it.text) }
        if (turns.isEmpty()) return listOf(GeminiTurn(true, contextBlock))

        // El contexto anonimo se adjunta al ultimo mensaje del usuario.
        val lastUserIndex = turns.indexOfLast { it.fromUser }
        return if (lastUserIndex >= 0) {
            turns.mapIndexed { index, turn ->
                if (index == lastUserIndex) {
                    turn.copy(text = "${turn.text}\n\n$contextBlock")
                } else {
                    turn
                }
            }
        } else {
            turns + GeminiTurn(true, contextBlock)
        }
    }

    private fun addUserMessage(text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = newId(), role = ChatRole.USER, text = text
            )
        )
        guardarChat()
    }

    private fun addAssistantMessage(text: String, notice: String? = null) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = newId(), role = ChatRole.ASSISTANT, text = text, notice = notice
            )
        )
        guardarChat()
    }

    private fun updateMessage(id: String, text: String, streaming: Boolean) {
        // 0.0.6: las notas de memoria ([RECUERDA: ...]) nunca se muestran.
        val visible = MemoriaPanda.sinEtiquetas(text)
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(text = visible, isStreaming = streaming) else it
            }
        )
        if (!streaming) guardarChat()
    }

    private fun guardarChat() {
        runCatching { MemoriaPanda.guardarChat(getApplication(), _state.value.messages) }
    }

    /** 0.0.6: borra lo que Panda recuerda del usuario. */
    fun forgetMemories() {
        MemoriaPanda.borrarRecuerdos(getApplication())
        _state.value = _state.value.copy(memories = 0)
        addAssistantMessage(str(com.pandafit.app.R.string.ai_msg_forgot))
    }

    /** 0.1.7: texto traducible desde el ViewModel. */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun welcomeMessage(): String = buildString {
        appendLine(str(com.pandafit.app.R.string.ai_welcome))
        appendLine()
        val proveedor = _state.value.provider
        appendLine(
            if (prefs.hasApiKey(proveedor)) str(com.pandafit.app.R.string.ai_welcome_key, proveedor.displayName)
            else str(com.pandafit.app.R.string.ai_welcome_nokey)
        )
    }

    private suspend fun resolveModelName() {
        val proveedor = _state.value.provider
        if (!prefs.hasApiKey(proveedor)) return
        runCatching {
            val guardado = prefs.getModel(proveedor)
            val modelo = withContext(Dispatchers.IO) {
                if (guardado.isNotBlank()) guardado else cliente.resolveModel(false)
            }
            if (_state.value.provider == proveedor) {
                _state.value = _state.value.copy(
                    modelName = modelo,
                    models = (listOf(modelo) + proveedor.suggestedModels).distinct()
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Limpia la conversacion (privacidad). */
    fun clearChat() {
        _state.value = _state.value.copy(messages = emptyList(), lastPlan = null)
        addAssistantMessage(welcomeMessage())
    }

    private fun newId(): String = "msg_" + System.currentTimeMillis() + "_" + (0..999).random()

    override fun onCleared() {
        voz.cerrar()
        super.onCleared()
    }

    companion object {
        private const val TAG = "PandaFitAI"
    }
}
