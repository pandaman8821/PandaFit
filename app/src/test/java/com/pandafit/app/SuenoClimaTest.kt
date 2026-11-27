// ============================================================================
//  Panda Fit - SuenoClimaTest.kt
//  0.1.6: sueno cada hora hasta levantarse; el clima sigue a tu ciudad.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ble.LecturaSueno
import com.pandafit.app.data.Clima
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SuenoClimaTest {

    private val hora = 3_600_000L

    @Test
    fun `pide el sueno cada hora solo de 6 a 13`() {
        val t = 100 * hora
        assertTrue(LecturaSueno.tocaPedir(LocalDateTime.of(2026, 11, 26, 6, 5), 0L, t, completoHoy = false))
        // Hace 30 min que se pidio: aun no.
        assertFalse(LecturaSueno.tocaPedir(LocalDateTime.of(2026, 11, 26, 7, 0), t - hora / 2, t, false))
        // Hace 1 h: si.
        assertTrue(LecturaSueno.tocaPedir(LocalDateTime.of(2026, 11, 26, 8, 0), t - hora, t, false))
        // Antes de las 6 o desde las 13: nunca.
        assertFalse(LecturaSueno.tocaPedir(LocalDateTime.of(2026, 11, 26, 5, 59), 0L, t, false))
        assertFalse(LecturaSueno.tocaPedir(LocalDateTime.of(2026, 11, 26, 13, 0), 0L, t, false))
        // Lectura ya definitiva: nunca mas hoy.
        assertFalse(LecturaSueno.tocaPedir(LocalDateTime.of(2026, 11, 26, 9, 0), 0L, t, completoHoy = true))
    }

    @Test
    fun `la lectura es definitiva cuando te levantas y caminas`() {
        val despierta = 8 * 60 + 40                       // 8:40
        assertFalse(LecturaSueno.lecturaCompleta(despierta, 8 * 60 + 50, 900))   // solo 10 min despierto
        assertFalse(LecturaSueno.lecturaCompleta(despierta, 9 * 60 + 30, 120))   // aun no camina
        assertTrue(LecturaSueno.lecturaCompleta(despierta, 9 * 60 + 30, 450))
        assertFalse(LecturaSueno.lecturaCompleta(null, 10 * 60, 2000))           // sin lectura
        assertFalse(LecturaSueno.lecturaCompleta(-1, 10 * 60, 2000))
    }

    @Test
    fun `dos ciudades a 90 km no son el mismo sitio`() {
        val ciudadA = 40.42 to -3.70
        val ciudadB = 40.96 to -4.12
        assertTrue(Clima.distanciaKm(ciudadA.first, ciudadA.second, ciudadB.first, ciudadB.second) > 50)
        assertTrue(Clima.distanciaKm(40.42, -3.70, 40.425, -3.705) < Clima.RADIO_MISMO_SITIO_KM)
    }
}
