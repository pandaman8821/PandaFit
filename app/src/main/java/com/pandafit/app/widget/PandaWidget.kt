// ============================================================================
//  Panda Fit - PandaWidget.kt
//  WIDGET DE PANTALLA DE INICIO (v22): pasos de hoy, barra de la meta, agua y
//  racha, sin abrir la app. Al tocarlo se abre Panda Fit.
//  Lo actualizan el servicio en segundo plano y la app cada minuto; el sistema
//  ademas lo refresca cada 30 min (updatePeriodMillis).
// ============================================================================
package com.pandafit.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pandafit.app.MainActivity
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.R
import com.pandafit.app.data.Racha
import java.text.NumberFormat

class PandaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        actualizarDesdeApp(context)
    }

    companion object {
        /** Lee los datos de la app (pasos medidos, agua, racha) y pinta los widgets. */
        fun actualizarDesdeApp(context: Context) {
            runCatching {
                val app = context.applicationContext as? PandaFitApplication ?: return
                val pasos = maxOf(app.phoneSteps.pasosHoy.value, app.bleManager.stepsFromBand.value ?: 0)
                val meta = app.preferences.stepGoal
                val log = app.dayLog.estado.value
                val racha = Racha.calcular(app.preferences.cachedHistory.days, meta)
                actualizar(context, pasos, meta, log.vasosAgua, app.preferences.waterGoalGlasses, racha)
            }
        }

        fun actualizar(context: Context, pasos: Int, meta: Int, vasos: Int, metaAgua: Int, racha: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PandaWidget::class.java))
            if (ids.isEmpty()) return
            val nf = NumberFormat.getIntegerInstance()
            val vistas = RemoteViews(context.packageName, R.layout.widget_pandafit).apply {
                setTextViewText(R.id.widget_steps, if (pasos > 0) nf.format(pasos) else "--")
                setTextViewText(R.id.widget_goal, context.getString(R.string.widget_goal, nf.format(meta)))
                setProgressBar(R.id.widget_progress, 100, if (meta > 0) (pasos * 100 / meta).coerceIn(0, 100) else 0, false)
                setTextViewText(R.id.widget_water, context.getString(R.string.widget_water, vasos, metaAgua))
                setTextViewText(
                    R.id.widget_streak,
                    if (racha > 0) context.resources.getQuantityString(R.plurals.home_streak, racha, racha) else ""
                )
                val abrir = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setOnClickPendingIntent(R.id.widget_root, abrir)
            }
            manager.updateAppWidget(ids, vistas)
        }
    }
}
