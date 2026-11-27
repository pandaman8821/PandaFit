// ============================================================================
//  Panda Fit - VozPanda.kt
//  VOZ DEL ASISTENTE.
//    * VOZ NATURAL (si hay clave de Gemini u OpenAI): la genera la IA del
//      usuario y suena humana.
//      0.0.8: UNA sola peticion por respuesta (antes una por trozo: lento y
//      gastaba enseguida el limite gratuito, y al llegar al limite cambiaba a
//      la voz del telefono a mitad de lectura). El audio se reproduce MIENTRAS
//      llega (streaming), asi empieza a sonar mucho antes.
//    * VOZ DEL TELEFONO (respaldo): la mejor voz en espanol instalada, frase a
//      frase y sin recortar. Si la voz natural no puede empezar, se lee TODO
//      con la del telefono (nunca se mezclan las dos voces en una respuesta).
//    * Baja el volumen de la musica mientras habla.
// ============================================================================
package com.pandafit.app.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.coroutineContext

class VozPanda(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Clave del proveedor (null si no hay). Se lee en el momento de hablar. */
    private val clave: (AiProvider) -> String?,
    private val onHablando: (Boolean) -> Unit
) {
    enum class Estilo { FEMENINA, MASCULINA }

    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var trabajo: Job? = null
    @Volatile private var pista: AudioTrack? = null
    /** Si la voz natural fallo por clave/cuota, se usa la del telefono el resto de la sesion. */
    @Volatile private var naturalRota = false
    /** 0.1.2: hasta cuando esperar por el limite POR MINUTO de la voz natural. */
    @Volatile private var pausaHasta = 0L
    /** Ultimo motivo por el que no se uso la voz natural (para la interfaz). */
    @Volatile var ultimoAviso: String? = null; private set

    private val audio = context.getSystemService(AudioManager::class.java)
    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(atributos).build()

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsListo = status == TextToSpeech.SUCCESS
                if (ttsListo) configurarVozTelefono()
            }
        }.onFailure { Log.w(TAG, "TTS no disponible: ${it.message}") }
    }

    /** Mejor voz en espanol del telefono (calidad primero, luego acento cercano). */
    private fun configurarVozTelefono() {
        val motor = tts ?: return
        runCatching { motor.setAudioAttributes(atributos) }
        val pais = Locale.getDefault().country.uppercase(Locale.ROOT)
        val latinos = setOf("US", "MX", "DO", "CO", "AR", "CL", "PE", "VE", "PR", "419", "")
        val mejor = runCatching { motor.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == Idioma.actual() }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .maxByOrNull { v ->
                val region = v.locale.country.uppercase(Locale.ROOT)
                val acento = when {
                    pais.isNotEmpty() && region == pais -> 3
                    region in latinos -> 2
                    region == "ES" -> 1
                    else -> 0
                }
                v.quality * 100 + acento * 10 + (if (v.isNetworkConnectionRequired) 1 else 0)
            }
        if (mejor != null) motor.voice = mejor else motor.language = Locale.forLanguageTag(Idioma.actual())
        motor.setSpeechRate(1.0f)
        motor.setPitch(1.0f)
        motor.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith("_fin") == true) terminar() }
            @Deprecated("API antigua") override fun onError(utteranceId: String?) { if (utteranceId?.endsWith("_fin") == true) terminar() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {}
        })
    }

    /** Habla el texto entero. [natural] = intentar la voz de IA. */
    fun hablar(texto: String, natural: Boolean, proveedor: AiProvider, estilo: Estilo) {
        parar()
        ultimoAviso = null
        val frases = TextoHablado.frases(texto, idioma = Idioma.actual())
        if (frases.isEmpty()) return
        onHablando(true)
        runCatching { audio?.requestAudioFocus(foco) }
        val enPausa = System.currentTimeMillis() < pausaHasta
        val origen = if (natural && !naturalRota && !enPausa) elegirOrigen(proveedor) else null
        if (origen == null) { telefono(frases); return }
        val limpio = frases.joinToString(" ")
        trabajo = scope.launch(Dispatchers.IO) {
            // Textos enormes se parten en pocas partes grandes (max ~3500 caracteres).
            val partes = TextoHablado.bloques(frases, primero = 3500, max = 3500)
            var sonoAlgo = false
            try {
                for (parte in partes) {
                    ensureActive()
                    when (origen) {
                        Origen.GEMINI -> geminiStream(parte, estilo) { sonoAlgo = true }
                        Origen.OPENAI -> openaiStream(parte, estilo) { sonoAlgo = true }
                    }
                }
                withContext(Dispatchers.Main) { terminar() }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val fallo = Red.clasificar(context, t)
                val cuerpo = (t as? HttpError)?.detalle.orEmpty()
                if (fallo == Red.Fallo.CUOTA && !Limite.esDiario(cuerpo)) {
                    // 0.1.2: limite POR MINUTO: la voz natural se pausa solo lo que pide
                    // Google (o 60 s) y vuelve sola; sin aviso rojo, solo una nota breve.
                    val espera = (Limite.segundosDeEspera(cuerpo) ?: 60).coerceIn(10, 300)
                    pausaHasta = System.currentTimeMillis() + espera * 1000L
                    ultimoAviso = context.getString(com.pandafit.app.R.string.voice_limit_minute, espera)
                } else {
                    naturalRota = fallo == Red.Fallo.SERVICIO || fallo == Red.Fallo.CUOTA
                    ultimoAviso = if (fallo == Red.Fallo.CUOTA) context.getString(com.pandafit.app.R.string.voice_limit_day)
                    else Red.mensaje(context, fallo, if (sonoAlgo) com.pandafit.app.R.string.net_what_voice_cut else com.pandafit.app.R.string.net_what_phone_voice)
                }
                Log.w(TAG, "Voz natural fallo ($fallo): ${t.message}")
                liberarPista()
                withContext(Dispatchers.Main) {
                    // Si no llego a sonar nada, se lee TODO con la voz del telefono
                    // (una sola voz en toda la respuesta).
                    if (!sonoAlgo) telefono(TextoHablado.frases(limpio, idioma = Idioma.actual())) else terminar()
                }
            }
        }
    }

    /** Prueba rapida (boton "Probar voz"): permite reintentar la natural. */
    fun probar(natural: Boolean, proveedor: AiProvider, estilo: Estilo) {
        naturalRota = false
        hablar(context.getString(com.pandafit.app.R.string.voice_test_phrase), natural, proveedor, estilo)
    }

    fun parar() {
        trabajo?.cancel(); trabajo = null
        liberarPista()
        runCatching { tts?.stop() }
        terminar()
    }

    fun cerrar() {
        parar()
        runCatching { tts?.shutdown() }
    }

    private fun terminar() {
        runCatching { audio?.abandonAudioFocusRequest(foco) }
        onHablando(false)
    }

    private fun liberarPista() {
        val p = pista ?: return
        pista = null
        runCatching { p.pause(); p.flush(); p.stop() }
        runCatching { p.release() }
    }

    // ------------------------------------------------------- VOZ DEL TELEFONO
    private fun telefono(frases: List<String>) {
        val motor = tts
        if (!ttsListo || motor == null || frases.isEmpty()) { terminar(); return }
        val base = System.nanoTime()
        frases.forEachIndexed { i, f ->
            val id = "panda_${base}_$i" + if (i == frases.lastIndex) "_fin" else ""
            motor.speak(f, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    // ---------------------------------------------------------- VOZ NATURAL
    private enum class Origen { GEMINI, OPENAI }

    /** Usa el proveedor activo si tiene voz; si no, cualquier clave guardada que la tenga. */
    private fun elegirOrigen(activo: AiProvider): Origen? {
        val orden = when (activo) {
            AiProvider.OPENAI -> listOf(AiProvider.OPENAI, AiProvider.GEMINI)
            else -> listOf(AiProvider.GEMINI, AiProvider.OPENAI)
        }
        val p = orden.firstOrNull { !clave(it).isNullOrBlank() }
        if (p == null) ultimoAviso = context.getString(com.pandafit.app.R.string.voice_need_key)
        return when (p) {
            AiProvider.GEMINI -> Origen.GEMINI
            AiProvider.OPENAI -> Origen.OPENAI
            else -> null
        }
    }

    private fun abrir(url: String, cuerpo: String, cabeceras: Map<String, String>): HttpURLConnection {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 45_000
            setRequestProperty("Content-Type", "application/json")
            cabeceras.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        con.outputStream.use { it.write(cuerpo.toByteArray()) }
        val code = con.responseCode
        if (code !in 200..299) {
            val err = con.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(2000)
            con.disconnect()
            throw HttpError(code, err)
        }
        return con
    }

    class HttpError(val code: Int, val detalle: String) : Exception("HTTP $code $detalle")

    /** Reproduce PCM 16 bits mono mientras llega. */
    private inner class Reproductor(hz: Int) {
        private val min = AudioTrack.getMinBufferSize(hz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(atributos)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(hz).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 4, hz)) // ~0,5 s de colchon
            .build().also { pista = it }
        private var resto: Byte? = null
        var bytes = 0L; private set
        private var empezo = false

        fun escribir(datos: ByteArray, alSonar: () -> Unit) {
            var d = datos
            resto?.let { d = byteArrayOf(it) + d; resto = null }
            if (d.size % 2 == 1) { resto = d.last(); d = d.copyOf(d.size - 1) }
            if (d.isEmpty()) return
            if (!empezo) { track.play(); empezo = true; alSonar() }
            track.write(d, 0, d.size)
            bytes += d.size
        }

        /** Espera a que suene lo que queda en el colchon y libera. */
        suspend fun terminar() {
            val frames = bytes / 2
            var espera = 0
            while (empezo && track.playbackHeadPosition < frames && espera < 15_000) {
                coroutineContext.ensureActive()
                delay(60); espera += 60
            }
            liberarPista()
        }
    }

    /** Gemini TTS en streaming (SSE). Si el modelo no lo admite, peticion normal. */
    private suspend fun geminiStream(texto: String, estilo: Estilo, alSonar: () -> Unit) {
        val key = clave(AiProvider.GEMINI) ?: error("sin clave")
        val voz = if (estilo == Estilo.MASCULINA) "Puck" else "Kore"
        val cuerpo = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            // 0.1.1: SOLO el texto. Antes iba delante "Lee en español latino,
                            // con voz cálida..." y las voces nuevas lo leian en voz alta.
                            // La calidez la da la voz elegida (Puck/Kore); el idioma lo
                            // detecta el modelo solo.
                            put("text", texto)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voz) } }
                }
            }
        }.toString()
        var ultimo: Throwable? = null
        // 0.1.2: primero la combinacion (modelo + modo) que funciono la ultima vez:
        // asi cada lectura gasta UNA sola peticion del limite gratuito (antes se
        // probaban varias y la segunda lectura chocaba con el limite por minuto).
        val prefsVoz = context.getSharedPreferences("panda_voz", Context.MODE_PRIVATE)
        val todas = MODELOS_GEMINI_TTS.flatMap { m -> listOf(m to true, m to false) }
        val buena = prefsVoz.getString("gemini_ok", null)?.split("|")?.takeIf { it.size == 2 }
            ?.let { it[0] to (it[1] == "1") }
        val orden = if (buena != null && buena in todas) listOf(buena) + (todas - buena) else todas
        for ((modelo, stream) in orden) {
                try {
                    val rep = Reproductor(24_000)
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelo:" +
                        if (stream) "streamGenerateContent?alt=sse" else "generateContent"
                    val con = abrir(url, cuerpo, mapOf("x-goog-api-key" to key))
                    if (stream) {
                        con.inputStream.bufferedReader().useLines { lineas ->
                            for (l in lineas) {
                                coroutineContext.ensureActive()
                                if (!l.startsWith("data:")) continue
                                audioDeGemini(l.removePrefix("data:").trim()).forEach { rep.escribir(it, alSonar) }
                            }
                        }
                    } else {
                        val texto = con.inputStream.bufferedReader().use { it.readText() }
                        audioDeGemini(texto).forEach { rep.escribir(it, alSonar) }
                    }
                    con.disconnect()
                    if (rep.bytes == 0L) { liberarPista(); ultimo = IllegalStateException("respuesta sin audio"); continue }
                    prefsVoz.edit().putString("gemini_ok", "$modelo|${if (stream) "1" else "0"}").apply()
                    rep.terminar()
                    return
                } catch (e: HttpError) {
                    liberarPista()
                    ultimo = e
                    // Modo/modelo no disponible: se prueba otro. Si es el LIMITE (429),
                    // se para: seguir probando solo gastaria mas cuota.
                    if (e.code == 404 || e.code == 400) {
                        if (buena == (modelo to stream)) prefsVoz.edit().remove("gemini_ok").apply()
                        continue
                    }
                    throw e
                }
        }
        throw ultimo ?: IllegalStateException("sin modelo de voz")
    }

    private fun audioDeGemini(json: String): List<ByteArray> = runCatching {
        Json.parseToJsonElement(json).jsonObject["candidates"]?.jsonArray?.flatMap { c ->
            c.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty().mapNotNull { p ->
                (p as? JsonObject)?.get("inlineData")?.jsonObject?.get("data")?.jsonPrimitive?.content
                    ?.let { Base64.decode(it, Base64.DEFAULT) }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** OpenAI TTS en streaming: PCM 24 kHz que se reproduce mientras llega. */
    private suspend fun openaiStream(texto: String, estilo: Estilo, alSonar: () -> Unit) {
        val key = clave(AiProvider.OPENAI) ?: error("sin clave")
        val voz = if (estilo == Estilo.MASCULINA) "ash" else "coral"
        fun cuerpo(modelo: String) = buildJsonObject {
            put("model", modelo); put("voice", voz); put("input", texto); put("response_format", "pcm")
            if (modelo != "tts-1") put("instructions", "Habla en español latino, cálido, cercano y natural, como un entrenador amigo. Ritmo tranquilo.")
        }.toString()
        val con = try {
            abrir("https://api.openai.com/v1/audio/speech", cuerpo("gpt-4o-mini-tts"), mapOf("Authorization" to "Bearer $key"))
        } catch (e: HttpError) {
            if (e.code != 404 && e.code != 400) throw e
            abrir("https://api.openai.com/v1/audio/speech", cuerpo("tts-1"), mapOf("Authorization" to "Bearer $key"))
        }
        val rep = Reproductor(24_000)
        con.inputStream.use { leerEnTrozos(it) { trozo -> rep.escribir(trozo, alSonar) } }
        con.disconnect()
        if (rep.bytes == 0L) { liberarPista(); error("respuesta sin audio") }
        rep.terminar()
    }

    private suspend fun leerEnTrozos(entrada: InputStream, cada: (ByteArray) -> Unit) {
        val buf = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val n = entrada.read(buf)
            if (n < 0) break
            if (n > 0) cada(buf.copyOf(n))
        }
    }

    companion object {
        private const val TAG = "VozPanda"
        // 0.0.10: voces actuales de Google (las 2.5 se apagan el 18-dic-2026).
        private val MODELOS_GEMINI_TTS = listOf(
            "gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-2.5-flash-preview-tts"
        )

        /** Cabecera WAV para PCM 16 bits mono. Funcion pura. */
        fun wav(pcm: ByteArray, hz: Int): ByteArray {
            val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
            b.putInt(hz); b.putInt(hz * 2); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
            return b.array()
        }
    }
}
