// ============================================================================
//  Panda Fit - EntrenoTest.kt
//  Distancia GPS (haversine) y ritmo min/km de la pantalla de entrenamiento.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.data.model.TrackPoint
import com.pandafit.app.ui.screens.distanciaKm
import com.pandafit.app.ui.screens.formatoRitmo
import com.pandafit.app.ui.screens.recorridoKm
import org.junit.Assert.assertEquals
import org.junit.Test

class EntrenoTest {

    @Test
    fun `un grado de latitud mide unos 111 km`() {
        val d = distanciaKm(TrackPoint(18.0, -68.9), TrackPoint(19.0, -68.9))
        assertEquals(111.2, d, 0.3)
    }

    @Test
    fun `el recorrido suma los tramos`() {
        // ~0.001 grados de latitud = ~111 m por tramo, 3 tramos = ~0.33 km.
        val puntos = (0..3).map { TrackPoint(18.42 + it * 0.001, -68.97) }
        assertEquals(0.333, recorridoKm(puntos), 0.01)
    }

    @Test
    fun `el ritmo se muestra como minutos y segundos por km`() {
        assertEquals("5:30", formatoRitmo(5.5))
        assertEquals("12:05", formatoRitmo(12.0 + 5.0 / 60))
        assertEquals("--", formatoRitmo(90.0))   // parado: sin ritmo
    }
}
