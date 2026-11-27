// ============================================================================
//  Panda Fit - AIAssistantScreen.kt
//  PANTALLA DEL ASISTENTE IA FITNESS (nueva funcion obligatoria).
//
//  Incluye:
//    * Chat en tiempo real con Gemini (streaming) y motor local de respaldo.
//    * Acciones rapidas: plan de entrenamiento, progreso, motivacion, sueno,
//      comidas y explicacion del pulso.
//    * Tarjeta visual del PLAN DE ENTRENAMIENTO generado (dias y ejercicios).
//    * MODO VOZ con TextToSpeech (lee las respuestas en espanol).
//    * Configuracion SEGURA de la API Key de Gemini (cifrada, enmascarada) y
//      aviso de privacidad: al modelo solo viajan datos agregados y anonimos.
//    * Todo con Liquid Glass: burbujas de cristal, glow que respira, transiciones.
// ============================================================================
package com.pandafit.app.ai

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.platform.LocalContext
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pandafit.app.R
import com.pandafit.app.data.model.ChatMessage
import com.pandafit.app.data.model.ChatRole
import com.pandafit.app.data.model.TrainingDay
import com.pandafit.app.data.model.TrainingPlan
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlassPill
import com.pandafit.app.ui.components.PandaGlassTextField
import com.pandafit.app.ui.components.PandaIconBubble
import com.pandafit.app.ui.components.PandaPrivacyBanner
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.ui.theme.liquidAura
import com.pandafit.app.ui.theme.liquidGlass

@Composable
fun AIAssistantScreen(
    onBack: () -> Unit,
    viewModel: AIViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sentContext by viewModel.sentContext.collectAsStateWithLifecycle()
    val tokens = PandaFitTheme.colors
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(!state.hasApiKey) }
    var showContext by remember { mutableStateOf(false) }
    val contexto = LocalContext.current
    val dictadoPrompt = stringResource(R.string.ai_dictate_prompt)
    // Dictado: el texto reconocido se anade a lo que ya hubiera escrito.
    val dictado = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val texto = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!texto.isNullOrBlank()) {
            val previo = state.input.trim()
            viewModel.onInputChange(if (previo.isEmpty()) texto else "$previo $texto")
        }
    }

    // Auto-scroll al ultimo mensaje (chat fluido).
    LaunchedEffect(state.messages.size, state.isThinking) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .liquidAura(MaterialTheme.colorScheme.tertiary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // ------------------------------------------------------- CABECERA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(6.dp))
                // v15: ficha plana con el icono de IA animado (antes un altavoz).
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    PandaGlyphIcon(
                        glyph = PandaGlyph.IA,
                        accent = MaterialTheme.colorScheme.surface,
                        principal = MaterialTheme.colorScheme.tertiary,
                        size = 30.dp,
                        animado = state.isThinking || state.messages.lastOrNull()?.isStreaming == true
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            state.isThinking -> stringResource(R.string.ai_thinking)
                            state.usedLocalEngine -> stringResource(R.string.ai_local_engine)
                            state.hasApiKey -> "IA · ${state.modelName}"
                            else -> stringResource(R.string.ai_no_key_short)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Modo voz
                VoiceButton(
                    enabled = state.voiceEnabled,
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = { viewModel.toggleVoice() }
                )
                Spacer(Modifier.width(4.dp))
                // Ajustes
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable { showSettings = !showSettings },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.ai_settings),
                        tint = if (showSettings) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp)
                    )
                }
                // Limpiar chat
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.clearChat() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.ai_clear_chat),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ------------------------------------------- AJUSTES / API KEY
            // 0.1.1: el menu se puede DESPLAZAR y ocupa casi toda la pantalla mientras
            // esta abierto (antes crecia sin limite y la parte de abajo quedaba cortada).
            AnimatedVisibility(
                visible = showSettings, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.weight(6f, fill = false)
            ) {
              Column(
                  modifier = Modifier
                      .verticalScroll(androidx.compose.foundation.rememberScrollState())
                      .padding(bottom = 12.dp)
              ) {
                // 0.0.8: al abrir los ajustes se carga la lista real de modelos.
                LaunchedEffect(state.provider, state.hasApiKey) { viewModel.loadModels() }
                ApiKeyPanel(
                    provider = state.provider,
                    hasApiKey = state.hasApiKey,
                    maskedKey = state.maskedApiKey,
                    modelName = state.modelName,
                    models = state.models,
                    modelsLoading = state.modelsLoading,
                    modelsFromAccount = state.modelsFromAccount,
                    onSelectModel = { viewModel.selectModel(it) },
                    onReloadModels = { viewModel.loadModels() },
                    onSelectProvider = { viewModel.selectProvider(it) },
                    onSave = { key -> viewModel.saveApiKey(key) },
                    onValidate = { viewModel.validateApiKey() },
                    onClear = { viewModel.clearApiKey() },
                    onClose = { showSettings = false }
                )
                // 0.0.6: voz natural de IA y memoria de Panda.
                VozYMemoriaPanel(
                    fastMode = state.fastMode,
                    onFastMode = { viewModel.setFastMode(it) },
                    naturalVoice = state.naturalVoice,
                    voiceStyle = state.voiceStyle,
                    memories = state.memories,
                    onNatural = { viewModel.setNaturalVoice(it) },
                    onStyle = { viewModel.setVoiceStyle(it) },
                    onTest = { viewModel.testVoice() },
                    onForget = { viewModel.forgetMemories() }
                )
              }
            }

            // ---------------------------------------------- AVISO DE ERROR
            state.error?.let { error ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
                        .clickable { viewModel.clearError() }
                        .padding(12.dp)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ------------------------------------------------------- CHAT
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatBubble(message = message, onSpeak = { viewModel.listen(it) })
                }
                if (state.isThinking) {
                    item { ThinkingBubble() }
                }
                // Conversacion recien empezada (solo el saludo): propuestas grandes.
                if (state.messages.size <= 1 && !state.isThinking) {
                    item { PropuestasIniciales(onIntent = { viewModel.runIntent(it) }) }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }

            // ------------------------------------------------ CONTEXTO ENVIADO
            AnimatedVisibility(visible = showContext && sentContext.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(tokens.glassTop.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            tokens.glassRim.copy(alpha = 0.22f),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.ai_context_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = sentContext,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // -------------------------------------------------- ACCIONES RAPIDAS
            // (con la conversacion vacia ya se ven en grande dentro del chat)
            if (state.messages.size > 1) LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(AiIntent.entries.toList()) { intent ->
                    ChipPropuesta(
                        texto = stringResource(intent.labelRes),
                        glyph = glyphDe(intent),
                        destacado = intent.wantsPlan,
                        onClick = { viewModel.runIntent(intent) }
                    )
                }
                item {
                    PandaGlassPill(
                        text = if (showContext) stringResource(R.string.ai_hide_context) else stringResource(R.string.ai_show_context),
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = { showContext = !showContext }
                    )
                }
            }

            // ------------------------------------------------------ ENTRADA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PandaGlassTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.ai_input_hint),
                    onImeAction = { viewModel.send() },
                    trailing = {
                        // Dictado por voz (reconocimiento de Android, sin permisos).
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.ai_dictate),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val intento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, dictadoPrompt)
                                    }
                                    try {
                                        dictado.launch(intento)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(contexto, R.string.ai_dictate_unavailable, Toast.LENGTH_SHORT).show()
                                    }
                                }
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                val puedeEnviar = !state.isThinking && state.input.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (puedeEnviar) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable(enabled = !state.isThinking) { viewModel.send() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.cd_send),
                        tint = if (puedeEnviar) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ============================================================================
//  BURBUJA DE CHAT
// ============================================================================
@Composable
private fun ChatBubble(message: ChatMessage, onSpeak: (String) -> Unit) {
    val tokens = PandaFitTheme.colors
    val fromUser = message.role == ChatRole.USER
    val accent = if (fromUser) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.tertiary

    // v15: TUS mensajes = burbuja lima solida a la derecha (texto oscuro).
    if (fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                    .background(accent)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        return
    }

    // Respuestas de la IA: avatar + tarjeta grafito a la izquierda.
    val ia = MaterialTheme.colorScheme.tertiary
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(ia.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            PandaGlyphIcon(
                glyph = PandaGlyph.IA,
                accent = MaterialTheme.colorScheme.surface,
                principal = ia,
                size = 22.dp,
                animado = message.isStreaming
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                )
                .padding(14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ai_from_panda),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ia
                    )
                    Spacer(Modifier.weight(1f))
                    if (!message.isStreaming) {
                        // 0.1.4: boton visible "Escuchar": lee ESTE mensaje con la voz
                        // natural de IA (las lecturas automaticas usan la del telefono).
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ia.copy(alpha = 0.14f))
                                .clickable { onSpeak(message.text) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.cd_listen_answer),
                                tint = ia,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.ai_listen), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ia)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (message.isStreaming && message.text.isEmpty()) {
                    StreamingDots(accent = ia)
                } else {
                    MarkdownText(text = message.text)
                }
                message.notice?.let { notice ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(tokens.glassTop.copy(alpha = 0.10f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(text = "\u2139\uFE0F", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                message.plan?.let { plan ->
                    Spacer(Modifier.height(10.dp))
                    PlanCard(plan)
                }
            }
        }
    }
}

/** Icono propio de cada propuesta rapida. */
private fun glyphDe(intent: AiIntent): PandaGlyph = when (intent) {
    AiIntent.TRAINING_PLAN -> PandaGlyph.ENTRENAR
    AiIntent.PROGRESS -> PandaGlyph.CURVA
    AiIntent.MOTIVATION -> PandaGlyph.EJERCICIO
    AiIntent.SLEEP -> PandaGlyph.SUENO
    AiIntent.NUTRITION -> PandaGlyph.SALUD
    AiIntent.HEART -> PandaGlyph.PULSO
}

/** Chip de propuesta rapida: icono + texto; el plan va destacado en violeta. */
@Composable
private fun ChipPropuesta(texto: String, glyph: PandaGlyph, destacado: Boolean, onClick: () -> Unit) {
    val color = if (destacado) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (destacado) 0.18f else 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PandaGlyphIcon(glyph = glyph, accent = MaterialTheme.colorScheme.surface, principal = color, size = 20.dp, animado = false)
        Spacer(Modifier.width(6.dp))
        Text(texto, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** Rejilla de propuestas grandes cuando la conversacion esta vacia. */
@Composable
private fun PropuestasIniciales(onIntent: (AiIntent) -> Unit) {
    val intents = AiIntent.entries.toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.ai_suggestions_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp, top = 4.dp)
        )
        intents.chunked(2).forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 40.dp)) {
                fila.forEach { intent ->
                    val color = if (intent.wantsPlan) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .clickable { onIntent(intent) }
                            .padding(12.dp)
                    ) {
                        PandaGlyphIcon(glyph = glyphDe(intent), accent = MaterialTheme.colorScheme.surface, principal = color, size = 28.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(intent.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2
                        )
                    }
                }
                if (fila.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Render sencillo de markdown (**negrita**, titulos, listas) para el chat. */
@Composable
private fun MarkdownText(text: String) {
    val body = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val annotated = remember(text, body, accent) {
        buildAnnotatedString {
            val lines = text.lines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> { /* linea en blanco */ }
                    trimmed.startsWith("###") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                            append(trimmed.removePrefix("###").trim())
                        }
                    }
                    trimmed.startsWith("##") || trimmed.startsWith("#") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                            append(trimmed.trimStart('#').trim())
                        }
                    }
                    else -> {
                        // Convierte **negrita** en estilo real.
                        var rest = trimmed
                        while (rest.contains("**")) {
                            val start = rest.indexOf("**")
                            val end = rest.indexOf("**", start + 2)
                            if (end < 0) break
                            append(rest.substring(0, start))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = body)) {
                                append(rest.substring(start + 2, end))
                            }
                            rest = rest.substring(end + 2)
                        }
                        append(rest)
                    }
                }
                if (index != lines.lastIndex) append("\n")
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = body
    )
}

/** Puntos animados mientras Gemini responde. */
@Composable
private fun StreamingDots(accent: Color) {
    val infinite = rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "phase"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val active = phase.toInt() == index
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(if (active) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (active) 1f else 0.45f))
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.ai_typing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Burbuja de "pensando" mientras se prepara la respuesta. */
@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .liquidGlass(cornerRadius = 24.dp, glow = 0.28f, glowAccent = MaterialTheme.colorScheme.tertiary)
                .padding(14.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.ai_from_panda),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(8.dp))
                StreamingDots(accent = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.ai_thinking_body),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================================
//  TARJETA DEL PLAN DE ENTRENAMIENTO
// ============================================================================
@Composable
private fun PlanCard(plan: TrainingPlan) {
    val tokens = PandaFitTheme.colors
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tokens.glassTop.copy(alpha = 0.10f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
                RoundedCornerShape(20.dp)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\uD83C\uDFCB\uFE0F ${plan.title}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "\u25B2" else "\u25BC",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = pluralStringResource(
                R.plurals.ai_plan_summary, plan.daysPerWeek, plan.daysPerWeek,
                plan.weeklyMinutes, plan.level
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = plan.goal,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                plan.days.forEach { day -> PlanDayRow(day) }
                if (plan.rationale.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = plan.rationale,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (plan.source == "gemini") stringResource(R.string.ai_plan_gemini)
                    else stringResource(R.string.ai_plan_local),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlanDayRow(day: TrainingDay) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = day.day,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = day.focus,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (day.totalMinutes > 0) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${day.totalMinutes} min",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        day.exercises.forEach { exercise ->
            val detail = when {
                exercise.sets > 0 && exercise.reps.isNotEmpty() -> "${exercise.sets} x ${exercise.reps}"
                exercise.durationMinutes > 0 -> "${exercise.durationMinutes} min"
                exercise.notes.isNotEmpty() -> exercise.notes
                else -> ""
            }
            Text(
                text = "   \u2022 ${exercise.name}" + if (detail.isNotEmpty()) "  \u2013  $detail" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
//  PANEL DE API KEY (SEGURIDAD)
// ============================================================================
@Composable
private fun ApiKeyPanel(
    provider: AiProvider,
    hasApiKey: Boolean,
    maskedKey: String,
    modelName: String,
    models: List<String>,
    modelsLoading: Boolean,
    modelsFromAccount: Boolean,
    onSelectModel: (String) -> Unit,
    onReloadModels: () -> Unit,
    onSelectProvider: (AiProvider) -> Unit,
    onSave: (String) -> String,
    onValidate: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    PandaGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        glowAccent = MaterialTheme.colorScheme.tertiary,
        glow = 0.32f
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_provider_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.ai_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onClose)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ai_provider_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            // ---- SELECTOR DE PROVEEDOR: Gemini / OpenAI / DeepSeek / Claude ----
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AiProvider.entries.toList()) { opcion ->
                    PandaGlassPill(
                        text = stringResource(opcion.labelRes),
                        selected = opcion == provider,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onClick = { onSelectProvider(opcion) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---- CLAVE DEL PROVEEDOR ACTIVO (cifrada, nunca compartida) --------
            Text(
                text = stringResource(R.string.ai_provider_active) + ": " + provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hasApiKey) {
                    stringResource(R.string.ai_key_stored, maskedKey)
                } else {
                    stringResource(R.string.ai_key_none_provider, provider.displayName)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.ai_key_where,
                    provider.displayName,
                    provider.consoleUrl
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            PandaGlassTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = provider.keyHint + "\u2026",
                shape = RoundedCornerShape(18.dp),
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                onImeAction = {
                    feedback = onSave(keyInput)
                    keyInput = ""
                }
            )
            Spacer(Modifier.height(10.dp))
            // 0.0.8: botones repartidos a partes iguales (antes "Probar clave" salia
            // cortado y "Borrar clave" quedaba fuera de la pantalla) y sin el icono
            // de altavoz, que no tenia nada que ver.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PandaGlassPill(
                    text = stringResource(R.string.ai_key_save_short),
                    selected = true,
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        feedback = onSave(keyInput)
                        keyInput = ""
                    }
                )
                PandaGlassPill(
                    text = stringResource(R.string.ai_key_test),
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onValidate
                )
            }
            if (hasApiKey) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ai_key_delete_link),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }

            // ---- 0.0.8: MODELO DEL PROVEEDOR ----------------------------------
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_model), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        when {
                            !hasApiKey -> stringResource(R.string.ai_model_need_key)
                            modelsLoading -> stringResource(R.string.ai_model_loading)
                            modelsFromAccount -> stringResource(R.string.ai_model_account)
                            else -> stringResource(R.string.ai_model_suggested)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasApiKey && !modelsLoading) {
                    Text(
                        stringResource(R.string.ai_model_refresh),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onReloadModels)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val visibles = (listOf(modelName) + models).distinct().filter { it.isNotBlank() }
            var verTodos by remember(provider) { mutableStateOf(false) }
            val mostrados = if (verTodos) visibles else visibles.take(6)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mostrados.forEach { m ->
                    val sel = m == modelName
                    val acento = MaterialTheme.colorScheme.tertiary
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sel) acento.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, if (sel) acento else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { onSelectModel(m) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = if (sel) acento else MaterialTheme.colorScheme.onSurface, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            val d = ModelosChat.descripcion(m)
                            if (d.isNotEmpty()) Text(d, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (sel) Text(stringResource(R.string.ai_model_in_use), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = acento)
                    }
                }
            }
            if (visibles.size > 6) {
                Text(
                    if (verTodos) stringResource(R.string.ai_model_less) else stringResource(R.string.ai_model_all, visibles.size),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50))
                        .clickable { verTodos = !verTodos }.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
            feedback?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(10.dp))
            PandaPrivacyBanner(text = stringResource(R.string.ai_key_responsibility))
        }
    }
}

// ============================================================================
//  BOTON DE MODO VOZ
// ============================================================================
@Composable
private fun VoiceButton(enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "voice")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (enabled) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "voicePulse"
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (enabled) accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (enabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = if (enabled) stringResource(R.string.ai_voice_toggle_off) else stringResource(R.string.ai_voice_toggle_on),
            tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((20 * pulse).dp)
        )
    }
}


/**
 * 0.0.6: opciones de VOZ (natural de IA o del telefono, estilo y prueba) y
 * MEMORIA de Panda (cuantas cosas recuerda y boton para olvidarlas).
 */
@Composable
private fun VozYMemoriaPanel(
    fastMode: Boolean,
    onFastMode: (Boolean) -> Unit,
    naturalVoice: Boolean,
    voiceStyle: String,
    memories: Int,
    onNatural: (Boolean) -> Unit,
    onStyle: (String) -> Unit,
    onTest: () -> Unit,
    onForget: () -> Unit
) {
    val lima = MaterialTheme.colorScheme.primary
    PandaGlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        cornerRadius = 20.dp, glow = 0f, padding = 14.dp
    ) {
        Column {
            // 0.1.3: velocidad de las respuestas.
            Text(stringResource(R.string.ai_speed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (fastMode) stringResource(R.string.ai_speed_fast_sub) else stringResource(R.string.ai_speed_detailed_sub),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to stringResource(R.string.ai_speed_fast), false to stringResource(R.string.ai_speed_detailed)).forEach { (valor, texto) ->
                    val sel = fastMode == valor
                    Text(
                        texto,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (sel) lima else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) lima.copy(alpha = 0.14f) else Color.Transparent)
                            .border(1.dp, if (sel) lima else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                            .clickable { onFastMode(valor) }
                            .padding(vertical = 9.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_voice_natural), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.ai_voice_natural_sub),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = naturalVoice, onCheckedChange = onNatural,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = lima)
                )
            }
            Spacer(Modifier.height(10.dp))
            // 0.0.8: cada boton en su sitio (antes "Probar voz" se partia en dos lineas
            // y se montaba encima de los otros en pantallas estrechas).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MASCULINA" to stringResource(R.string.ai_voice_male), "FEMENINA" to stringResource(R.string.ai_voice_female)).forEach { (id, texto) ->
                    val sel = voiceStyle == id
                    Text(
                        texto,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (sel) lima else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) lima.copy(alpha = 0.14f) else Color.Transparent)
                            .border(1.dp, if (sel) lima else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                            .clickable { onStyle(id) }
                            .padding(vertical = 9.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ai_voice_test),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(lima)
                    .clickable(onClick = onTest)
                    .padding(vertical = 10.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_memory), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (memories == 0) stringResource(R.string.ai_memory_empty) else stringResource(R.string.ai_memory_count, memories),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (memories > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.ai_memory_forget),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = PandaFitTheme.colors.heart,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, PandaFitTheme.colors.heart, RoundedCornerShape(50))
                            .clickable(onClick = onForget)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
