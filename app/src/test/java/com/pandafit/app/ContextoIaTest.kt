// ============================================================================
//  Panda Fit - ContextoIaTest.kt
//  La IA recibe las mediciones detalladas de hoy, y siguen siendo anonimas.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.ContextoDetallado
import com.pandafit.app.ble.BleProtocol
import com.pandafit.app.data.DayLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ContextoIaTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun `sin mediciones no se envia nada extra`() {
        assertEquals("", ContextoDetallado.resumen(DayLog.Snapshot(fecha = "2026-11-18"), null, utc))
    }

    @Test
    fun `el contexto incluye sueno por fases, horas activas, pulso, presion y spo2`() {
        val horas = List(24) { if (it == 9) 1200 else if (it == 18) 800 else 0 }
        val log = DayLog.Snapshot(
            fecha = "2026-11-18",
            pasosPorHora = horas,
            pulsos = listOf(DayLog.MuestraPulso(600, 62), DayLog.MuestraPulso(700, 80), DayLog.MuestraPulso(800, 71)),
            presion = DayLog.Presion(113, 70, 0L),
            spo2 = DayLog.Medida(98, 0L)
        )
        val sueno = BleProtocol.SuenoBanda(
            profundoMin = 60, ligeroMin = 140, despiertoMin = 10,
            dormirMin = 3 * 60 + 49, despertarMin = 7 * 60 + 9
        )
        val txt = ContextoDetallado.resumen(log, sueno, utc)
        assertTrue(txt.contains("3 h 20 min"))
        assertTrue(txt.contains("03:49"))
        assertTrue(txt.contains("09h: 1200 pasos"))
        assertTrue(txt.contains("minimo 62, medio 71, maximo 80"))
        assertTrue(txt.contains("113/70"))
        assertTrue(txt.contains("98 %"))
    }

    @Test
    fun `la IA sabe cuanta agua llevas`() {
        val log = DayLog.Snapshot(fecha = "2026-11-18", vasosAgua = 6)
        val txt = ContextoDetallado.resumen(log, null, utc)
        assertTrue(txt.contains("6 vasos de 250 ml (1.50 L)"))
    }

    @Test
    fun `el contexto detallado no lleva identificadores`() {
        val log = DayLog.Snapshot(fecha = "2026-11-18", presion = DayLog.Presion(120, 80, 0L))
        val txt = ContextoDetallado.resumen(log, null, utc)
        assertFalse(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").containsMatchIn(txt))
        assertFalse(txt.contains("@"))
    }
}
