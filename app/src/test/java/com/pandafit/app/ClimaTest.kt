// ============================================================================
//  Panda Fit - ClimaTest.kt
//  0.0.3: lectura de la respuesta de Open-Meteo, tipo de cielo y consejos.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.data.Cielo
import com.pandafit.app.data.Clima
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimaTest {

    private val respuesta = """
        {"latitude":40.42,"longitude":-3.7,
         "current":{"time":"2026-11-22T13:00","temperature_2m":18.6,"apparent_temperature":17.2,
                    "relative_humidity_2m":55,"weather_code":2,"wind_speed_10m":12.4,"is_day":1},
         "daily":{"time":["2026-11-22"],"temperature_2m_max":[20.3],"temperature_2m_min":[8.9],
                  "uv_index_max":[3.1],"precipitation_probability_max":[10]}}
    """.trimIndent()

    @Test
    fun `lee la respuesta de open-meteo`() {
        val c = Clima.interpretar(respuesta, "Madrid", ahora = 0)
        assertEquals("Madrid", c.ciudad)
        assertEquals(19, c.temp)
        assertEquals(17, c.sensacion)
        assertEquals(20, c.maxima)
        assertEquals(9, c.minima)
        assertEquals(10, c.probLluvia)
        assertEquals("Parcialmente nublado", c.descripcion)
        assertEquals(Cielo.POCO_NUBOSO, c.cielo)
        assertTrue(c.esDeDia)
    }

    @Test
    fun `codigos del cielo`() {
        assertEquals(Cielo.DESPEJADO, Clima.cielo(0))
        assertEquals(Cielo.LLUVIA, Clima.cielo(63))
        assertEquals(Cielo.TORMENTA, Clima.cielo(95))
        assertEquals(Cielo.NIEVE, Clima.cielo(73))
        assertEquals("Noche despejada", Clima.descripcion(0, dia = false))
    }

    @Test
    fun `el consejo depende del tiempo`() {
        val base = Clima.interpretar(respuesta, "X", 0)
        assertTrue(Clima.consejo(base).startsWith("Buen tiempo"))
        assertTrue(Clima.consejo(base.copy(codigo = 95)).contains("tormenta"))
        assertTrue(Clima.consejo(base.copy(codigo = 63)).contains("casa"))
        assertTrue(Clima.consejo(base.copy(sensacion = 34)).contains("calor"))
        assertTrue(Clima.consejo(base.copy(sensacion = 1)).contains("frío"))
    }
}
