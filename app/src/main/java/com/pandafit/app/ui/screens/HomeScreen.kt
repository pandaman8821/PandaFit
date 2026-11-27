// ============================================================================
//  Panda Fit - HomeScreen.kt
//  PANTALLA PRINCIPAL: simple, grande y funcional (estilo DaFit), con acabado
//  Liquid Glass (refraction + rim light + glow).
//
//  Contenido, de arriba a abajo:
//    1. Saludo + chip de estado (banda BLE / nube / datos reales).
//    2. Anillo gigante de progreso de pasos con las 3 cifras clave.
//    3. BOTON NUEVO Y PROTAGONISTA: "Asistente IA Fitness" (cristal + glow).
//    4. Botones grandes: Empezar entrenamiento / Sincronizar.
//    5. Rejilla de metricas: distancia, calorias, sueno, pulso, oxigeno.
//    6. Tarjeta de Health Connect (datos reales) con un toque.
//    7. Consejo del analisis local (FitnessEngine) + privacidad.
// ============================================================================
package com.pandafit.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import java.text.NumberFormat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.NightlightRound
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pandafit.app.R
import com.pandafit.app.data.model.BleState
import com.pandafit.app.ui.components.PandaBigGlassButton
import com.pandafit.app.ui.components.PandaDataRow
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlassPill
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import com.pandafit.app.ui.components.PandaMetricCard
import com.pandafit.app.ui.components.PandaPrivacyBanner
import com.pandafit.app.ui.components.PandaProgressRing
import com.pandafit.app.ui.components.PandaSectionTitle
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.viewmodel.FirebaseHealthViewModel
import com.pandafit.app.viewmodel.PandaUiState
import java.time.LocalTime
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenAssistant: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenLogros: () -> Unit = {},
    viewModel: FirebaseHealthViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val dayLog by viewModel.dayLog.collectAsStateWithLifecycle()
    val bandSleep by viewModel.bandSleep.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Registro BLE de los ultimos segundos, para poder ver QUE pasa sin adb/Logcat.
    var bleLogLines by remember { mutableStateOf(listOf<String>()) }
    var showDiagnostic by remember { mutableStateOf(false) }
    var diagnosticText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        viewModel.bleLogs().collect { linea ->
            // Se guardan 30 para el informe; en pantalla solo se ven los 6 ultimos.
            bleLogLines = (bleLogLines + linea).takeLast(30)
        }
    }

    // Permiso de escaneo BLE: se pide al pulsar "Conectar banda".
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) viewModel.startBleScan()
    }
    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = viewModel.healthConnectHelper().permissionContract()
    ) { granted ->
        viewModel.onHealthConnectGranted(granted.isNotEmpty())
    }

    fun connectBand() {
        val needed: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.startBleScan()
        } else {
            blePermissionLauncher.launch(needed)
        }
    }

    val tokens = PandaFitTheme.colors

    LazyColumn(
        // statusBarsPadding: la app dibuja de borde a borde y la cabecera
        // ("Martes..." / "Hoy") quedaba debajo del reloj y la camara.
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------------------------------------------------------- CABECERA
        item { HomeHeader(state, syncMessage) }
        // 0.0.3: el tiempo y la ciudad, con consejo para entrenar hoy.
        item { ClimaCard() }

        // ------------------------------------------------------ CONECTIVIDAD
        // Arriba del todo: es lo primero que hay que tener bien (la pulsera
        // alimenta todas las tarjetas del panel "Hoy").
        item {
            ConnectionCard(
                state = state,
                presion = dayLog.presion,
                onConnect = { connectBand() },
                onDisconnect = viewModel::disconnectDevice,
                onStopScan = viewModel::stopBleScan,
                onSelectDevice = viewModel::connectDevice,
                onMeasureNow = viewModel::requestMeasurement,
                onSyncBand = viewModel::syncBand,
                onShowDiagnostic = {
                    diagnosticText = viewModel.bleDiagnostics()
                    showDiagnostic = true
                }
            )
        }

        // ------------------------------- REGISTRO BLE (diagnostico en pantalla)
        if (bleLogLines.isNotEmpty()) {
            item {
                PandaGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowAccent = MaterialTheme.colorScheme.primary,
                    glow = 0.14f
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ble_log_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            PandaGlassPill(
                                text = stringResource(R.string.ble_diag),
                                accent = MaterialTheme.colorScheme.primary,
                                onClick = {
                                    diagnosticText = viewModel.bleDiagnostics()
                                    showDiagnostic = true
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        bleLogLines.takeLast(6).forEach { linea ->
                            Text(
                                text = "\u2022 $linea",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------- DIAGNOSTICO BLE
        if (showDiagnostic) {
            item {
                PandaGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowAccent = MaterialTheme.colorScheme.tertiary,
                    glow = 0.26f
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ble_diag),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.ai_close),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showDiagnostic = false }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // El informe incluye los ultimos mensajes de la banda.
                        val informe = buildString {
                            append(diagnosticText.trimEnd())
                            if (bleLogLines.isNotEmpty()) {
                                appendLine()
                                appendLine()
                                appendLine("ULTIMOS MENSAJES:")
                                bleLogLines.forEach { appendLine("- $it") }
                            }
                        }
                        // Botones para sacar el informe de la app: antes era
                        // texto fijo y no habia forma de copiarlo ni enviarlo.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PandaGlassPill(
                                text = stringResource(R.string.ble_diag_copy),
                                icon = Icons.Filled.ContentCopy,
                                selected = true,
                                accent = MaterialTheme.colorScheme.primary,
                                onClick = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText("PandaFit BLE", informe))
                                    Toast.makeText(context, R.string.ble_diag_copied, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            PandaGlassPill(
                                text = stringResource(R.string.ble_diag_share),
                                icon = Icons.Filled.Share,
                                selected = true,
                                accent = MaterialTheme.colorScheme.tertiary,
                                onClick = {
                                    val envio = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "PandaFit - Diagnostico BLE")
                                        putExtra(Intent.EXTRA_TEXT, informe)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(envio, null))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer {
                            Text(
                                text = informe,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------ PANEL "HOY"
        // Tarjetas de cristal con ficha de icono flotante y grafica propia.
        // Cada tarjeta tiene SU boton; sin pulsera, el boton es "Conectar pulsera".
        val acciones = AccionesHoy(
            conectado = state.bleState == BleState.CONNECTED,
            onConnect = { connectBand() },
            onSyncBand = viewModel::syncBand,
            onReadSleep = viewModel::readBandSleep,
            onMeasureHeart = viewModel::measureHeartRate,
            onMeasureSpo2 = viewModel::measureSpo2,
            onMeasureBp = viewModel::measureBloodPressure
        )
        item { StepsTodayCard(state, dayLog, acciones) }
        item { SleepTodayCard(state, bandSleep, metaHoras = 8, acciones = acciones) }
        item { HeartZonesCard(state, dayLog, edad = viewModel.userAge, acciones = acciones) }
        item { BloodPressureCard(log = dayLog, acciones = acciones) }
        item { Spo2TodayCard(state, dayLog, acciones) }
        item {
            WaterTodayCard(
                vasos = dayLog.vasosAgua,
                meta = viewModel.waterGoal,
                onMenos = { viewModel.addWater(-1) },
                onMas = { viewModel.addWater(1) }
            )
        }
        item { HeartDayCard(dayLog, acciones) }
        item { ExerciseLinkCard(onOpenTraining = onOpenTraining) }
        // v26: medallas y retos de la semana.
        item { LogrosHomeCard(onOpen = onOpenLogros) }

        // ---------------------------------------- COMPARTIR MIS RESULTADOS
        // Crea una imagen con las cifras de hoy y abre el menu de Android
        // (Instagram, WhatsApp, Facebook, X...). Solo cifras, nada personal.
        item {
            ShareResultsButton {
                val sueno = bandSleep?.let { "${it.dormidoMin / 60}h ${it.dormidoMin % 60}m" }
                    ?: state.today.sleepHours.takeIf { it > 0 && state.usingRealData }
                        ?.let { "${it.toInt()}h ${((it - it.toInt()) * 60).toInt()}m" }
                val pulso = state.vitals.heartRate.takeIf { it > 0 } ?: dayLog.ultimoPulso?.valor
                com.pandafit.app.share.CompartirResultados.compartir(
                    context,
                    com.pandafit.app.share.CompartirResultados.Datos(
                        pasos = state.today.steps,
                        metaPasos = state.today.stepGoal,
                        distanciaKm = state.today.distanceKm,
                        kcal = state.today.caloriesKcal,
                        sueno = sueno,
                        pulso = pulso,
                        presion = dayLog.presion?.let { "${it.sistolica}/${it.diastolica}" },
                        spo2 = dayLog.spo2?.valor ?: state.vitals.spo2.takeIf { it > 0 }
                    )
                )
            }
        }

        // ------------------------------------- ASISTENTE IA + SINCRONIZAR
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PandaBigGlassButton(
                    text = stringResource(R.string.action_ai_assistant),
                    subtitle = stringResource(R.string.action_ai_assistant_sub),
                    glyph = PandaGlyph.IA,
                    accent = MaterialTheme.colorScheme.tertiary,
                    height = 84.dp,
                    onClick = onOpenAssistant
                )
                PandaBigGlassButton(
                    text = stringResource(R.string.action_sync_now),
                    subtitle = stringResource(R.string.profile_cloud_sub),
                    glyph = PandaGlyph.SINCRONIZAR,
                    accent = tokens.distance,
                    height = 76.dp,
                    onClick = { viewModel.syncNow() }
                )
            }
        }

        // -------------------------------------------------- HEALTH CONNECT
        item {
            HealthConnectCard(
                available = state.healthConnectAvailable,
                granted = state.healthConnectGranted,
                onConnect = {
                    val helper = viewModel.healthConnectHelper()
                    if (helper.isAvailable()) {
                        healthConnectLauncher.launch(helper.readPermissions)
                    } else {
                        onOpenHealth()
                    }
                }
            )
        }

        // -------------------------------------------------- CONSEJO LOCAL
        if (state.report.insights.isNotEmpty()) {
            item {
                PandaGlassCard(
                    glowAccent = MaterialTheme.colorScheme.secondary,
                    glow = 0.28f
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "\uD83E\uDDE0", fontSize = 20.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.home_score_title, state.report.score),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        state.report.insights.take(3).forEach { insight ->
                            PandaDataRow(
                                label = insight.title,
                                value = if (insight.severity >= 1) "!" else "\u2713",
                                accent = when (insight.severity) {
                                    2 -> MaterialTheme.colorScheme.error
                                    1 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.secondary
                                }
                            )
                            Text(
                                text = insight.recommendation.ifBlank { insight.detail },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 18.dp, bottom = 6.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        PandaGlassPill(
                            text = stringResource(R.string.home_ask_plan),
                            icon = Icons.Outlined.AutoAwesome,
                            accent = MaterialTheme.colorScheme.tertiary,
                            onClick = onOpenAssistant
                        )
                    }
                }
            }
        }

        item { PandaPrivacyBanner(text = com.pandafit.app.ai.PromptBuilder.PRIVACY_NOTICE) }
    }
}

// ============================================================================
//  SUBCOMPONENTES
// ============================================================================

@Composable
private fun HomeHeader(state: PandaUiState, syncMessage: String) {
    // Cabecera del panel: fecha completa ("martes, 17 de noviembre") + "Hoy".
    val greeting = java.time.LocalDate.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            // 0.0.4: la cara del panda elegida por el usuario (antes un emoji).
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.panda_cara),
                contentDescription = null,
                modifier = Modifier.size(46.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.today_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black
            )
            // v18: RACHA de dias seguidos cumpliendo la meta de pasos.
            val racha = com.pandafit.app.data.Racha.calcular(state.history.days, state.today.stepGoal)
            if (racha > 0) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(PandaFitTheme.colors.calories.copy(alpha = 0.16f))
                        .padding(start = 6.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PandaGlyphIcon(
                        glyph = PandaGlyph.EJERCICIO,
                        accent = MaterialTheme.colorScheme.surface,
                        principal = PandaFitTheme.colors.calories,
                        size = 18.dp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.home_streak, racha, racha),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PandaFitTheme.colors.calories
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            // Etiqueta de estado: solo aparece cuando hay algo que contar (banda
            // conectada o datos reales). Sin banda ni Health Connect no se marca
            // nada: se ha eliminado por completo la etiqueta "Demo".
            val etiqueta = when {
                state.bleState == BleState.CONNECTED -> stringResource(R.string.home_status_band_ok)
                state.usingRealData -> stringResource(R.string.home_status_real_data)
                else -> null
            }
            if (etiqueta != null) {
                StatusChip(
                    text = etiqueta,
                    accent = if (state.bleState == BleState.CONNECTED) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.height(6.dp))
            }
            // 0.0.5: sin Firebase configurado no hay nube: no se muestra "Nube lista"
            // ni "N pendiente(s) de subir" (solo los mensajes puntuales).
            val nubeActiva = remember { com.pandafit.app.PandaFitApplication.get().repository.firebaseReady }
            if (nubeActiva || syncMessage.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.online) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = if (state.online) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (syncMessage.isNotBlank()) syncMessage
                    else if (state.online) stringResource(R.string.home_cloud_ready)
                    else stringResource(R.string.home_no_network),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
    }
}

@Composable
private fun StepHeroCard(state: PandaUiState) {
    val tokens = PandaFitTheme.colors
    val infinite = rememberInfiniteTransition(label = "hero")
    val glowPulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2100), RepeatMode.Reverse),
        label = "glowPulse"
    )

    PandaGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 34.dp,
        glowAccent = tokens.steps,
        glow = 0.45f,
        padding = 20.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_steps_today),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.home_goal_label, state.today.stepGoal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PandaProgressRing(
                    progress = state.stepProgress,
                    accent = tokens.steps,
                    modifier = Modifier.size(158.dp),
                    strokeWidth = 14.dp
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            // 12.345 en vez de 12345: se lee de un vistazo.
                            text = NumberFormat.getIntegerInstance().format(state.today.steps),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = stringResource(R.string.home_steps_unit),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    HeroStat(
                        value = String.format(Locale.US, "%.2f", state.today.distanceKm),
                        label = stringResource(R.string.home_km_unit),
                        accent = tokens.distance
                    )
                    Spacer(Modifier.height(12.dp))
                    HeroStat(
                        value = state.today.caloriesKcal.toString(),
                        label = stringResource(R.string.home_kcal_unit),
                        accent = tokens.calories
                    )
                    Spacer(Modifier.height(12.dp))
                    HeroStat(
                        value = NumberFormat.getIntegerInstance().format(state.stepsLeft),
                        label = stringResource(R.string.home_steps_left_unit),
                        accent = if (state.stepsLeft == 0) tokens.distance else tokens.heart
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    state.stepsLeft == 0 -> stringResource(R.string.home_goal_done)
                    state.stepProgress > 0.6f -> stringResource(R.string.home_goal_close)
                    else -> stringResource(R.string.home_goal_start)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Honestidad: sin pulsera ni Health Connect las cifras son de
            // ejemplo. Se avisa con discrecion (no una etiqueta "Demo" gritona).
            if (!state.usingRealData) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_sample_data),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, accent: Color) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            fontWeight = FontWeight.Black
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionCard(
    state: PandaUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onMeasureNow: () -> Unit,
    onSyncBand: () -> Unit,
    onShowDiagnostic: () -> Unit,
    presion: com.pandafit.app.data.DayLog.Presion? = null
) {
    val tokens = PandaFitTheme.colors
    val connected = state.bleState == BleState.CONNECTED
    val scanning = state.bleState == BleState.SCANNING
    val busy = scanning ||
        state.bleState == BleState.CONNECTING ||
        state.bleState == BleState.RECONNECTING
    val statusColor by animateColorAsState(
        targetValue = when (state.bleState) {
            BleState.CONNECTED -> tokens.distance
            BleState.ERROR -> MaterialTheme.colorScheme.error
            BleState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "bleStatusColor"
    )
    // Latido suave del icono mientras busca o conecta.
    val pulse = rememberInfiniteTransition(label = "blePulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "blePulseScale"
    )

    PandaGlassCard(
        modifier = Modifier.fillMaxWidth(),
        glowAccent = statusColor,
        glow = if (connected) 0.30f else 0.20f
    ) {
        Column {
            // ---- Cabecera: icono de estado + texto + accion principal --------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(if (busy) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Icono propio: reloj con latido (se anima al buscar o con banda).
                    PandaGlyphIcon(
                        glyph = PandaGlyph.RELOJ,
                        accent = Color.White,
                        principal = statusColor,
                        size = 30.dp,
                        animado = busy || connected
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (state.bleState) {
                            BleState.CONNECTED -> stringResource(R.string.home_ble_connected)
                            BleState.SCANNING -> stringResource(R.string.home_ble_scanning)
                            BleState.CONNECTING -> stringResource(R.string.home_ble_connecting)
                            BleState.RECONNECTING -> stringResource(R.string.home_ble_reconnecting)
                            BleState.ERROR -> stringResource(R.string.home_ble_error)
                            BleState.DISCONNECTED -> stringResource(R.string.home_ble_disconnected)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            state.bleDeviceName.isNotBlank() -> state.bleDeviceName
                            state.bleDevices.isNotEmpty() -> pluralStringResource(
                                R.plurals.home_ble_devices_near, state.bleDevices.size, state.bleDevices.size
                            )
                            else -> stringResource(R.string.home_ble_hint)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    connected -> PandaGlassPill(
                        text = stringResource(R.string.home_disconnect),
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onDisconnect
                    )
                    scanning -> PandaGlassPill(
                        text = stringResource(R.string.home_stop_scan),
                        selected = true,
                        onClick = onStopScan
                    )
                    else -> PandaGlassPill(
                        text = stringResource(R.string.home_connect),
                        icon = Icons.Filled.Bluetooth,
                        selected = true,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = onConnect
                    )
                }
            }

            // ---- Barra de actividad mientras busca/conecta -------------------
            if (busy) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50)),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.28f)
                )
            }

            // ---- Lista de relojes encontrados (filas grandes, faciles de tocar)
            if (!connected && state.bleDevices.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.home_tap_to_connect),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.bleDevices.take(6).forEach { device ->
                        DeviceRow(
                            name = device.name,
                            address = device.address,
                            bars = device.signalBars,
                            accent = tokens.distance,
                            onClick = { onSelectDevice(device.address) }
                        )
                    }
                }
            } else if (scanning) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.home_ble_wake_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- Valores en vivo + acciones cuando hay banda ----------------
            val hayVitales = state.vitals.heartRate > 0 || presion != null
            if (connected || hayVitales) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VitalTile(
                        icon = PandaGlyph.PULSO,
                        value = state.vitals.heartRate.takeIf { it > 0 }?.toString() ?: "--",
                        unit = stringResource(R.string.metric_ppm),
                        accent = tokens.heart,
                        modifier = Modifier.weight(1f)
                    )
                    // v10: presion arterial en lugar de SpO2 (el SpO2 sigue en
                    // su propia tarjeta del panel "Hoy").
                    VitalTile(
                        icon = PandaGlyph.PRESION,
                        value = presion?.let { "${it.sistolica}/${it.diastolica}" } ?: "--",
                        unit = stringResource(R.string.today_bp_unit),
                        accent = Color(0xFF5B7CFA),
                        modifier = Modifier.weight(1f)
                    )
                    if (connected) {
                        VitalTile(
                            icon = PandaGlyph.BATERIA,
                            value = state.vitals.batteryPercent.takeIf { it >= 0 }?.let { "$it%" } ?: "--",
                            unit = stringResource(R.string.home_battery_unit),
                            accent = tokens.distance,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            // (Los botones "Sincronizar" y "Medir ahora" de aqui se quitaron:
            //  salian cortados y ya estan en las tarjetas de Pasos y Ritmo.)

            // ---- Diagnostico: discreto, siempre disponible -------------------
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.ble_diag) + "  ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onShowDiagnostic)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** Fila de un reloj encontrado: icono, nombre, MAC, senal y "Conectar". */
@Composable
private fun DeviceRow(
    name: String,
    address: String,
    bars: Int,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            PandaGlyphIcon(glyph = PandaGlyph.RELOJ, accent = Color.White, principal = accent, size = 24.dp, animado = false)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (name != address) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        SignalBars(bars = bars, accent = accent)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.home_device_connect),
            style = MaterialTheme.typography.labelLarge,
            color = accent
        )
    }
}

/** 4 barritas de cobertura (como las del telefono). */
@Composable
private fun SignalBars(bars: Int, accent: Color) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i <= bars) accent
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
            )
        }
    }
}

/** Baldosa compacta de un valor en vivo (pulso, SpO2, bateria). */
@Composable
private fun VitalTile(
    icon: PandaGlyph,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PandaGlyphIcon(glyph = icon, accent = Color.White, principal = accent, size = 24.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun HealthConnectCard(
    available: Boolean,
    granted: Boolean,
    onConnect: () -> Unit
) {
    PandaGlassCard(
        modifier = Modifier.fillMaxWidth(),
        glowAccent = MaterialTheme.colorScheme.secondary,
        glow = 0.22f
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "\uD83D\uDC9A", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.hc_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        granted -> stringResource(R.string.home_hc_connected)
                        available -> stringResource(R.string.home_hc_available)
                        else -> stringResource(R.string.home_hc_unavailable)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (available && !granted) {
                PandaGlassPill(
                    text = stringResource(R.string.home_hc_connect),
                    icon = Icons.Filled.Refresh,
                    selected = true,
                    onClick = onConnect
                )
            } else if (granted) {
                StatusChip(text = stringResource(R.string.home_hc_active), accent = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
