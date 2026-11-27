// ============================================================================
//  Panda Fit - EntrenoEnVivo.kt
//  v26: logica PURA del entrenamiento en curso (se prueba sin telefono):
//    * acumulado de distancia con filtro de GPS (precision <= 30 m, > 3 m),
//    * PAUSA AUTOMATICA: si no hay movimiento en 12 s se para el cronometro y
//      se reanuda al volver a moverse (solo en actividades con GPS),
//    * ENTRENADOR POR VOZ: frases para cada kilometro (tiempo, ritmo del
//      kilometro y pulso) y, en actividades sin GPS, cada 5 minutos.
// ============================================================================
package com.pandafit.app.data

import com.pandafit.app.data.model.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Lo que ve la pantalla del entreno (lo publica el servicio cada segundo). */
data class EntrenoEstado(
    val tipo: String = "",
    val segundos: Int = 0,
    val km: Double = 0.0,
    val pausaAuto: Boolean = false,
    val pausaManual: Boolean = false,
    val ritmoUltimoKm: Int = 0,          // segundos por km del ultimo km completo
    val recorrido: List<TrackPoint> = emptyList()
) {
    val pausado: Boolean get() = pausaAuto || pausaManual
}

class MotorEntreno(
    val tipo: String,
    private val usaGps: Boolean,
    private val autoPausa: Boolean,
    private val segundosQuieto: Int = 12,
    /** 0.1.7: idioma de la voz del entrenador. */
    private val idioma: String = "es"
) {
    var segundos = 0; private set
    var km = 0.0; private set
    var pausaAuto = false; private set
    var pausaManual = false
    val recorrido = mutableListOf<TrackPoint>()

    private var ultimoBueno: TrackPoint? = null
    private var ultimoMovimientoMs = 0L
    private var kmAnunciados = 0
    private var segundosAlUltimoKm = 0
    var ritmoUltimoKm = 0; private set
    private var minutosAnunciados = 0

    /** Llega un punto del GPS. Devuelve true si se usa (es bueno y se movio). */
    fun punto(p: TrackPoint): Boolean {
        if (p.accuracyMeters > 30f) return false
        val ant = ultimoBueno
        if (ant == null) {
            ultimoBueno = p
            recorrido += p
            ultimoMovimientoMs = p.timestamp
            return true
        }
        val d = distanciaKm(ant, p)
        if (d < 0.003) return false           // menos de 3 m: ruido del GPS
        // Saltos imposibles (> 60 km/h) se descartan.
        val dtS = ((p.timestamp - ant.timestamp) / 1000.0).coerceAtLeast(0.5)
        if (d / dtS * 3600 > 60) return false
        ultimoBueno = p
        recorrido += p
        ultimoMovimientoMs = p.timestamp
        // En pausa automatica el primer tramo al volver a moverse SI cuenta.
        if (!pausaManual) km += d
        return true
    }

    /**
     * Avanza un segundo. Devuelve los avisos que el entrenador debe decir
     * (vacio casi siempre).
     */
    fun tic(ahoraMs: Long, pulso: Int): List<String> {
        val avisos = mutableListOf<String>()
        if (usaGps && autoPausa && !pausaManual && ultimoMovimientoMs > 0) {
            val quieto = (ahoraMs - ultimoMovimientoMs) / 1000 >= segundosQuieto
            if (quieto && !pausaAuto) { pausaAuto = true; avisos += FrasesCoach(idioma).pausaAuto }
            else if (!quieto && pausaAuto) { pausaAuto = false; avisos += FrasesCoach(idioma).seguimos }
        }
        if (pausaManual || pausaAuto) return avisos
        segundos++
        if (usaGps) {
            val kmEnteros = km.toInt()
            if (kmEnteros > kmAnunciados) {
                kmAnunciados = kmEnteros
                ritmoUltimoKm = segundos - segundosAlUltimoKm
                segundosAlUltimoKm = segundos
                avisos += fraseKm(kmEnteros, segundos, ritmoUltimoKm, pulso, idioma)
            }
        } else {
            val bloques = segundos / 300          // cada 5 minutos
            if (bloques > minutosAnunciados) {
                minutosAnunciados = bloques
                avisos += fraseTiempo(segundos, pulso, idioma)
            }
        }
        return avisos
    }

    /** Si el cronometro se reanuda tras la pausa manual, cuenta como movimiento. */
    fun reanudar(ahoraMs: Long) {
        pausaManual = false
        pausaAuto = false
        ultimoMovimientoMs = ahoraMs
    }

    fun estado() = EntrenoEstado(
        tipo = tipo, segundos = segundos, km = km, pausaAuto = pausaAuto,
        pausaManual = pausaManual, ritmoUltimoKm = ritmoUltimoKm, recorrido = recorrido.toList()
    )

    /** Recupera un entreno tras un cierre del proceso. */
    fun restaurar(segundos: Int, puntos: List<TrackPoint>) {
        this.segundos = segundos
        puntos.forEach { punto(it) }
        kmAnunciados = km.toInt()
        segundosAlUltimoKm = segundos
    }

    companion object {
        fun distanciaKm(a: TrackPoint, b: TrackPoint): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }

        /** "12 minutos 5 segundos" (para la voz, sin abreviaturas). En espanol. */
        fun duracionHablada(seg: Int): String = FrasesCoach("es").duracion(seg)

        fun fraseKm(km: Int, totalSeg: Int, ritmoSeg: Int, pulso: Int, idioma: String = "es"): String =
            FrasesCoach(idioma).km(km, totalSeg, ritmoSeg, pulso)

        fun fraseTiempo(totalSeg: Int, pulso: Int, idioma: String = "es"): String =
            FrasesCoach(idioma).tiempo(totalSeg, pulso)
    }
}
