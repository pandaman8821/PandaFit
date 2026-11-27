// ============================================================================
//  Panda Fit - ModelosTest.kt
//  0.0.8 / 0.0.10: selector de modelos con los nombres oficiales actuales.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.AiJson
import com.pandafit.app.ai.AiProvider
import com.pandafit.app.ai.AiRequest
import com.pandafit.app.ai.GeminiTurn
import com.pandafit.app.ai.ModelosChat
import com.pandafit.app.ai.Red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelosTest {

    @Test
    fun `gemini solo modelos de chat actuales, sugeridos primero`() {
        val ids = listOf(
            "models/gemini-3.8-flash-tts", "models/text-embedding-004", "models/gemini-2.5-pro",
            "models/gemini-4-carbon", "models/gemini-3.8-flash", "models/imagen-3.0",
            "models/gemini-3.5-flash-lite", "models/gemini-2.0-flash", "models/gemini-2.5-flash"
        )
        val r = ModelosChat.filtrar(AiProvider.GEMINI, ids)
        assertEquals(listOf("gemini-3.8-flash", "gemini-4-carbon", "gemini-3.5-flash-lite", "gemini-2.5-flash"), r)
        assertFalse(r.any { it.contains("tts") || it.contains("embedding") })
        assertFalse("gemini-2.5-pro" in r)        // retirado
    }

    @Test
    fun `openai fuera audio, imagen y obsoletos`() {
        val r = ModelosChat.filtrar(
            AiProvider.OPENAI,
            listOf("gpt-6.2-sol", "gpt-4o-mini-tts", "whisper-1", "gpt-image-2.6-flare", "gpt-realtime-2.1",
                "gpt-6.2-astra", "gpt-6-cyber", "gpt-4o-mini", "o4-mini")
        )
        assertEquals(listOf("gpt-6.2-sol", "gpt-6.2-astra"), r)
    }

    @Test
    fun `los modelos retirados se cambian solos`() {
        assertEquals("deepseek-flash", AiProvider.actualizarModelo("deepseek-chat"))
        assertEquals("claude-sonnet-5-5", AiProvider.actualizarModelo("claude-sonnet-4-5"))
        assertEquals("gemini-3.8-flash", AiProvider.actualizarModelo("gemini-2.5-pro"))
        assertEquals("gemini-2.5-flash", AiProvider.actualizarModelo("gemini-2.5-flash"))   // aun funciona
        assertEquals("gemini-3.8-flash", AiProvider.GEMINI.defaultModel)
        assertEquals("deepseek-flash", AiProvider.DEEPSEEK.defaultModel)
    }

    @Test
    fun `peticion openai segun el modelo`() {
        val req = AiRequest("sys", listOf(GeminiTurn(true, "hola")), temperature = 0.8f, maxOutputTokens = 3500)
        val nuevo = AiJson.openAiPayload(req, "gpt-6.2-sol", stream = true)
        assertTrue(nuevo.contains("max_completion_tokens") && !nuevo.contains("temperature"))
        val ds = AiJson.openAiPayload(req, "deepseek-flash", stream = true)
        assertTrue(ds.contains("\"max_tokens\":8192") && ds.contains("temperature"))
        assertEquals("Hola", AiJson.extractResponsesText("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hola\"}]}]}"))
    }

    @Test
    fun `modo rapido pide razonar poco`() {
        val req = AiRequest("sys", listOf(GeminiTurn(true, "hola")), maxOutputTokens = 3500, rapido = true)
        assertTrue(AiJson.openAiPayload(req, "deepseek-flash", true).contains("\"reasoning_effort\":\"low\""))
        assertTrue(AiJson.openAiPayload(req, "gpt-6.2-sol", true).contains("\"reasoning_effort\":\"low\""))
        assertFalse(AiJson.openAiPayload(req.copy(rapido = false), "gpt-6.2-sol", true).contains("reasoning_effort"))
    }

    @Test
    fun `deepseek pensando no escribe null`() {
        // Trozo de razonamiento: content = null, reasoning_content con texto.
        val pensando = "{\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"Analizo los datos\"}}]}"
        assertEquals(null, AiJson.extractOpenAiDelta(pensando))
        val texto = "{\"choices\":[{\"delta\":{\"content\":\"Mira, \",\"reasoning_content\":null}}]}"
        assertEquals("Mira, ", AiJson.extractOpenAiDelta(texto))
        assertEquals("Mira, te hago balance", com.pandafit.app.ai.MemoriaPanda.sinNull("nullnullnullMira, te hago balance"))
        assertEquals("El valor null no aparece", com.pandafit.app.ai.MemoriaPanda.sinNull("El valor null no aparece"))
    }

    @Test
    fun `descripciones para elegir`() {
        assertEquals("Rápido y económico", ModelosChat.descripcion("gemini-3.5-flash-lite"))
        assertEquals("Rápido y listo (recomendado)", ModelosChat.descripcion("gemini-3.8-flash"))
        assertEquals("Equilibrado, el de diario (recomendado)", ModelosChat.descripcion("gpt-6.2-sol"))
    }

    @Test
    fun `el error de cuota del modelo se explica`() {
        val e = com.pandafit.app.ai.AiError.RateLimited(
            "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-2.5-pro\"}}"
        )
        assertTrue(e.userMessage.contains("no tiene cuota para este modelo"))
        assertTrue(com.pandafit.app.ai.AiError.Server(404, "models/x is not found").userMessage.contains("no está disponible"))
    }

    @Test
    fun `el limite de uso se reconoce`() {
        assertEquals(Red.Fallo.CUOTA, Red.clasificar(Exception("HTTP 429 RESOURCE_EXHAUSTED"), true))
    }
}
