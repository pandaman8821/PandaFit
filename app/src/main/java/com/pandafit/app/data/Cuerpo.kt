// ============================================================================
//  Panda Fit - Cuerpo.kt
//  0.1.7: calculos con la ESTATURA del usuario (Perfil):
//    * longitud del paso (zancada) -> distancia de los pasos mas real
//      (antes se usaban 75 cm fijos para todo el mundo),
//    * IMC (indice de masa corporal) y su categoria orientativa (OMS).
//  Funciones puras: se prueban sin telefono.
// ============================================================================
package com.pandafit.app.data

import kotlin.math.roundToInt

object Cuerpo {

    enum class CategoriaImc { BAJO_PESO, SALUDABLE, SOBREPESO, OBESIDAD }

    /** Longitud de un paso al caminar, en metros (estatura x 0,415 / 0,413). */
    fun zancadaM(estaturaCm: Int, mujer: Boolean): Double =
        estaturaCm.coerceIn(120, 220) * (if (mujer) 0.413 else 0.415) / 100.0

    /** Distancia de [pasos] en km con la zancada del usuario (2 decimales). */
    fun kmDePasos(pasos: Int, estaturaCm: Int, mujer: Boolean): Double =
        (pasos * zancadaM(estaturaCm, mujer) / 1000.0 * 100).roundToInt() / 100.0

    /** IMC = peso / estatura^2, con 1 decimal. */
    fun imc(pesoKg: Int, estaturaCm: Int): Double {
        val m = estaturaCm.coerceIn(120, 220) / 100.0
        return (pesoKg / (m * m) * 10).roundToInt() / 10.0
    }

    fun categoria(imc: Double): CategoriaImc = when {
        imc < 18.5 -> CategoriaImc.BAJO_PESO
        imc < 25.0 -> CategoriaImc.SALUDABLE
        imc < 30.0 -> CategoriaImc.SOBREPESO
        else -> CategoriaImc.OBESIDAD
    }
}
