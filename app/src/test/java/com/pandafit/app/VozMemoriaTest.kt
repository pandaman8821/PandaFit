// ============================================================================
//  Panda Fit - VozMemoriaTest.kt
//  0.0.6: la voz lee TODO y bien pronunciado; la memoria de Panda.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.MemoriaPanda
import com.pandafit.app.ai.TextoHablado
import com.pandafit.app.ai.VozPanda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VozMemoriaTest {

    @Test
    fun `un texto largo se lee entero, sin recortar`() {
        val largo = (1..60).joinToString(" ") { "Esta es la frase número $it del plan de hoy." }
        val frases = TextoHablado.frases(largo)
        assertEquals(60, frases.size)
        assertTrue(frases.last().contains("60"))
        // Ninguna frase pasa del limite que acepta el motor de voz.
        val sinPuntos = "palabra ".repeat(200)
        assertTrue(TextoHablado.frases(sinPuntos).all { it.length <= 280 })
        // No se pierde ni una palabra (solo se anade el punto final de la pausa).
        assertEquals(sinPuntos.trim() + ".", TextoHablado.frases(sinPuntos).joinToString(" "))
    }

    @Test
    fun `las listas se convierten en frases con pausa`() {
        val txt = "PLAN DE HOY\n- Caminar 20 min\n- 3x12 sentadillas\n**Ánimo**"
        val f = TextoHablado.frases(txt)
        assertEquals(listOf("Plan de hoy.", "Caminar 20 minutos.", "3 por 12 sentadillas.", "Ánimo."), f)
    }

    @Test
    fun `abreviaturas y cifras se leen bien`() {
        val t = TextoHablado.normalizar("Hoy 5 km a 72 ppm, 300 kcal, SpO2 97% y presión 113/70 mmHg. 1 h de paseo.")
        assertTrue(t.contains("5 kilómetros"))
        assertTrue(t.contains("72 pulsaciones por minuto"))
        assertTrue(t.contains("300 kilocalorías"))
        assertTrue(t.contains("oxígeno en sangre 97 por ciento"))
        assertTrue(t.contains("113 sobre 70 milímetros de mercurio"))
        assertTrue(t.contains("1 hora de paseo"))
        // Las palabras con tilde no se rompen ("5 hábitos" no es "5 horas").
        assertTrue(TextoHablado.normalizar("5 hábitos").contains("5 hábitos"))
        assertTrue(TextoHablado.normalizar("Haz 8-10 repeticiones").contains("8 a 10"))
    }

    @Test
    fun `quita emojis y corrige la enie`() {
        val t = TextoHablado.normalizar("Buen sueno anoche 💪🐼")
        assertEquals("Buen sueño anoche.", t)
    }

    @Test
    fun `la memoria se extrae y no se muestra`() {
        val (limpio, r) = MemoriaPanda.extraer(
            "Vale, iremos suave con esa rodilla.\n[RECUERDA: le molesta la rodilla derecha al correr]\n[RECUERDA: quiere correr 10 km en marzo]"
        )
        assertEquals("Vale, iremos suave con esa rodilla.", limpio)
        assertEquals(listOf("le molesta la rodilla derecha al correr", "quiere correr 10 km en marzo"), r)
        // Etiqueta a medio llegar (streaming): tampoco se ve.
        assertEquals("Hola", MemoriaPanda.sinEtiquetas("Hola\n[RECUER"))
        assertFalse(MemoriaPanda.sinEtiquetas("Hola [RECUERDA: algo]").contains("RECUERDA"))
    }

    @Test
    fun `cabecera wav correcta`() {
        val w = VozPanda.wav(ByteArray(48_000), 24_000)
        assertEquals(44 + 48_000, w.size)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
    }
}
