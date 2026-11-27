// ============================================================================
//  Panda Fit - MapaRecorrido.kt
//  v26: MAPA DE CALLES real del entreno (OpenStreetMap via osmdroid: gratis,
//  sin clave de API). Tema oscuro (colores invertidos), ruta en el color de la
//  distancia, salida en lima y punto actual. Durante el entreno el mapa sigue
//  al corredor; en sesiones guardadas encuadra la ruta entera.
//
//  Privacidad: para pintar el mapa se descargan "baldosas" de la zona desde
//  los servidores de OpenStreetMap (como cualquier app de mapas). La ruta NO
//  se envia a ningun sitio: se dibuja en el propio telefono.
// ============================================================================
package com.pandafit.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pandafit.app.data.model.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.Overlay
import android.graphics.Canvas as AndroidCanvas

/**
 * @param puntos   recorrido (puede estar vacio).
 * @param posicion ultima posicion conocida (para centrar antes de moverse).
 * @param seguir   true durante el entreno: el mapa acompana al usuario.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapaRecorrido(
    puntos: List<TrackPoint>,
    posicion: TrackPoint?,
    colorRuta: Color,
    colorSalida: Color,
    seguir: Boolean,
    modifier: Modifier = Modifier,
    alto: Int = 220
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapa = remember {
        Configuration.getInstance().apply {
            // Politica de OpenStreetMap: identificar la app (sin datos personales).
            userAgentValue = "PandaFit/" + context.packageName
            load(context, context.getSharedPreferences("osmdroid", 0))
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(16.5)
            // Tema oscuro: se invierten los colores de las baldosas.
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.rgb(23, 26, 32)
            overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.rgb(42, 46, 54)
            // Dentro de una lista con scroll: el mapa se queda con los gestos.
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    val ruta = remember {
        Polyline(mapa).apply {
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        }
    }
    val marcas = remember { MarcasRuta() }
    val estado = remember { object { var encuadrado = false; var ultimoN = -1 } }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> mapa.onResume()
                Lifecycle.Event.ON_PAUSE -> mapa.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        mapa.overlays.add(ruta)
        mapa.overlays.add(marcas)
        onDispose {
            lifecycle.removeObserver(obs)
            mapa.onDetach()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(alto.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AndroidView(
            factory = { mapa },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                ruta.outlinePaint.color = colorRuta.toArgb()
                marcas.salida = puntos.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
                marcas.actual = (puntos.lastOrNull() ?: posicion)?.let { GeoPoint(it.lat, it.lon) }
                marcas.colorSalida = colorSalida.toArgb()
                marcas.colorActual = colorRuta.toArgb()
                if (puntos.size != estado.ultimoN) {
                    estado.ultimoN = puntos.size
                    ruta.setPoints(puntos.map { GeoPoint(it.lat, it.lon) })
                }
                val actual = marcas.actual
                when {
                    seguir && actual != null -> m.controller.animateTo(actual)
                    !seguir && puntos.size >= 2 && !estado.encuadrado -> {
                        estado.encuadrado = true
                        val caja = BoundingBox.fromGeoPoints(puntos.map { GeoPoint(it.lat, it.lon) })
                        m.post { runCatching { m.zoomToBoundingBox(caja.increaseByScale(1.35f), false) } }
                    }
                    actual != null && !estado.encuadrado -> {
                        estado.encuadrado = true
                        m.controller.setCenter(actual)
                    }
                }
                m.invalidate()
            }
        )
        // Atribucion obligatoria de OpenStreetMap.
        Text(
            "© OpenStreetMap",
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        if (puntos.isEmpty() && posicion == null) {
            Text(
                androidx.compose.ui.res.stringResource(com.pandafit.app.R.string.map_waiting_gps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** Punto de salida (lima) y posicion actual (blanco con borde de color). */
private class MarcasRuta : Overlay() {
    var salida: GeoPoint? = null
    var actual: GeoPoint? = null
    var colorSalida = android.graphics.Color.GREEN
    var colorActual = android.graphics.Color.CYAN
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = android.graphics.Point()

    override fun draw(c: AndroidCanvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val d = mapView.resources.displayMetrics.density
        salida?.let {
            mapView.projection.toPixels(it, p)
            pincel.color = colorSalida
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * d, pincel)
        }
        actual?.let {
            mapView.projection.toPixels(it, p)
            pincel.color = android.graphics.Color.WHITE
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 8 * d, pincel)
            pincel.color = colorActual
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 4.5f * d, pincel)
        }
    }
}
