// ============================================================================
//  Panda Fit - CharlaIaTest.kt
//  v25: dialogos mas humanos. El motor local responde a la charla (saludos,
//  gracias, desanimo...) en vez de soltar el informe, y la IA recibe la hora.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.FitnessEngine
import com.pandafit.app.ai.PromptBuilder
import com.pandafit.app.data.model.DailyMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CharlaIaTest {

    private val hoy = DailyMetrics(dateKey = "2026-11-18", steps = 6_200, source = "ble")

    @Test
    fun `un hola recibe un saludo segun la hora y no el informe`() {
        val r = FitnessEngine.charlaLocal("Hola!", hoy, hora = 9)!!
        assertTrue(r.startsWith("Buenos dias"))
        assertTrue(!r.contains("Puntuacion"))
        assertTrue(FitnessEngine.charlaLocal("buenas", hoy, hora = 16)!!.startsWith("Buenas tardes"))
    }

    @Test
    fun `entiende acentos y despedidas`() {
        assertNotNull(FitnessEngine.charlaLocal("¿Cómo estás?", hoy, hora = 12))
        assertNotNull(FitnessEngine.charlaLocal("muchas gracias", hoy, hora = 12))
        assertTrue(FitnessEngine.charlaLocal("buenas noches", hoy, hora = 22)!!.contains("descanses"))
    }

    @Test
    fun `si esta desanimado responde con empatia`() {
        val r = FitnessEngine.charlaLocal("hoy estoy muy cansado y sin ganas", hoy, hora = 18)!!
        assertTrue(r.startsWith("Te entiendo"))
    }

    @Test
    fun `una pregunta de verdad no es charla`() {
        assertNull(FitnessEngine.charlaLocal("¿cuantas calorias quemo corriendo 5 km?", hoy, hora = 10))
        assertNull(FitnessEngine.charlaLocal("hola, quiero saber por que me duele la rodilla al correr", hoy, hora = 10))
    }

    @Test
    fun `el momento del dia se describe para la IA`() {
        assertEquals("miercoles 18:05 (tarde)", PromptBuilder.momentoDelDia(LocalDateTime.of(2026, 11, 18, 18, 5)))
        assertEquals("jueves 02:30 (madrugada)", PromptBuilder.momentoDelDia(LocalDateTime.of(2026, 11, 19, 2, 30)))
    }

    @Test
    fun `las respuestas de la IA llevan la enie`() {
        assertEquals(
            "Tu sueño de anoche fue corto. Mañana, SUEÑO primero.",
            com.pandafit.app.ai.ConEnes.corregir("Tu sueno de anoche fue corto. Manana, SUENO primero.")
        )
        // No toca palabras que solo contienen las letras ni claves con guiones.
        assertEquals("suenovelas panda_sueno pandafit-sueno", com.pandafit.app.ai.ConEnes.corregir("suenovelas panda_sueno pandafit-sueno"))
        val s = PromptBuilder.systemInstruction(com.pandafit.app.data.model.GenderMode.values().first())
        assertTrue(!Regex("\\bsueno\\b").containsMatchIn(s))
    }

    @Test
    fun `la personalidad pide conversar sin informes para un saludo`() {
        val s = PromptBuilder.systemInstruction(com.pandafit.app.data.model.GenderMode.values().first())
        assertTrue(s.contains("NO sueltes un resumen de datos"))
        assertTrue(!s.contains("Respuestas de 120 a 350 palabras"))
    }
}
