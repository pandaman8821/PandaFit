// ============================================================================
//  Panda Fit - PodometroTest.kt
//  Pruebas del calculo de pasos a partir del contador ACUMULADO del sensor
//  de pasos del telefono (Sensor.TYPE_STEP_COUNTER).
// ============================================================================
package com.pandafit.app

import com.pandafit.app.data.PhoneStepCounter
import org.junit.Assert.assertEquals
import org.junit.Test

class PodometroTest {

    @Test
    fun `la primera lectura solo fija la referencia`() {
        assertEquals(0, PhoneStepCounter.calcularIncremento(-1L, 54_321L))
    }

    @Test
    fun `se suman los pasos nuevos entre lecturas`() {
        assertEquals(120, PhoneStepCounter.calcularIncremento(1_000L, 1_120L))
        assertEquals(0, PhoneStepCounter.calcularIncremento(1_000L, 1_000L))
    }

    @Test
    fun `tras reiniciar el movil el contador vuelve a cero y no se pierden pasos`() {
        // Antes del reinicio: 80 000. Despues: el sensor arranca de 0 y ya lleva 35.
        assertEquals(35, PhoneStepCounter.calcularIncremento(80_000L, 35L))
    }

    @Test
    fun `un salto absurdo se limita`() {
        assertEquals(60_000, PhoneStepCounter.calcularIncremento(0L, 9_000_000L))
    }
}
