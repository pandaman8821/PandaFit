// ============================================================================
//  Panda Fit - EntrenoVivoTest.kt
//  v26: cronometro con pausa automatica y avisos del entrenador por voz.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.data.MotorEntreno
import com.pandafit.app.data.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntrenoVivoTest {

    // ~0.00899 grados de latitud = 1 km.
    private fun p(km: Double, t: Long, prec: Float = 5f) =
        TrackPoint(lat = 40.0 + km * 0.0089932, lon = -3.0, timestamp = t, accuracyMeters = prec)

    @Test
    fun `anuncia cada kilometro con tiempo y ritmo`() {
        val m = MotorEntreno("Carrera", usaGps = true, autoPausa = true)
        var t = 0L
        val avisos = mutableListOf<String>()
        // 1.2 km a 5 min/km: un punto cada 10 s (33 m).
        for (i in 0..36) {
            m.punto(p(i * 0.0333, t))
            repeat(10) { t += 1000; avisos += m.tic(t, pulso = 150) }
        }
        val km = avisos.filter { it.startsWith("Kilómetro 1") }
        assertEquals(1, km.size)
        assertTrue(km[0].contains("Pulso: 150"))
        assertTrue(km[0].contains("minutos"))
        assertTrue(m.km > 1.1 && m.km < 1.3)
    }

    @Test
    fun `pausa sola al pararse y sigue al moverse`() {
        val m = MotorEntreno("Caminata", usaGps = true, autoPausa = true)
        m.punto(p(0.0, 0))
        m.punto(p(0.01, 5_000))
        var t = 5_000L
        val avisos = mutableListOf<String>()
        repeat(20) { t += 1000; avisos += m.tic(t, 0) }         // 20 s quieto
        assertTrue(m.pausaAuto)
        assertTrue("Pausa automática." in avisos)
        val segundosEnPausa = m.segundos
        repeat(5) { t += 1000; m.tic(t, 0) }
        assertEquals(segundosEnPausa, m.segundos)               // el reloj no corre
        m.punto(p(0.02, t))
        t += 1000
        assertTrue("Seguimos." in m.tic(t, 0))
        assertFalse(m.pausaAuto)
    }

    @Test
    fun `descarta puntos imprecisos y saltos imposibles`() {
        val m = MotorEntreno("Carrera", usaGps = true, autoPausa = false)
        m.punto(p(0.0, 0))
        assertFalse(m.punto(p(0.5, 1_000, prec = 80f)))     // precision mala
        assertFalse(m.punto(p(1.0, 2_000)))                  // 1 km en 2 s
        assertTrue(m.punto(p(0.01, 4_000)))
        assertEquals(0.01, m.km, 0.001)
    }

    @Test
    fun `sin gps avisa cada 5 minutos y no se pausa solo`() {
        val m = MotorEntreno("Fuerza", usaGps = false, autoPausa = true)
        var t = 0L
        val avisos = mutableListOf<String>()
        repeat(601) { t += 1000; avisos += m.tic(t, 0) }
        assertEquals(2, avisos.count { it.startsWith("Llevas") })
        assertEquals("Llevas 10 minutos. Sigue así.", avisos.last())
        assertFalse(m.pausaAuto)
    }

    @Test
    fun `duracion hablada natural`() {
        assertEquals("5 minutos 30 segundos", MotorEntreno.duracionHablada(330))
        assertEquals("1 hora 2 minutos", MotorEntreno.duracionHablada(3_725))
        assertEquals("1 minuto", MotorEntreno.duracionHablada(60))
    }
}
