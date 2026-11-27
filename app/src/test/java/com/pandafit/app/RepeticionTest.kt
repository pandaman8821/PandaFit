// ============================================================================
//  Panda Fit - RepeticionTest.kt
//  0.1.1: la IA recibe como empezo y que palabras repitio, para variar.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.Repeticion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeticionTest {

    private val previas = listOf(
        "¡Genial! Hoy es fundamental mantener la constancia con tus pasos.",
        "**¡Genial!** Recuerda que es fundamental mantener un buen descanso.",
        "¡Genial! La constancia es fundamental para mantener el progreso."
    )

    @Test
    fun `detecta las palabras repetidas`() {
        val r = Repeticion.muyUsadas(previas)
        assertTrue("fundamental" in r)
        assertTrue("mantener" in r)
        assertTrue("genial" in r)
        assertTrue("pasos" !in r)            // palabra comun del tema: no cuenta
    }

    @Test
    fun `aviso con los comienzos y nada si hay poca charla`() {
        val a = Repeticion.aviso(previas)
        assertTrue(a.contains("\"¡Genial! Hoy es fundamental mantener la\""))
        assertTrue(a.contains("Empieza esta de otra forma"))
        assertEquals("", Repeticion.aviso(previas.take(1)))
    }
}
