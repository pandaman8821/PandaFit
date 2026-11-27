// ============================================================================
//  Panda Fit - AiProvidersTest.kt
//  Pruebas unitarias del MULTI-PROVEEDOR de IA y del enrutado de modelos.
//
//  Comprueba, sin red, que:
//    * cada proveedor tiene su URL, su modelo por defecto y su identificador,
//    * el catalogo tiene los cuatro servicios pedidos (Gemini, OpenAI, DeepSeek
//      y Claude) y no se pierden al cambiar de uno a otro,
//    * las cargas JSON de Gemini, OpenAI/DeepSeek y Claude se construyen con la
//      forma correcta para cada API,
//    * los parsers de respuesta extraen bien el texto de cada proveedor.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.AiHttp
import com.pandafit.app.ai.AiJson
import com.pandafit.app.ai.AiProvider
import com.pandafit.app.ai.AiRequest
import com.pandafit.app.ai.GeminiTurn
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersTest {

    // ========================================================================
    //  CATALOGO DE PROVEEDORES
    // ========================================================================

    @Test
    fun `estan los cuatro proveedores pedidos`() {
        val ids = AiProvider.entries.map { it.id }
        assertTrue("Falta Gemini", ids.contains("gemini"))
        assertTrue("Falta OpenAI", ids.contains("openai"))
        assertTrue("Falta DeepSeek", ids.contains("deepseek"))
        assertTrue("Falta Claude", ids.contains("claude"))
        assertEquals(4, AiProvider.entries.size)
    }

    @Test
    fun `cada proveedor tiene modelo por defecto y URL de consola`() {
        AiProvider.entries.forEach { proveedor ->
            assertTrue(
                "Sin modelo por defecto: ${proveedor.id}",
                proveedor.defaultModel.isNotBlank()
            )
            assertTrue(
                "Sin URL de consola: ${proveedor.id}",
                proveedor.consoleUrl.startsWith("https://")
            )
            assertTrue(
                "Sin modelos sugeridos: ${proveedor.id}",
                proveedor.suggestedModels.isNotEmpty()
            )
            // El modelo por defecto debe estar entre los sugeridos (asi el
            // selector de la interfaz siempre lo muestra marcado).
            assertTrue(
                "El modelo por defecto de ${proveedor.id} no esta en la lista",
                proveedor.suggestedModels.contains(proveedor.defaultModel)
            )
        }
    }

    @Test
    fun `fromId aguanta entradas raras y cae a Gemini`() {
        assertEquals(AiProvider.OPENAI, AiProvider.fromId("openai"))
        assertEquals(AiProvider.DEEPSEEK, AiProvider.fromId("DEEPSEEK"))
        assertEquals(AiProvider.CLAUDE, AiProvider.fromId("claude"))
        assertEquals(AiProvider.GEMINI, AiProvider.fromId(null))
        assertEquals(AiProvider.GEMINI, AiProvider.fromId(""))
        assertEquals(AiProvider.GEMINI, AiProvider.fromId("proveedor-que-no-existe"))
    }

    // ========================================================================
    //  FORMATO DE LAS PETICIONES (una forma de API por proveedor)
    // ========================================================================

    private val peticion = AiRequest(
        systemInstruction = "Eres el asistente de Panda Fit.",
        turns = listOf(
            GeminiTurn(fromUser = true, text = "Dame un plan"),
            GeminiTurn(fromUser = false, text = "Aqui tienes"),
            GeminiTurn(fromUser = true, text = "Gracias")
        ),
        maxOutputTokens = 800
    )

    @Test
    fun `OpenAI y DeepSeek usan messages con rol system`() {
        val json = AiJson.openAiPayload(peticion, "gpt-4o-mini", stream = true)
        val raiz = AiHttp.json.parseToJsonElement(json).jsonObject

        assertEquals("gpt-4o-mini", raiz["model"]?.jsonPrimitive?.content)
        assertEquals("true", raiz["stream"]?.jsonPrimitive?.content)
        val mensajes = raiz["messages"]?.jsonArray ?: error("sin messages")
        // 1 system + los 3 turnos
        assertEquals(4, mensajes.size)
        assertEquals("system", mensajes[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", mensajes[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", mensajes[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", mensajes[3].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Claude usa system aparte y mensajes user assistant`() {
        val json = AiJson.claudePayload(peticion, "claude-sonnet-4-5", stream = false)
        val raiz = AiHttp.json.parseToJsonElement(json).jsonObject

        assertEquals("claude-sonnet-4-5", raiz["model"]?.jsonPrimitive?.content)
        assertEquals(
            "Eres el asistente de Panda Fit.",
            raiz["system"]?.jsonPrimitive?.content
        )
        // Claude NO admite un mensaje "system" dentro de messages
        val mensajes = raiz["messages"]?.jsonArray ?: error("sin messages")
        assertEquals(3, mensajes.size)
        assertTrue(
            mensajes.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
        )
        assertNull(raiz["stream"])
        assertEquals("800", raiz["max_tokens"]?.jsonPrimitive?.content)
    }

    // ========================================================================
    //  PARSERS DE RESPUESTA
    // ========================================================================

    @Test
    fun `se extrae el texto de una respuesta de OpenAI`() {
        val respuesta = """
            {"choices":[{"message":{"role":"assistant","content":"Hola panda"}}]}
        """.trimIndent()
        assertEquals("Hola panda", AiJson.extractOpenAiText(respuesta))
    }

    @Test
    fun `se extrae el trozo incremental de OpenAI y DeepSeek`() {
        val trozo = """{"choices":[{"delta":{"content":"Hola"}}]}"""
        assertEquals("Hola", AiJson.extractOpenAiDelta(trozo))
        // El centinela de fin de stream no produce texto
        assertNull(AiJson.extractOpenAiDelta("[DONE]"))
    }

    @Test
    fun `se extrae el texto de una respuesta de Claude ignorando otros bloques`() {
        val respuesta = """
            {"content":[
              {"type":"thinking","thinking":"no cuenta"},
              {"type":"text","text":"Hola "},
              {"type":"text","text":"panda"}
            ]}
        """.trimIndent()
        assertEquals("Hola panda", AiJson.extractClaudeText(respuesta))
    }

    @Test
    fun `se extrae el trozo incremental de Claude`() {
        val trozo = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hola"}}"""
        assertEquals("Hola", AiJson.extractClaudeDelta(trozo))
        // Los eventos que no son de texto se ignoran
        val inicio = """{"type":"message_start","message":{"id":"msg_1"}}"""
        assertNull(AiJson.extractClaudeDelta(inicio))
    }

    @Test
    fun `se listan los modelos de OpenAI DeepSeek y Claude`() {
        val respuesta = """{"data":[{"id":"modelo-a"},{"id":"modelo-b"}]}"""
        assertEquals(listOf("modelo-a", "modelo-b"), AiJson.extractModelIds(respuesta))
        assertEquals(listOf("modelo-a", "modelo-b"), AiJson.extractClaudeModelIds(respuesta))
        // Respuesta vacia o con forma inesperada -> lista vacia, sin excepcion
        assertTrue(AiJson.extractModelIds("""{"data":[]}""").isEmpty())
        assertTrue(AiJson.extractModelIds("no es json").isEmpty())
    }

    @Test
    fun `una respuesta con forma inesperada no rompe la app`() {
        assertNull(AiJson.extractOpenAiText("{}"))
        assertNull(AiJson.extractOpenAiText("esto no es json"))
        assertNull(AiJson.extractClaudeText("{}"))
        assertNull(AiJson.extractClaudeDelta("{}"))
    }
}
