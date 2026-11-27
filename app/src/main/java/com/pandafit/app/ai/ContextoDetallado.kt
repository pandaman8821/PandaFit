// ============================================================================
//  Panda Fit - ContextoDetallado.kt
//  Resume en texto las MEDICIONES DETALLADAS de hoy para que la IA razone con
//  algo mas que los totales: fases del sueno, horas mas activas, pulso
//  minimo/medio/maximo, presion y SpO2 con su hora.
//
//  Solo cifras anonimas: ni nombre, ni MAC, ni ubicacion, ni cuenta.
//  Es una funcion pura (sin Android) para poder probarla.
// ============================================================================
package com.pandafit.app.ai

import com.pandafit.app.ble.BleProtocol
import com.pandafit.app.data.DayLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ContextoDetallado {

    private val HORA = DateTimeFormatter.ofPattern("HH:mm")

    private fun hhmm(minuto: Int): String =
        if (minuto < 0) "?" else "%02d:%02d".format(minuto / 60 % 24, minuto % 60)

    private fun horaDe(ms: Long, zona: ZoneId): String =
        HORA.format(Instant.ofEpochMilli(ms).atZone(zona))

    fun resumen(
        log: DayLog.Snapshot,
        sueno: BleProtocol.SuenoBanda?,
        zona: ZoneId = ZoneId.systemDefault()
    ): String = buildString {
        if (sueno != null && sueno.dormidoMin > 0) {
            appendLine(
                "- Sueño de anoche (medido por la pulsera): ${sueno.dormidoMin / 60} h ${sueno.dormidoMin % 60} min " +
                    "dormido; profundo ${sueno.profundoMin} min, ligero ${sueno.ligeroMin} min, " +
                    "despierto ${sueno.despiertoMin} min; se durmio a las ${hhmm(sueno.dormirMin)} y " +
                    "desperto a las ${hhmm(sueno.despertarMin)}; calidad estimada ${sueno.calidad}/100."
            )
        }
        if (log.hayPasosPorHora) {
            val top = log.pasosPorHora.withIndex()
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { "${"%02d".format(it.index)}h: ${it.value} pasos" }
            appendLine("- Horas con mas pasos hoy: $top.")
        }
        if (log.pulsos.isNotEmpty()) {
            val valores = log.pulsos.map { it.ppm }
            appendLine(
                "- Pulso hoy (${valores.size} mediciones): minimo ${valores.min()}, " +
                    "medio ${valores.average().toInt()}, maximo ${valores.max()} ppm."
            )
        }
        log.presion?.let {
            appendLine(
                "- Presion arterial: ${it.sistolica}/${it.diastolica} mmHg " +
                    "(medida a las ${horaDe(it.en, zona)})."
            )
        }
        log.spo2?.let {
            appendLine("- Oxigeno en sangre: ${it.valor} % (medido a las ${horaDe(it.en, zona)}).")
        }
        if (log.vasosAgua > 0) {
            appendLine(
                "- Agua apuntada hoy: ${log.vasosAgua} vasos de 250 ml " +
                    "(${"%.2f".format(java.util.Locale.US, log.vasosAgua * 0.25)} L)."
            )
        }
    }.trimEnd()
}
