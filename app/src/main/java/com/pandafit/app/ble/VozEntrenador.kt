// ============================================================================
//  Panda Fit - VozEntrenador.kt
//  v26: voz del entrenador durante el entreno (funciona con la pantalla
//  apagada porque vive en el servicio). Usa la mejor voz en espanol instalada
//  y BAJA la musica mientras habla (foco de audio "transitorio con ducking").
// ============================================================================
package com.pandafit.app.ble

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VozEntrenador(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var listo = false
    private var pendientes = mutableListOf<String>()

    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(atributos)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            configurar()
            listo = true
            pendientes.forEach { decir(it) }
            pendientes.clear()
        } else Log.w(TAG, "TTS no disponible ($status)")
    }

    private fun configurar() {
        runCatching {
            tts.setAudioAttributes(atributos)
            // 0.1.7: voz en el idioma del telefono (antes siempre espanol).
            val idioma = com.pandafit.app.ai.Idioma.actual()
            val locales = tts.voices.orEmpty()
                .filter { it.locale.language == idioma && !it.isNetworkConnectionRequired }
            val mejor = locales.maxByOrNull { it.quality } ?: tts.voices.orEmpty()
                .filter { it.locale.language == idioma }.maxByOrNull { it.quality }
            if (mejor != null) tts.voice = mejor else tts.language = Locale.forLanguageTag(idioma)
            tts.setSpeechRate(1.02f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { soltarFoco() }
                @Deprecated("API antigua") override fun onError(utteranceId: String?) { soltarFoco() }
            })
        }
    }

    fun decir(texto: String) {
        if (!listo) { pendientes += texto; return }
        runCatching {
            audio?.requestAudioFocus(foco)
            tts.speak(texto, TextToSpeech.QUEUE_ADD, null, "coach_${System.nanoTime()}")
        }.onFailure { Log.w(TAG, "No se pudo hablar: ${it.message}") }
    }

    private fun soltarFoco() {
        if (!tts.isSpeaking) runCatching { audio?.abandonAudioFocusRequest(foco) }
    }

    fun cerrar() {
        runCatching { tts.stop(); tts.shutdown(); audio?.abandonAudioFocusRequest(foco) }
    }

    companion object { private const val TAG = "VozEntrenador" }
}
