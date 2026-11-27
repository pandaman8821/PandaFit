// ============================================================================
//  Panda Fit - AvisosActividad.kt
//  AVISOS DE ACTIVIDAD (v18), se activan/desactivan en Perfil:
//    * META CUMPLIDA: una felicitacion al dia cuando los pasos llegan a la meta.
//    * MOVERSE: un recordatorio (como mucho uno por hora) si entre las 9:00 y
//      las 21:00 llevas la hora actual y la anterior casi sin pasos.
//  Todo se calcula en el telefono; no sale ningun dato.
// ============================================================================
package com.pandafit.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pandafit.app.MainActivity
import com.pandafit.app.R
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime

object AvisosActividad {

    private const val CANAL = "panda_fit_actividad"
    private const val ID_META = 5101
    private const val ID_MOVER = 5102
    private const val ID_AGUA = 5103
    private const val ID_DORMIR = 5104
    private const val ID_SEMANA = 5105
    private const val ID_SUENO_MANANA = 5106
    private const val PREFS = "panda_avisos"

    /** Umbral de "casi sin pasos" en una hora. */
    private const val PASOS_QUIETO = 150

    fun activos(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("activos", true)

    fun setActivos(context: Context, valor: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("activos", valor).apply()
    }

    /**
     * Regla del recordatorio de moverse (funcion pura, se prueba sin telefono).
     * @param horas pasos de cada hora de hoy (24 valores).
     */
    fun debeRecordarMoverse(horas: List<Int>, hora: Int, minuto: Int): Boolean {
        if (horas.size != 24 || hora !in 9..20 || minuto < 50) return false
        if (horas.sum() <= 0) return false               // sin podometro/pulsera hoy: no molestar
        val actual = horas[hora]
        val anterior = if (hora > 0) horas[hora - 1] else 0
        return actual < PASOS_QUIETO && anterior < PASOS_QUIETO
    }

    /**
     * Regla del recordatorio de agua (v20, funcion pura): de 9:00 a 21:00 la meta
     * se reparte por igual; si vas mas de un vaso por detras de ese ritmo, toca.
     */
    fun debeRecordarAgua(vasos: Int, meta: Int, hora: Int, minuto: Int): Boolean {
        if (meta <= 0 || vasos >= meta || hora !in 9..20) return false
        val horasPasadas = (hora - 9) + minuto / 60.0
        val esperado = meta * horasPasadas / 12.0
        return vasos < esperado - 1.0
    }

    /**
     * Hora ideal de acostarse (minuto del dia) para dormir [metaHoras] y
     * despertar a [despertarMin], con 15 min para conciliar el sueno.
     * Funcion pura (v21).
     */
    fun horaDeAcostarse(despertarMin: Int, metaHoras: Float): Int {
        val m = despertarMin - (metaHoras * 60).toInt() - 15
        return ((m % 1440) + 1440) % 1440
    }

    /** true si faltan entre 0 y 30 minutos para la hora de acostarse. */
    fun debeRecordarDormir(ahoraMin: Int, acostarseMin: Int): Boolean {
        val falta = ((acostarseMin - ahoraMin) % 1440 + 1440) % 1440
        return falta in 0..30
    }

    /** Resumen de una semana con datos REALES (v22). */
    data class Semana(val diasConDatos: Int, val mediaPasos: Int, val diasMeta: Int)

    /** Ultimos 7 dias hasta [hoy], sin dias de ejemplo; null si no hay ninguno real. */
    fun resumenSemana(dias: List<com.pandafit.app.data.model.DailyMetrics>, meta: Int, hoy: LocalDate = LocalDate.now()): Semana? {
        val desde = hoy.minusDays(6)
        val reales = dias.filter {
            it.source != "simulado" && runCatching { LocalDate.parse(it.dateKey) }.getOrNull()
                ?.let { f -> !f.isBefore(desde) && !f.isAfter(hoy) } == true
        }
        if (reales.isEmpty()) return null
        return Semana(
            diasConDatos = reales.size,
            mediaPasos = reales.sumOf { it.steps } / reales.size,
            diasMeta = reales.count { it.steps >= meta }
        )
    }

    /** Domingo de 19:00 a 21:59: una notificacion con el resumen de la semana. */
    /**
     * v26: aviso de la MANANA con el comentario de Panda sobre la noche
     * (una vez al dia, de 7 a 12 h, solo si ya hay sueno de anoche guardado).
     */
    fun revisarSuenoManana(context: Context, dias: List<ResumenDia>, metaHoras: Float) {
        if (!activos(context)) return
        val hoy = LocalDate.now()
        if (LocalTime.now().hour !in 7..11) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("sueno_avisado", null) == hoy.toString()) return
        if (dias.lastOrNull { it.fecha == hoy.toString() }?.sueno == null) return
        val a = SuenoSemana.analizar(dias, metaHoras, hoy) ?: return
        prefs.edit().putString("sueno_avisado", hoy.toString()).apply()
        notificar(context, ID_SUENO_MANANA, context.getString(R.string.sleep_m_title), SuenoSemana.avisoMananaLocal(context, a, metaHoras))
    }

    fun revisarSemanal(context: Context, dias: List<com.pandafit.app.data.model.DailyMetrics>, meta: Int, entrenos: Int) {
        if (!activos(context)) return
        val hoy = LocalDate.now()
        val ahora = LocalTime.now()
        if (hoy.dayOfWeek != java.time.DayOfWeek.SUNDAY || ahora.hour !in 19..21) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("semana_avisada", null) == hoy.toString()) return
        val s = resumenSemana(dias, meta, hoy) ?: return
        prefs.edit().putString("semana_avisada", hoy.toString()).apply()
        notificar(
            context, ID_SEMANA,
            context.getString(R.string.notif_week_title),
            context.getString(
                R.string.notif_week_text,
                NumberFormat.getIntegerInstance().format(s.mediaPasos), s.diasMeta, s.diasConDatos, entrenos
            )
        )
    }

    /** Se llama a menudo (cada pocos segundos): decide si toca avisar. */
    fun revisar(
        context: Context,
        pasosHoy: Int,
        meta: Int,
        pasosPorHora: List<Int>,
        vasosAgua: Int = -1,
        metaAgua: Int = 0,
        /** Minuto del dia en que suele despertar (de la pulsera); -1 si no se sabe. */
        despertarMin: Int = -1,
        metaSueno: Float = 8f
    ) {
        if (!activos(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hoy = LocalDate.now().toString()
        if (meta > 0 && pasosHoy >= meta && prefs.getString("meta_avisada", null) != hoy) {
            prefs.edit().putString("meta_avisada", hoy).apply()
            notificar(
                context, ID_META,
                context.getString(R.string.notif_goal_title),
                context.getString(R.string.notif_goal_text, NumberFormat.getIntegerInstance().format(pasosHoy))
            )
        }
        val ahora = LocalTime.now()
        val claveHora = "$hoy-${ahora.hour}"
        if (prefs.getString("mover_avisado", null) != claveHora &&
            debeRecordarMoverse(pasosPorHora, ahora.hour, ahora.minute)
        ) {
            prefs.edit().putString("mover_avisado", claveHora).apply()
            notificar(
                context, ID_MOVER,
                context.getString(R.string.notif_move_title),
                context.getString(R.string.notif_move_text)
            )
        }
        // Hora de dormir (v21): una vez por noche, 30 min antes de acostarse.
        if (despertarMin >= 0) {
            val acostarse = horaDeAcostarse(despertarMin, metaSueno)
            val ahoraMin = ahora.hour * 60 + ahora.minute
            if (prefs.getString("dormir_avisado", null) != hoy && debeRecordarDormir(ahoraMin, acostarse)) {
                prefs.edit().putString("dormir_avisado", hoy).apply()
                notificar(
                    context, ID_DORMIR,
                    context.getString(R.string.notif_sleep_title),
                    context.getString(
                        R.string.notif_sleep_text,
                        "%02d:%02d".format(acostarse / 60, acostarse % 60),
                        "%02d:%02d".format(despertarMin / 60, despertarMin % 60)
                    )
                )
            }
        }
        // Agua: como mucho un aviso cada 2 horas.
        val bloqueAgua = "$hoy-${ahora.hour / 2}"
        if (vasosAgua >= 0 && prefs.getString("agua_avisado", null) != bloqueAgua &&
            debeRecordarAgua(vasosAgua, metaAgua, ahora.hour, ahora.minute)
        ) {
            prefs.edit().putString("agua_avisado", bloqueAgua).apply()
            notificar(
                context, ID_AGUA,
                context.getString(R.string.notif_water_title),
                context.getString(R.string.notif_water_text, vasosAgua, metaAgua)
            )
        }
    }

    internal fun notificar(context: Context, id: Int, titulo: String, texto: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CANAL, context.getString(R.string.notif_channel_activity), NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val abrir = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
