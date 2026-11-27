// ============================================================================
//  Panda Fit - SuenoBandaTest.kt
//  Pruebas del lector de SUENO del reloj (comando 0x32 / 0x33 arg 3-4).
//  Formato: registros {tipo, hora, minuto}; cada uno marca el inicio de una
//  fase (0 despierto, 1 ligero, 2 profundo) que dura hasta el siguiente.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ble.BleProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuenoBandaTest {

    private fun reg(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `noche que cruza la medianoche suma bien las fases`() {
        // 23:30 ligero -> 01:00 profundo -> 03:00 ligero -> 06:30 despierto -> 06:45 fin
        val s = BleProtocol.leerSueno(
            reg(1, 23, 30, 2, 1, 0, 1, 3, 0, 0, 6, 30, 0, 6, 45)
        )
        assertNotNull(s)
        s!!
        assertEquals(120, s.profundoMin)         // 01:00-03:00
        assertEquals(90 + 210, s.ligeroMin)      // 23:30-01:00 + 03:00-06:30
        assertEquals(15, s.despiertoMin)         // 06:30-06:45
        assertEquals(7.0, s.horas, 0.001)
        assertTrue(s.calidad in 1..100)
    }

    @Test
    fun `el byte de dia de 0x33 se descarta`() {
        val sinDia = BleProtocol.leerSueno(reg(1, 0, 0, 2, 2, 0, 0, 5, 0))
        val conDia = BleProtocol.leerSueno(reg(3, 1, 0, 0, 2, 2, 0, 0, 5, 0))
        assertEquals(sinDia, conDia)
    }

    @Test
    fun `datos incoherentes no inventan sueno`() {
        assertNull(BleProtocol.leerSueno(ByteArray(0)))
        assertNull(BleProtocol.leerSueno(reg(1, 23, 0)))                 // un solo registro
        assertNull(BleProtocol.leerSueno(reg(7, 23, 0, 0, 6, 0)))        // tipo desconocido
        assertNull(BleProtocol.leerSueno(reg(1, 25, 0, 0, 6, 0)))        // hora 25
        assertNull(BleProtocol.leerSueno(reg(0, 22, 0, 0, 6, 0)))        // solo despierto
        assertNull(BleProtocol.leerSueno(reg(1, 1, 2, 0, 3)))            // longitud rara
    }

    @Test
    fun `mas sueno profundo da mejor calidad`() {
        val poco = BleProtocol.SuenoBanda(profundoMin = 30, ligeroMin = 390, despiertoMin = 30)
        val mucho = BleProtocol.SuenoBanda(profundoMin = 150, ligeroMin = 270, despiertoMin = 10)
        assertTrue(mucho.calidad > poco.calidad)
    }
}
