// ============================================================================
//  Panda Fit - IdiomasCuerpoTest.kt
//  0.1.7: estatura (zancada, distancia, IMC), idioma del telefono y frases del
//  entrenador por voz en los 6 idiomas.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.Idioma
import com.pandafit.app.data.Cuerpo
import com.pandafit.app.data.FrasesCoach
import com.pandafit.app.data.MotorEntreno
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class IdiomasCuerpoTest {

    @Test fun zancadaSegunEstatura() {
        assertEquals(0.747, Cuerpo.zancadaM(180, mujer = false), 0.001)
        assertEquals(0.661, Cuerpo.zancadaM(160, mujer = true), 0.001)
        // Fuera de rango se limita (120-220 cm).
        assertEquals(Cuerpo.zancadaM(220, false), Cuerpo.zancadaM(300, false), 0.0)
    }

    @Test fun distanciaConZancadaPropia() {
        // 10 000 pasos: 7,47 km a 180 cm y 6,61 km a 160 cm (mujer).
        assertEquals(7.47, Cuerpo.kmDePasos(10_000, 180, false), 0.001)
        assertEquals(6.61, Cuerpo.kmDePasos(10_000, 160, true), 0.001)
        assertEquals(0.0, Cuerpo.kmDePasos(0, 170, false), 0.0)
    }

    @Test fun imcYCategoria() {
        assertEquals(22.9, Cuerpo.imc(70, 175), 0.001)
        assertEquals(Cuerpo.CategoriaImc.BAJO_PESO, Cuerpo.categoria(17.0))
        assertEquals(Cuerpo.CategoriaImc.SALUDABLE, Cuerpo.categoria(22.9))
        assertEquals(Cuerpo.CategoriaImc.SOBREPESO, Cuerpo.categoria(27.0))
        assertEquals(Cuerpo.CategoriaImc.OBESIDAD, Cuerpo.categoria(31.0))
    }

    @Test fun idiomaDelTelefono() {
        assertEquals("en", Idioma.actual(Locale.US))
        assertEquals("pt", Idioma.actual(Locale.forLanguageTag("pt-BR")))
        assertEquals("de", Idioma.actual(Locale.GERMANY))
        // Idioma no traducido -> espanol.
        assertEquals("es", Idioma.actual(Locale.JAPAN))
        assertTrue(Idioma.nombreParaIa("fr").contains("Français"))
    }

    @Test fun frasesDelEntrenadorEnCadaIdioma() {
        assertEquals("12 minutos 5 segundos", FrasesCoach("es").duracion(725))
        assertEquals("12 minutes 5 seconds", FrasesCoach("en").duracion(725))
        assertEquals("1 Stunde 2 Minuten", FrasesCoach("de").duracion(3720))
        val km = FrasesCoach("en").km(2, 720, 355, 140)
        assertEquals("2 kilometers. Time: 12 minutes. Last kilometer pace: 5 minutes 55 per kilometer. Heart rate: 140.", km)
        assertTrue(FrasesCoach("fr").empezamos("Course").startsWith("C'est parti : Course"))
        assertTrue(FrasesCoach("it").tiempo(300, 0).startsWith("Hai fatto 5 minuti"))
        // Un idioma desconocido usa el espanol.
        assertEquals(FrasesCoach("es").pausa, FrasesCoach("ja").pausa)
    }

    @Test fun motorEntrenoHablaElIdiomaElegido() {
        val m = MotorEntreno("Fuerza", usaGps = false, autoPausa = false, idioma = "en")
        var avisos = emptyList<String>()
        repeat(300) { avisos = m.tic(System.currentTimeMillis(), 0) }
        assertEquals(listOf("You've done 5 minutes. Keep it up."), avisos)
    }
}
