// ============================================================================
//  Panda Fit - CompartirResultados.kt
//  "COMPARTIR MIS RESULTADOS" (v12): dibuja una imagen 1080x1350 (4:5, el
//  formato que mejor se ve en Instagram, WhatsApp, Facebook o X) con las
//  cifras de hoy en el diseno deportivo oscuro y abre el menu de compartir
//  de Android.
//
//  PRIVACIDAD: la imagen solo lleva cifras del dia (pasos, distancia,
//  calorias, sueno, pulso, presion, SpO2) y la fecha. Nunca el nombre, el
//  correo, la MAC del reloj ni la ubicacion. El usuario elige con quien
//  compartirla; la app no publica nada por su cuenta.
// ============================================================================
package com.pandafit.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.pandafit.app.R
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object CompartirResultados {

    /** Cifras que salen en la imagen (null = no se dibuja esa casilla). */
    data class Datos(
        val pasos: Int,
        val metaPasos: Int,
        val distanciaKm: Double,
        val kcal: Int,
        val sueno: String?,
        val pulso: Int?,
        val presion: String?,
        val spo2: Int?
    )

    private const val ANCHO = 1080
    private const val ALTO = 1350
    private const val FONDO = 0xFF0E1014.toInt()
    private const val TARJETA = 0xFF171A20.toInt()
    private const val LINEA = 0xFF2A2E36.toInt()
    private const val LIMA = 0xFF9BE15D.toInt()
    private const val TEXTO = 0xFFF3F4F6.toInt()
    private const val GRIS = 0xFF8B93A1.toInt()

    /** Crea la imagen y abre el menu de compartir. */
    fun compartir(context: Context, datos: Datos) {
        runCatching {
            val imagen = crearImagen(context, datos)
            val carpeta = File(context.cacheDir, "compartir").apply { mkdirs() }
            val archivo = File(carpeta, "pandafit_hoy.png")
            archivo.outputStream().use { imagen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.compartir", archivo)
            val envio = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(R.string.share_text, NumberFormat.getIntegerInstance().format(datos.pasos))
                )
                clipData = ClipData.newRawUri("Panda Fit", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val selector = Intent.createChooser(envio, context.getString(R.string.share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(selector)
        }.onFailure { Log.w("PandaFitShare", "No se pudo compartir: ${it.message}") }
    }

    /** Dibuja la tarjeta 1080x1350 con las cifras de hoy. */
    fun crearImagen(context: Context, d: Datos): Bitmap {
        val bmp = Bitmap.createBitmap(ANCHO, ALTO, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(FONDO)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val negrita = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val normal = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val nf = NumberFormat.getIntegerInstance()

        // ---- Cabecera: icono + "Panda Fit" + fecha --------------------------
        runCatching {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(132, 132)
        }.getOrNull()?.let { c.drawBitmap(it, 72f, 72f, p) }
        p.color = TEXTO; p.typeface = negrita; p.textSize = 60f
        c.drawText("Panda Fit", 228f, 128f, p)
        p.color = GRIS; p.typeface = normal; p.textSize = 36f
        val fecha = LocalDate.now()
            .format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        c.drawText(fecha, 228f, 182f, p)

        // ---- Tarjeta principal: anillo de pasos -----------------------------
        tarjeta(c, p, 72f, 260f, ANCHO - 72f, 720f)
        val cx = 300f
        val cy = 490f
        val r = 160f
        p.style = Paint.Style.STROKE; p.strokeWidth = 40f; p.strokeCap = Paint.Cap.ROUND
        p.color = LINEA
        c.drawCircle(cx, cy, r, p)
        val progreso = if (d.metaPasos > 0) (d.pasos.toFloat() / d.metaPasos).coerceIn(0f, 1f) else 0f
        p.color = LIMA
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * progreso, false, p)
        p.style = Paint.Style.FILL
        p.color = TEXTO; p.typeface = negrita; p.textSize = 54f; p.textAlign = Paint.Align.CENTER
        c.drawText("${(progreso * 100).toInt()}%", cx, cy + 20f, p)
        p.textAlign = Paint.Align.LEFT

        p.color = GRIS; p.typeface = normal; p.textSize = 38f
        c.drawText(context.getString(R.string.today_steps), 530f, 400f, p)
        p.color = TEXTO; p.typeface = negrita; p.textSize = 104f
        c.drawText(nf.format(d.pasos), 530f, 510f, p)
        p.color = LIMA; p.typeface = normal; p.textSize = 34f
        c.drawText(context.getString(R.string.today_steps_goal, nf.format(d.metaPasos)), 530f, 566f, p)
        p.color = GRIS; p.textSize = 32f
        c.drawText(
            String.format(Locale.US, "%.2f km  ·  %d kcal", d.distanciaKm, d.kcal),
            530f, 628f, p
        )

        // ---- Rejilla de 4 casillas ------------------------------------------
        val casillas = listOf(
            Triple(context.getString(R.string.today_sleep), d.sueno, 0xFFA78BFA.toInt()),
            Triple(context.getString(R.string.today_hr), d.pulso?.let { "$it ppm" }, 0xFFFF5C7A.toInt()),
            Triple(context.getString(R.string.today_bp), d.presion?.let { "$it mmHg" }, 0xFF5B9CFF.toInt()),
            Triple(context.getString(R.string.today_spo2), d.spo2?.let { "$it %" }, 0xFF38D3E8.toInt())
        )
        val anchoCasilla = (ANCHO - 72f * 2 - 24f) / 2f
        casillas.forEachIndexed { i, (titulo, valor, color) ->
            val col = i % 2
            val fila = i / 2
            val x = 72f + col * (anchoCasilla + 24f)
            val y = 752f + fila * (214f + 24f)
            tarjeta(c, p, x, y, x + anchoCasilla, y + 214f)
            p.color = color; p.typeface = normal; p.textSize = 34f
            c.drawText(titulo, x + 36f, y + 70f, p)
            val texto = valor ?: "--"
            p.color = TEXTO; p.typeface = negrita
            // Se reduce la letra hasta que el valor quepa en la casilla.
            p.textSize = 64f
            while (p.measureText(texto) > anchoCasilla - 72f && p.textSize > 30f) p.textSize -= 2f
            c.drawText(texto, x + 36f, y + 160f, p)
        }

        // ---- Pie ------------------------------------------------------------
        p.color = GRIS; p.typeface = normal; p.textSize = 30f; p.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(R.string.share_footer), ANCHO / 2f, ALTO - 64f, p)
        p.textAlign = Paint.Align.LEFT
        return bmp
    }

    private fun tarjeta(c: Canvas, p: Paint, l: Float, t: Float, r: Float, b: Float) {
        val rect = RectF(l, t, r, b)
        p.style = Paint.Style.FILL; p.color = TARJETA
        c.drawRoundRect(rect, 44f, 44f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = LINEA
        c.drawRoundRect(rect, 44f, 44f, p)
        p.style = Paint.Style.FILL
    }
}
