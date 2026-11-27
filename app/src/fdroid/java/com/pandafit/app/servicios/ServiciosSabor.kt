// ============================================================================
//  Panda Fit - ServiciosSabor.kt (version F-DROID)
//  100 % software libre: sin Firebase ni Google Play Services. Los datos se
//  quedan en el telefono y la ubicacion la da el propio Android.
// ============================================================================
package com.pandafit.app.servicios

import android.app.Application
import android.content.Context

object ServiciosSabor {

    const val LIBRE = true

    fun iniciar(app: Application) {}

    fun nube(context: Context): NubeRemota = NubeApagada

    fun ubicacion(context: Context): FuenteUbicacion = UbicacionAndroid(context)
}
