// ============================================================================
//  Panda Fit - Racha.kt
//  RACHA: dias SEGUIDOS cumpliendo la meta de pasos.
//    * Si hoy ya se cumplio, cuenta desde hoy hacia atras.
//    * Si hoy aun no, la racha de ayer sigue viva (el dia no ha terminado).
//    * Los dias con cifras de EJEMPLO ("simulado") no cuentan: cortan la racha.
//  Funcion pura para poder probarla.
// ============================================================================
package com.pandafit.app.data

import com.pandafit.app.data.model.DailyMetrics
import java.time.LocalDate

object Racha {

    fun calcular(dias: List<DailyMetrics>, meta: Int, hoy: LocalDate = LocalDate.now()): Int {
        if (meta <= 0) return 0
        val porFecha = dias.associateBy { it.dateKey }
        fun cumplido(fecha: LocalDate): Boolean {
            val d = porFecha[fecha.toString()] ?: return false
            return d.source != "simulado" && d.steps >= meta
        }
        var dia = if (cumplido(hoy)) hoy else hoy.minusDays(1)
        var racha = 0
        while (cumplido(dia)) {
            racha++
            dia = dia.minusDays(1)
        }
        return racha
    }
}
