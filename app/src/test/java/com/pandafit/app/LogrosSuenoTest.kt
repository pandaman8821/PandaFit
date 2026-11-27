// ============================================================================
//  Panda Fit - LogrosSuenoTest.kt
//  v26: medallas, retos semanales, analisis del sueno, copia e informe.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.data.CopiaSeguridad
import com.pandafit.app.data.Logros
import com.pandafit.app.data.ResumenDia
import com.pandafit.app.data.SuenoDia
import com.pandafit.app.data.SuenoSemana
import com.pandafit.app.data.model.WorkoutSession
import com.pandafit.app.share.InformePdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogrosSuenoTest {

    private val hoy = LocalDate.of(2026, 11, 19)   // jueves
    private fun dia(atras: Long, pasos: Int, sueno: SuenoDia? = null, agua: Int = 0) =
        ResumenDia(hoy.minusDays(atras).toString(), pasos = pasos, metaPasos = 10_000, sueno = sueno, vasosAgua = agua)

    @Test
    fun `medallas de racha, pasos y distancia`() {
        val dias = (0L..7L).map { dia(it, 11_000) } + dia(10, 21_000)
        val sesiones = listOf(WorkoutSession(id = "a", distanceKm = 10.4, durationSeconds = 3600))
        val m = Logros.evaluar(dias, sesiones, metaAgua = 8, metaSueno = 8f)
        assertTrue("racha_7" in m)
        assertFalse("racha_30" in m)
        assertTrue("pasos_20k" in m)
        assertTrue("km_10" in m && "km_5" in m && "km_21" !in m)
        assertTrue("entreno_1" in m && "entreno_10" !in m)
        assertEquals(8, Logros.mejorRacha(dias))
    }

    @Test
    fun `semana perfecta solo de lunes a domingo`() {
        // Del lunes 9 al domingo 15 de noviembre de 2026.
        val semana = (4L..10L).map { dia(it, 12_000) }
        assertTrue("semana_perfecta" in Logros.evaluar(semana, emptyList(), 8, 8f))
        val sinDomingo = semana.filterNot { it.fecha == "2026-11-15" }
        assertFalse("semana_perfecta" in Logros.evaluar(sinDomingo, emptyList(), 8, 8f))
    }

    @Test
    fun `los retos se basan en la media y avanzan en la semana`() {
        val previos = (4L..17L).map { dia(it, 8_000) }            // semanas anteriores: 8000/dia
        val obj = Logros.proponer(previos, emptyList(), 8f, hoy)
        assertEquals(60_000, obj["pasos"])                         // 8000*7*1.1 = 61600 -> 60000
        assertEquals(2, obj["entrenos"])
        val estaSemana = (0L..3L).map { dia(it, 9_000, agua = 8) }   // lunes a jueves
        val retos = Logros.progreso(obj, previos + estaSemana, emptyList(), 8f, 8, hoy)
        assertEquals(36_000, retos.first { it.id == "pasos" }.progreso)
        assertTrue(retos.first { it.id == "agua" }.cumplido)
    }

    @Test
    fun `la hora media de acostarse cruza la medianoche`() {
        assertEquals(0, SuenoSemana.horaMedia(listOf(23 * 60 + 30, 30)))       // 23:30 y 0:30 -> 0:00
        assertEquals(23 * 60, SuenoSemana.horaMedia(listOf(22 * 60 + 30, 23 * 60 + 30)))
        assertEquals(100, SuenoSemana.regularidad(listOf(1380, 1380, 1380)))
        assertTrue(SuenoSemana.regularidad(listOf(21 * 60, 1 * 60, 23 * 60)) < 60)
    }

    @Test
    fun `analisis del sueno de la semana`() {
        val dias = (0L..4L).map {
            dia(it, 5_000, SuenoDia(profundoMin = 90, ligeroMin = 300, despiertoMin = 20, dormirMin = 23 * 60, despertarMin = 7 * 60))
        } + dia(9, 5_000, SuenoDia(horasTotal = 9.0))          // fuera de los 7 dias
        val a = SuenoSemana.analizar(dias, 8f, hoy)!!
        assertEquals(5, a.noches.size)
        assertEquals(390, a.mediaMin)
        assertEquals(23 * 60, a.acostarseMin)
        assertEquals(100, a.regularidad)
        assertEquals(23, a.profundoPct)
        assertTrue(a.comentario.isNotBlank())
        assertNull(SuenoSemana.analizar(emptyList(), 8f, hoy))
    }

    @Test
    fun `la copia nunca incluye claves ni la pulsera`() {
        assertTrue(CopiaSeguridad.excluida("clave_api_gemini"))
        assertTrue(CopiaSeguridad.excluida("ultima_banda_ble"))
        assertTrue(CopiaSeguridad.excluida("id_usuario_anonimo"))
        assertFalse(CopiaSeguridad.excluida("meta_pasos"))
    }

    @Test
    fun `clasificacion orientativa de la presion`() {
        assertEquals("Normal", InformePdf.clasificar(113, 70))
        assertEquals("Elevada", InformePdf.clasificar(125, 75))
        assertEquals("Alta (grado 1)", InformePdf.clasificar(132, 78))
        assertEquals("Alta (grado 2)", InformePdf.clasificar(145, 92))
        assertNotNull(InformePdf.clasificar(85, 55))
    }
}
