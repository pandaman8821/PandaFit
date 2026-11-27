// ============================================================================
//  Panda Fit - InformePdf.kt
//  v26: INFORME PDF de los ultimos 30 dias para ensenarselo al medico:
//  resumen (pasos, sueno, pulso, presion, SpO2), graficas por dia y la tabla
//  con TODAS las presiones medidas y su clasificacion orientativa.
//  Se genera en el telefono (sin internet) y se abre el menu de compartir para
//  guardarlo, imprimirlo o enviarlo. No lleva nombre ni datos de contacto.
// ============================================================================
package com.pandafit.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.pandafit.app.data.ResumenDia
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object InformePdf {

    private const val ANCHO = 595   // A4 en puntos
    private const val ALTO = 842
    private const val MARGEN = 40f

    data class Datos(
        val dias: List<ResumenDia>,
        val edad: Int,
        val pesoKg: Int,
        val metaPasos: Int,
        val metaSueno: Float
    )

    /** Clasificacion orientativa (AHA 2017); NO es un diagnostico. */
    fun clasificar(s: Int, d: Int): String = when {
        s >= 180 || d >= 120 -> "Muy alta: consultar ya"
        s >= 140 || d >= 90 -> "Alta (grado 2)"
        s >= 130 || d >= 80 -> "Alta (grado 1)"
        s >= 120 -> "Elevada"
        s < 90 || d < 60 -> "Baja"
        else -> "Normal"
    }

    /** Crea el PDF y abre "Compartir". Devuelve false si no hay datos. */
    fun compartir(context: Context, datos: Datos): Boolean {
        val archivo = generar(context, datos) ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.compartir", archivo)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Informe de salud (Panda Fit)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(envio, "Guardar o enviar el informe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    fun generar(context: Context, datos: Datos, hoy: LocalDate = LocalDate.now()): File? {
        val desde = hoy.minusDays(29)
        val dias = datos.dias.filter {
            val f = runCatching { LocalDate.parse(it.fecha) }.getOrNull() ?: return@filter false
            f in desde..hoy
        }.sortedBy { it.fecha }
        if (dias.isEmpty()) return null

        val doc = PdfDocument()
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es"))
        val corto = DateTimeFormatter.ofPattern("d/M")
        var numPagina = 1
        var pagina = doc.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, numPagina).create())
        var c = pagina.canvas
        var y = MARGEN

        val titulo = Paint().apply { color = Color.rgb(20, 22, 26); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val sub = Paint().apply { color = Color.rgb(110, 115, 125); textSize = 10f; isAntiAlias = true }
        val texto = Paint().apply { color = Color.rgb(30, 32, 38); textSize = 11f; isAntiAlias = true }
        val negrita = Paint(texto).apply { typeface = Typeface.DEFAULT_BOLD }
        val seccion = Paint().apply { color = Color.rgb(20, 22, 26); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val acento = Color.rgb(95, 160, 40)

        fun nuevaPagina() {
            doc.finishPage(pagina)
            numPagina++
            pagina = doc.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, numPagina).create())
            c = pagina.canvas
            y = MARGEN
        }
        fun asegurar(alto: Float) { if (y + alto > ALTO - MARGEN) nuevaPagina() }

        // ---------------------------------------------------------- CABECERA
        c.drawRect(0f, 0f, ANCHO.toFloat(), 6f, Paint().apply { color = acento })
        y += 14f
        c.drawText("Informe de salud", MARGEN, y, titulo); y += 18f
        c.drawText("Del ${desde.format(fmt)} al ${hoy.format(fmt)} · ${dias.size} días con datos · Generado con Panda Fit",
            MARGEN, y, sub); y += 14f
        c.drawText("Edad: ${datos.edad} años · Peso: ${datos.pesoKg} kg · Medido con pulsera y teléfono.", MARGEN, y, sub); y += 12f
        c.drawText("Valores orientativos de un dispositivo de consumo: no sustituyen a una medición clínica.", MARGEN, y, sub); y += 24f

        // ----------------------------------------------------------- RESUMEN
        val presiones = dias.flatMap { d -> d.presiones.map { d.fecha to it } }
        val conPulso = dias.filter { it.pulsoMedio > 0 }
        val conSueno = dias.filter { (it.sueno?.dormidoMin ?: 0) > 0 }
        val conSpo2 = dias.filter { it.spo2 > 0 }
        val conPasos = dias.filter { it.pasos > 0 }
        c.drawText("Resumen", MARGEN, y, seccion); y += 18f
        fun fila(etiqueta: String, valor: String) {
            c.drawText(etiqueta, MARGEN, y, texto); c.drawText(valor, MARGEN + 190f, y, negrita); y += 16f
        }
        if (presiones.isNotEmpty()) {
            val ms = presiones.map { it.second.sistolica }.average().roundToInt()
            val md = presiones.map { it.second.diastolica }.average().roundToInt()
            val max = presiones.maxBy { it.second.sistolica }.second
            fila("Presión arterial media", "$ms/$md mmHg  (${clasificar(ms, md)})")
            fila("Presión más alta", "${max.sistolica}/${max.diastolica} mmHg · ${presiones.size} mediciones")
        } else fila("Presión arterial", "Sin mediciones en este periodo")
        if (conPulso.isNotEmpty()) {
            fila("Pulso medio", "${conPulso.map { it.pulsoMedio }.average().roundToInt()} ppm")
            fila("Pulso mínimo / máximo", "${conPulso.filter { it.pulsoMin > 0 }.minOfOrNull { it.pulsoMin } ?: "-"} / ${conPulso.maxOf { it.pulsoMax }} ppm")
        }
        if (conSueno.isNotEmpty()) {
            val m = conSueno.map { it.sueno!!.dormidoMin }.average().roundToInt()
            fila("Sueño medio", "${m / 60} h ${m % 60} min (${conSueno.size} noches, meta ${datos.metaSueno} h)")
        }
        if (conSpo2.isNotEmpty()) fila("Oxígeno en sangre (SpO2)", "media ${conSpo2.map { it.spo2 }.average().roundToInt()} % · mínimo ${conSpo2.minOf { it.spo2 }} %")
        if (conPasos.isNotEmpty()) fila("Pasos al día", "media ${conPasos.map { it.pasos }.average().roundToInt()} (meta ${datos.metaPasos})")
        y += 14f

        // ---------------------------------------------------------- GRAFICAS
        val etiquetas = (0..29).map { desde.plusDays(it.toLong()) }
        val porFecha = dias.associateBy { it.fecha }
        fun grafica(nombre: String, unidad: String, color: Int, referencia: Float?, valor: (ResumenDia) -> Float?, rango: ((ResumenDia) -> Pair<Float, Float>?)? = null) {
            asegurar(150f)
            c.drawText(nombre, MARGEN, y, seccion); y += 8f
            val izq = MARGEN + 28f; val der = ANCHO - MARGEN; val arriba = y + 6f; val abajo = y + 110f
            val vals = etiquetas.mapNotNull { porFecha[it.toString()]?.let(valor) }
            val altos = etiquetas.mapNotNull { porFecha[it.toString()]?.let { d -> rango?.invoke(d)?.second } }
            if (vals.isEmpty()) { c.drawText("Sin datos", izq, arriba + 50f, sub); y = abajo + 26f; return }
            val maxV = (listOfNotNull(vals.maxOrNull(), altos.maxOrNull(), referencia).max() * 1.1f).coerceAtLeast(1f)
            val minV = 0f
            val eje = Paint().apply { this.color = Color.rgb(220, 222, 226); strokeWidth = 0.8f }
            listOf(0f, 0.5f, 1f).forEach { f ->
                val yy = abajo - (abajo - arriba) * f
                c.drawLine(izq, yy, der, yy, eje)
                c.drawText("${(minV + (maxV - minV) * f).roundToInt()}", MARGEN - 4f, yy + 3f, sub)
            }
            referencia?.let { r ->
                val yy = abajo - (abajo - arriba) * (r / maxV)
                val p = Paint().apply { this.color = Color.rgb(200, 80, 80); strokeWidth = 1f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f) }
                c.drawLine(izq, yy, der, yy, p)
            }
            val paso = (der - izq) / 30f
            val barra = Paint().apply { this.color = color; isAntiAlias = true }
            val linea = Paint().apply { this.color = color; strokeWidth = 2f; isAntiAlias = true }
            etiquetas.forEachIndexed { i, f ->
                val d = porFecha[f.toString()] ?: return@forEachIndexed
                val x = izq + paso * i + paso / 2
                rango?.invoke(d)?.let { (a, b) ->
                    c.drawLine(x, abajo - (abajo - arriba) * (a / maxV), x, abajo - (abajo - arriba) * (b / maxV), linea)
                }
                val v = valor(d) ?: return@forEachIndexed
                if (rango == null) c.drawRect(x - paso * 0.32f, abajo - (abajo - arriba) * (v / maxV), x + paso * 0.32f, abajo, barra)
                else c.drawCircle(x, abajo - (abajo - arriba) * (v / maxV), 2.6f, barra)
                if (i % 5 == 0) c.drawText(f.format(corto), x - 8f, abajo + 12f, sub)
            }
            c.drawText(unidad, der - 40f, arriba - 2f, sub)
            y = abajo + 30f
        }
        grafica("Presión sistólica (media del día y rango)", "mmHg", Color.rgb(220, 70, 100), 130f,
            { d -> d.presiones.takeIf { it.isNotEmpty() }?.map { it.sistolica }?.average()?.toFloat() },
            { d -> d.presiones.takeIf { it.isNotEmpty() }?.let { p -> p.minOf { it.diastolica }.toFloat() to p.maxOf { it.sistolica }.toFloat() } })
        grafica("Pulso (medio del día, mínimo-máximo)", "ppm", Color.rgb(230, 90, 120), null,
            { d -> d.pulsoMedio.takeIf { it > 0 }?.toFloat() },
            { d -> if (d.pulsoMin > 0 && d.pulsoMax > 0) d.pulsoMin.toFloat() to d.pulsoMax.toFloat() else null })
        grafica("Horas de sueño", "h", Color.rgb(140, 110, 230), datos.metaSueno,
            { d -> d.sueno?.dormidoMin?.takeIf { it > 0 }?.let { it / 60f } })
        grafica("Pasos", "pasos", acento, datos.metaPasos.toFloat(), { d -> d.pasos.takeIf { it > 0 }?.toFloat() })

        // ------------------------------------------- TABLA DE PRESIONES
        if (presiones.isNotEmpty()) {
            asegurar(60f)
            c.drawText("Todas las mediciones de presión", MARGEN, y, seccion); y += 18f
            fun cabecera() {
                c.drawText("Fecha", MARGEN, y, negrita); c.drawText("Hora", MARGEN + 110f, y, negrita)
                c.drawText("Sistólica / Diastólica", MARGEN + 170f, y, negrita); c.drawText("Clasificación", MARGEN + 330f, y, negrita)
                y += 14f
            }
            cabecera()
            presiones.forEach { (fecha, p) ->
                if (y + 14f > ALTO - MARGEN) { nuevaPagina(); cabecera() }
                c.drawText(runCatching { LocalDate.parse(fecha).format(fmt) }.getOrDefault(fecha), MARGEN, y, texto)
                c.drawText("%d:%02d".format(p.minuto / 60, p.minuto % 60), MARGEN + 110f, y, texto)
                c.drawText("${p.sistolica} / ${p.diastolica} mmHg", MARGEN + 170f, y, texto)
                c.drawText(clasificar(p.sistolica, p.diastolica), MARGEN + 330f, y, texto)
                y += 14f
            }
        }
        doc.finishPage(pagina)

        val carpeta = File(context.cacheDir, "compartir").apply { mkdirs() }
        val archivo = File(carpeta, "PandaFit-informe-${hoy}.pdf")
        archivo.outputStream().use { doc.writeTo(it) }
        doc.close()
        return archivo
    }
}
