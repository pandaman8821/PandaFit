// ============================================================================
//  Panda Fit - RachaAvisosTest.kt
//  Racha de dias cumpliendo la meta y regla del recordatorio de moverse.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.data.AvisosActividad
import com.pandafit.app.data.Racha
import com.pandafit.app.data.model.DailyMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RachaAvisosTest {

    private val hoy = LocalDate.of(2026, 11, 18)
    private fun dia(offset: Long, pasos: Int, fuente: String = "ble") =
        DailyMetrics(dateKey = hoy.minusDays(offset).toString(), steps = pasos, source = fuente)

    @Test
    fun `cuenta los dias seguidos con la meta cumplida`() {
        val dias = listOf(dia(3, 2_000), dia(2, 11_000), dia(1, 12_000), dia(0, 10_500))
        assertEquals(3, Racha.calcular(dias, 10_000, hoy))
    }

    @Test
    fun `si hoy aun no se cumple la racha de ayer sigue viva`() {
        val dias = listOf(dia(2, 11_000), dia(1, 12_000), dia(0, 3_000))
        assertEquals(2, Racha.calcular(dias, 10_000, hoy))
    }

    @Test
    fun `los dias de ejemplo no cuentan`() {
        val dias = listOf(dia(1, 15_000, "simulado"), dia(0, 15_000, "simulado"))
        assertEquals(0, Racha.calcular(dias, 10_000, hoy))
    }

    @Test
    fun `el aviso de agua salta solo si vas por detras del ritmo`() {
        // A las 15:00 con meta 8 tocarian 4 vasos: con 1 vaso va por detras.
        assertTrue(AvisosActividad.debeRecordarAgua(vasos = 1, meta = 8, hora = 15, minuto = 0))
        assertFalse(AvisosActividad.debeRecordarAgua(vasos = 4, meta = 8, hora = 15, minuto = 0))
        assertFalse(AvisosActividad.debeRecordarAgua(vasos = 0, meta = 8, hora = 9, minuto = 30))   // recien empieza
        assertFalse(AvisosActividad.debeRecordarAgua(vasos = 0, meta = 8, hora = 22, minuto = 0))   // de noche no
        assertFalse(AvisosActividad.debeRecordarAgua(vasos = 8, meta = 8, hora = 18, minuto = 0))   // meta cumplida
    }

    @Test
    fun `el resumen semanal solo usa dias reales de los ultimos 7`() {
        val dias = listOf(
            dia(9, 20_000),                 // fuera de la semana
            dia(5, 12_000), dia(3, 6_000), dia(1, 10_000),
            dia(0, 4_000, "simulado")       // de ejemplo: no cuenta
        )
        val s = AvisosActividad.resumenSemana(dias, 10_000, hoy)!!
        assertEquals(3, s.diasConDatos)
        assertEquals(9_333, s.mediaPasos)
        assertEquals(2, s.diasMeta)
        assertEquals(null, AvisosActividad.resumenSemana(listOf(dia(0, 5_000, "simulado")), 10_000, hoy))
    }

    @Test
    fun `la hora de acostarse cruza la medianoche bien`() {
        // Despierta 7:00, meta 8 h -> acostarse 22:45.
        assertEquals(22 * 60 + 45, AvisosActividad.horaDeAcostarse(7 * 60, 8f))
        // Despierta 5:30, meta 7.5 h -> 21:45.
        assertEquals(21 * 60 + 45, AvisosActividad.horaDeAcostarse(5 * 60 + 30, 7.5f))
        // Despierta 9:00, meta 9 h -> 23:45 del dia anterior.
        assertEquals(23 * 60 + 45, AvisosActividad.horaDeAcostarse(9 * 60, 9f))
        assertTrue(AvisosActividad.debeRecordarDormir(22 * 60 + 20, 22 * 60 + 45))   // faltan 25 min
        assertFalse(AvisosActividad.debeRecordarDormir(21 * 60, 22 * 60 + 45))       // falta mucho
        assertTrue(AvisosActividad.debeRecordarDormir(23 * 60 + 50, 10))             // cruza medianoche
    }

    @Test
    fun `el historial reduce el recorrido conservando inicio y fin`() {
        val r = com.pandafit.app.data.WorkoutHistory.reducir((0 until 1000).toList(), 200)
        assertEquals(200, r.size)
        assertEquals(0, r.first())
        assertEquals(999, r.last())
    }

    @Test
    fun `el recordatorio solo salta con dos horas casi quieto, de 9 a 21`() {
        val horas = MutableList(24) { 0 }.apply { this[8] = 900 }   // hubo pasos por la manana
        assertTrue(AvisosActividad.debeRecordarMoverse(horas, hora = 10, minuto = 55))
        assertFalse(AvisosActividad.debeRecordarMoverse(horas, hora = 10, minuto = 20))   // aun no termina la hora
        assertFalse(AvisosActividad.debeRecordarMoverse(horas, hora = 22, minuto = 55))   // de noche no
        horas[10] = 600
        assertFalse(AvisosActividad.debeRecordarMoverse(horas, hora = 10, minuto = 55))   // se movio
        assertFalse(AvisosActividad.debeRecordarMoverse(List(24) { 0 }, hora = 10, minuto = 55)) // sin datos hoy
    }
}
