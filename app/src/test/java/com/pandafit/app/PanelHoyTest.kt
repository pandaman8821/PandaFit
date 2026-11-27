// ============================================================================
//  Panda Fit - PanelHoyTest.kt
//  Pruebas de los datos del panel "Hoy": reparto de pasos por hora y tramos
//  del hipnograma (hora de dormirse y de despertar).
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ble.BleProtocol
import com.pandafit.app.data.DayLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PanelHoyTest {

    private val vacio = List(24) { 0 }

    @Test
    fun `los pasos nuevos van a la hora actual`() {
        val horas = DayLog.repartir(vacio, anterior = 1_000, total = 1_250, hora = 9)
        assertEquals(250, horas[9])
        assertEquals(250, horas.sum())
    }

    @Test
    fun `sin referencia previa no se inventa en que hora se caminaron`() {
        assertEquals(vacio, DayLog.repartir(vacio, anterior = -1, total = 7_058, hora = 17))
    }

    @Test
    fun `si el total baja (reinicio o cambio de fuente) no se resta nada`() {
        val antes = vacio.mapIndexed { i, v -> if (i == 8) 500 else v }
        assertEquals(antes, DayLog.repartir(antes, anterior = 7_000, total = 120, hora = 10))
    }

    @Test
    fun `el hipnograma sabe la hora de dormirse y de despertar`() {
        fun reg(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        // 03:49 ligero -> 04:30 profundo -> 05:10 ligero -> 07:09 fin
        val s = BleProtocol.leerSueno(reg(1, 3, 49, 2, 4, 30, 1, 5, 10, 0, 7, 9))
        assertNotNull(s)
        assertEquals(3 * 60 + 49, s!!.dormirMin)
        assertEquals(7 * 60 + 9, s.despertarMin)
        assertEquals(3, s.tramos.size)
        assertEquals(3 * 60 + 20, s.dormidoMin)   // 3 h 20 min
    }
}
