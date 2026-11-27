// ============================================================================
//  Panda Fit - LecturaSueno.kt
//  0.1.6: CUANDO pedir el sueno al reloj (comando 0x32), elegido por el usuario:
//  "cada hora hasta que me levante".
//    * Solo de 6:00 a 13:00 y como mucho UNA vez por hora.
//    * Deja de pedirlo en cuanto la lectura muestra que YA SE LEVANTO: el reloj
//      marca la hora de despertar, han pasado 20 min desde entonces y lleva
//      300 pasos o mas hoy. Normalmente 1-3 lecturas por manana.
//  Motivo: antes se leia 1 vez desde las 6:00 y los dias que el usuario se
//  levantaba mas tarde la noche quedaba a medias.
//  Seguridad (ver memoria del K56): nunca mas de 7 pedidos al dia y nunca
//  otros comandos; solo 0x32, el que lleva dias funcionando sin borrar nada.
//  Funciones puras: se prueban sin reloj.
// ============================================================================
package com.pandafit.app.ble

import java.time.LocalDateTime

object LecturaSueno {

    const val HORA_INICIO = 6
    const val HORA_FIN = 13          // a las 13:00 ya no se pide mas
    const val ENTRE_PEDIDOS_MS = 55 * 60 * 1000L
    const val PASOS_LEVANTADO = 300
    const val MIN_TRAS_DESPERTAR = 20

    /** true si ahora toca pedir el sueno al reloj. */
    fun tocaPedir(
        ahora: LocalDateTime,
        ultimoPedidoMs: Long,
        ahoraMs: Long,
        completoHoy: Boolean
    ): Boolean {
        if (completoHoy) return false
        if (ahora.hour < HORA_INICIO || ahora.hour >= HORA_FIN) return false
        return ahoraMs - ultimoPedidoMs >= ENTRE_PEDIDOS_MS
    }

    /**
     * true si la lectura ya es la definitiva: el usuario se levanto (el reloj
     * marca el despertar hace 20+ min) y ya camina (300+ pasos hoy).
     */
    fun lecturaCompleta(despertarMin: Int?, minutoAhora: Int, pasosHoy: Int): Boolean {
        if (despertarMin == null || despertarMin < 0) return false
        // Despertar "en el futuro" o justo ahora = el reloj aun cree que duermes.
        if (minutoAhora < despertarMin + MIN_TRAS_DESPERTAR) return false
        return pasosHoy >= PASOS_LEVANTADO
    }
}
