// ============================================================================
//  Panda Fit - ProfileScreen.kt
//  PERFIL Y AJUSTES (v17, diseno deportivo oscuro):
//    * tarjeta de perfil con el panda, la puntuacion y el estado de la pulsera,
//    * "Tu cuerpo": perfil hombre/mujer y edad (zonas de pulso),
//    * metas de pasos y sueno con la cifra en grande,
//    * conexiones (pulsera, Health Connect, red, GPS) con chips de estado,
//    * nube, asistente IA (proveedor ACTIVO, no solo Gemini) y privacidad.
// ============================================================================
package com.pandafit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pandafit.app.BuildConfig
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.R
import com.pandafit.app.data.model.BleState
import com.pandafit.app.data.model.GenderMode
import com.pandafit.app.ui.components.PandaGlassCard
import com.pandafit.app.ui.components.PandaGlassPill
import com.pandafit.app.ui.components.PandaGlyph
import com.pandafit.app.ui.components.PandaGlyphIcon
import com.pandafit.app.ui.components.PandaPrivacyBanner
import com.pandafit.app.ui.components.PandaSectionTitle
import com.pandafit.app.ui.navigation.PandaScreen
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.viewmodel.FirebaseHealthViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: FirebaseHealthViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = PandaFitTheme.colors
    val lima = MaterialTheme.colorScheme.primary
    val app = remember { PandaFitApplication.get() }
    val prefs = remember { app.preferences }

    var stepGoal by remember { mutableIntStateOf(prefs.stepGoal) }
    var userAge by remember { mutableIntStateOf(prefs.userAge) }
    var userWeight by remember { mutableIntStateOf(prefs.userWeightKg) }
    var userHeight by remember { mutableIntStateOf(prefs.userHeightCm) }
    var sleepGoal by remember { mutableFloatStateOf(prefs.sleepGoalHours) }
    var cloudSync by remember { mutableStateOf(prefs.cloudSyncEnabled) }
    var avisos by remember { mutableStateOf(com.pandafit.app.data.AvisosActividad.activos(app)) }
    // IA: proveedor ACTIVO (antes solo se miraba la clave de Gemini).
    val proveedor = remember { prefs.aiProvider }
    var claveMasked by remember { mutableStateOf(prefs.maskedApiKey(proveedor)) }
    val nf = NumberFormat.getIntegerInstance()

    PandaScreen { modifier ->
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // --------------------------------------------------------- CABECERA
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.profile_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f)
                    )
                    PandaGlassPill(text = stringResource(R.string.action_back), onClick = onBack)
                }
            }

            // -------------------------------------------- TARJETA DE PERFIL
            item {
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(lima.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            // 0.0.4: la cara del panda elegida por el usuario.
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.panda_cara),
                                contentDescription = null,
                                modifier = Modifier.size(60.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.profile_health_score, state.report.score),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val conectada = state.bleState == BleState.CONNECTED
                                ChipEstado(
                                    if (conectada) stringResource(R.string.profile_chip_band_on)
                                    else stringResource(R.string.profile_chip_band_off),
                                    if (conectada) tokens.distance else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                ChipEstado(
                                    if (state.online) stringResource(R.string.profile_state_connected)
                                    else stringResource(R.string.profile_state_no_network),
                                    if (state.online) lima else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // --------------------------------------------------------- TU CUERPO
            item { PandaSectionTitle(text = stringResource(R.string.profile_body)) }
            item {
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
                    Column {
                        Text(
                            stringResource(R.string.profile_mode_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OpcionGrande(
                                stringResource(R.string.profile_mode_man),
                                state.gender == GenderMode.MAN, lima, Modifier.weight(1f)
                            ) { viewModel.setGender(GenderMode.MAN) }
                            OpcionGrande(
                                stringResource(R.string.profile_mode_woman),
                                state.gender == GenderMode.WOMAN, lima, Modifier.weight(1f)
                            ) { viewModel.setGender(GenderMode.WOMAN) }
                        }
                        Spacer(Modifier.height(16.dp))
                        AjusteConValor(
                            titulo = stringResource(R.string.profile_age_title),
                            valor = "$userAge",
                            unidad = stringResource(R.string.profile_age_unit),
                            color = tokens.heart
                        )
                        Slider(
                            value = userAge.toFloat(),
                            onValueChange = { userAge = it.toInt() },
                            onValueChangeFinished = { viewModel.setUserAge(userAge) },
                            valueRange = 12f..95f,
                            steps = 82,
                            colors = coloresSlider(tokens.heart)
                        )
                        Text(
                            stringResource(R.string.profile_age_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        // v19: peso para estimar las kcal del entrenamiento.
                        AjusteConValor(
                            titulo = stringResource(R.string.profile_weight_title),
                            valor = "$userWeight",
                            unidad = "kg",
                            color = tokens.calories
                        )
                        Slider(
                            value = userWeight.toFloat(),
                            onValueChange = { userWeight = it.toInt() },
                            onValueChangeFinished = { prefs.userWeightKg = userWeight },
                            valueRange = 30f..200f,
                            steps = 169,
                            colors = coloresSlider(tokens.calories)
                        )
                        Text(
                            stringResource(R.string.profile_weight_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        // 0.1.7: estatura -> distancia de los pasos e IMC.
                        AjusteConValor(
                            titulo = stringResource(R.string.profile_height_title),
                            valor = "$userHeight",
                            unidad = "cm",
                            color = tokens.distance
                        )
                        Slider(
                            value = userHeight.toFloat(),
                            onValueChange = { userHeight = it.toInt() },
                            onValueChangeFinished = { prefs.userHeightCm = userHeight },
                            valueRange = 120f..220f,
                            steps = 99,
                            colors = coloresSlider(tokens.distance)
                        )
                        Text(
                            stringResource(R.string.profile_height_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        val imc = com.pandafit.app.data.Cuerpo.imc(userWeight, userHeight)
                        val categoria = when (com.pandafit.app.data.Cuerpo.categoria(imc)) {
                            com.pandafit.app.data.Cuerpo.CategoriaImc.BAJO_PESO -> stringResource(R.string.profile_bmi_under)
                            com.pandafit.app.data.Cuerpo.CategoriaImc.SALUDABLE -> stringResource(R.string.profile_bmi_normal)
                            com.pandafit.app.data.Cuerpo.CategoriaImc.SOBREPESO -> stringResource(R.string.profile_bmi_over)
                            com.pandafit.app.data.Cuerpo.CategoriaImc.OBESIDAD -> stringResource(R.string.profile_bmi_obese)
                        }
                        val zancada = (com.pandafit.app.data.Cuerpo.zancadaM(userHeight, state.gender == GenderMode.WOMAN) * 100).toInt()
                        Text(
                            stringResource(R.string.profile_bmi_line, String.format(Locale.getDefault(), "%.1f", imc), categoria, zancada),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = tokens.distance
                        )
                    }
                }
            }

            // -------------------------------------------------------------- METAS
            item { PandaSectionTitle(text = stringResource(R.string.profile_goals)) }
            item {
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
                    Column {
                        AjusteConValor(
                            titulo = stringResource(R.string.today_steps),
                            valor = nf.format(stepGoal),
                            unidad = stringResource(R.string.profile_per_day),
                            color = tokens.steps,
                            glyph = PandaGlyph.PASOS
                        )
                        Slider(
                            value = stepGoal.toFloat(),
                            onValueChange = { stepGoal = (it / 500).toInt() * 500 },
                            onValueChangeFinished = { viewModel.setStepGoal(stepGoal) },
                            valueRange = 2_000f..30_000f,
                            steps = 55,
                            colors = coloresSlider(tokens.steps)
                        )
                        Spacer(Modifier.height(10.dp))
                        AjusteConValor(
                            titulo = stringResource(R.string.today_sleep),
                            valor = String.format(Locale.US, "%.1f", sleepGoal),
                            unidad = stringResource(R.string.profile_hours_night),
                            color = tokens.sleep,
                            glyph = PandaGlyph.SUENO
                        )
                        Slider(
                            value = sleepGoal,
                            onValueChange = { sleepGoal = (it * 10).toInt() / 10f },
                            onValueChangeFinished = { prefs.sleepGoalHours = sleepGoal },
                            valueRange = 5f..11f,
                            steps = 11,
                            colors = coloresSlider(tokens.sleep)
                        )
                        Spacer(Modifier.height(12.dp))
                        // v18: avisos de actividad (meta cumplida + moverse).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.profile_reminders),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.profile_reminders_sub),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = avisos,
                                onCheckedChange = {
                                    avisos = it
                                    com.pandafit.app.data.AvisosActividad.setActivos(app, it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = lima
                                )
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------- CONEXIONES
            item { PandaSectionTitle(text = stringResource(R.string.profile_connections)) }
            item {
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 8.dp) {
                    Column {
                        val conectada = state.bleState == BleState.CONNECTED
                        FilaConexion(
                            PandaGlyph.RELOJ, tokens.distance, stringResource(R.string.profile_conn_ble),
                            if (conectada) state.bleDeviceName.ifBlank { stringResource(R.string.profile_state_connected) }
                            else stringResource(R.string.profile_chip_band_off),
                            conectada
                        )
                        Divisor()
                        FilaConexion(
                            PandaGlyph.SALUD, MaterialTheme.colorScheme.secondary, stringResource(R.string.profile_conn_hc),
                            when {
                                state.healthConnectGranted -> stringResource(R.string.profile_state_authorized)
                                state.healthConnectAvailable -> stringResource(R.string.profile_state_available)
                                else -> stringResource(R.string.profile_state_unavailable)
                            },
                            state.healthConnectGranted
                        )
                        Divisor()
                        FilaConexion(
                            PandaGlyph.SINCRONIZAR, lima, stringResource(R.string.profile_conn_net),
                            if (state.online) stringResource(R.string.profile_state_connected)
                            else stringResource(R.string.profile_state_no_network),
                            state.online
                        )
                        Divisor()
                        FilaConexion(
                            PandaGlyph.RUTA, tokens.calories, stringResource(R.string.profile_conn_gps),
                            if (state.lastTrackPoint != null) stringResource(R.string.profile_state_active)
                            else stringResource(R.string.profile_state_waiting),
                            state.lastTrackPoint != null
                        )
                    }
                }
            }

            // -------------------------------------------------------------- NUBE
            item { PandaSectionTitle(text = stringResource(R.string.profile_data_cloud)) }
            item {
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.profile_cloud),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.profile_cloud_sub),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = cloudSync,
                                onCheckedChange = {
                                    cloudSync = it
                                    viewModel.setCloudSync(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = lima
                                )
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        BotonLinea(stringResource(R.string.profile_sync_now), PandaGlyph.SINCRONIZAR, lima) {
                            viewModel.syncNow()
                        }
                    }
                }
            }

            // ------------------------------- v26: HEALTH CONNECT, COPIA E INFORME
            item { PandaSectionTitle(text = stringResource(R.string.data_title)) }
            item { TarjetaTusDatos(app, lima) }

            // ------------------------------------------------- ASISTENTE IA / CLAVE
            item { PandaSectionTitle(text = stringResource(R.string.profile_ai_privacy)) }
            item {
                val violeta = MaterialTheme.colorScheme.tertiary
                PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(violeta.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                PandaGlyphIcon(PandaGlyph.IA, MaterialTheme.colorScheme.surface, principal = violeta, size = 32.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    proveedor.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    prefs.getModel(proveedor).ifBlank { stringResource(R.string.profile_model_auto) },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ChipEstado(
                                if (claveMasked.isNotBlank()) claveMasked else stringResource(R.string.profile_api_key_none),
                                if (claveMasked.isNotBlank()) violeta else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.profile_key_where),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (claveMasked.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            BotonLinea(stringResource(R.string.profile_delete_key), null, MaterialTheme.colorScheme.error) {
                                prefs.clearApiKey(proveedor)
                                claveMasked = ""
                            }
                        }
                    }
                }
            }

            item { PandaPrivacyBanner(text = stringResource(R.string.ai_privacy_body)) }

            item {
                Text(
                    "Panda Fit ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// ============================================================================
//  PIEZAS
// ============================================================================

@Composable
private fun coloresSlider(color: Color) = SliderDefaults.colors(
    thumbColor = color,
    activeTrackColor = color,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
)

/** Chip pequeno de estado. */
@Composable
private fun ChipEstado(texto: String, color: Color) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Opcion grande seleccionable (perfil hombre / mujer). */
@Composable
private fun OpcionGrande(texto: String, seleccionada: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(forma)
            .background(if (seleccionada) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (seleccionada) color else MaterialTheme.colorScheme.outlineVariant, forma)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (seleccionada) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Titulo del ajuste a la izquierda y su valor grande a la derecha. */
@Composable
private fun AjusteConValor(titulo: String, valor: String, unidad: String, color: Color, glyph: PandaGlyph? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) {
            PandaGlyphIcon(glyph, MaterialTheme.colorScheme.surface, principal = color, size = 26.dp, animado = false)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            titulo,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(valor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.width(4.dp))
        Text(unidad, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Fila de una conexion: icono, nombre y chip de estado (verde si va bien). */
@Composable
private fun FilaConexion(glyph: PandaGlyph, color: Color, titulo: String, estado: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            PandaGlyphIcon(glyph, MaterialTheme.colorScheme.surface, principal = color, size = 26.dp, animado = ok)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            titulo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        ChipEstado(estado, if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divisor() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * v26: enviar a Health Connect (Google Fit / Samsung Health), copia de
 * seguridad (guardar / restaurar) e informe PDF del mes para el medico.
 */
@Composable
private fun TarjetaTusDatos(app: PandaFitApplication, lima: Color) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs = app.preferences
    var enviarHc by remember { mutableStateOf(prefs.healthConnectWrite) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var restaurarPendiente by remember { mutableStateOf<android.net.Uri?>(null) }

    fun enviarAhora() {
        scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.pandafit.app.health.EnvioHealthConnect.enviar(app)
            }
            mensaje = if (n > 0) context.getString(R.string.hc_sent, n) else context.getString(R.string.hc_nothing)
        }
    }

    val permisoHc = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { concedidos ->
        val ok = concedidos.containsAll(com.pandafit.app.health.EnvioHealthConnect.permisos)
        enviarHc = ok
        prefs.healthConnectWrite = ok
        if (ok) enviarAhora() else mensaje = context.getString(R.string.hc_no_permission)
    }
    val guardar = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) mensaje = runCatching {
            com.pandafit.app.data.CopiaSeguridad.guardarEn(context, uri); context.getString(R.string.backup_saved)
        }.getOrElse { context.getString(R.string.backup_save_failed, it.message ?: "") }
    }
    val abrir = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restaurarPendiente = uri }

    PandaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, glow = 0f, padding = 16.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hc_send_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.hc_send_sub),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enviarHc,
                    onCheckedChange = { activar ->
                        if (!activar) { enviarHc = false; prefs.healthConnectWrite = false; return@Switch }
                        val sdk = androidx.health.connect.client.HealthConnectClient.getSdkStatus(context)
                        if (sdk != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                            mensaje = context.getString(R.string.hc_unavailable)
                        } else permisoHc.launch(com.pandafit.app.health.EnvioHealthConnect.permisos)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = lima)
                )
            }
            if (enviarHc) {
                Spacer(Modifier.height(10.dp))
                BotonLinea(stringResource(R.string.hc_send_now), PandaGlyph.SINCRONIZAR, lima) { enviarAhora() }
            }
            Spacer(Modifier.height(14.dp))
            Divisor()
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.backup_sub),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    BotonLinea(stringResource(R.string.backup_save), PandaGlyph.COMPARTIR, lima) {
                        guardar.launch("PandaFit-copia-${java.time.LocalDate.now()}.json")
                    }
                }
                Box(Modifier.weight(1f)) {
                    BotonLinea(stringResource(R.string.backup_restore), PandaGlyph.SINCRONIZAR, PandaFitTheme.colors.distance) {
                        abrir.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Divisor()
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.report_sub),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            BotonLinea(stringResource(R.string.report_create), PandaGlyph.SALUD, PandaFitTheme.colors.heart) {
                app.archivarHoy()
                val ok = runCatching {
                    com.pandafit.app.share.InformePdf.compartir(
                        context,
                        com.pandafit.app.share.InformePdf.Datos(
                            app.archivo.dias.value, prefs.userAge, prefs.userWeightKg, prefs.stepGoal, prefs.sleepGoalHours
                        )
                    )
                }.getOrElse { mensaje = context.getString(R.string.report_failed, it.message ?: ""); return@BotonLinea }
                if (!ok) mensaje = context.getString(R.string.report_empty)
            }
            mensaje?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = lima)
            }
        }
    }

    restaurarPendiente?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { restaurarPendiente = null },
            title = { Text(stringResource(R.string.backup_restore_q)) },
            text = { Text(stringResource(R.string.backup_restore_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    restaurarPendiente = null
                    runCatching { com.pandafit.app.data.CopiaSeguridad.restaurarDe(context, uri) }
                        .onSuccess {
                            // Reinicio limpio para que toda la app lea los datos restaurados.
                            val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            if (i != null) context.startActivity(i)
                            Runtime.getRuntime().exit(0)
                        }
                        .onFailure { mensaje = it.message ?: context.getString(R.string.backup_restore_failed) }
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { restaurarPendiente = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Boton de contorno del color (acciones secundarias). */
@Composable
private fun BotonLinea(texto: String, glyph: PandaGlyph?, color: Color, onClick: () -> Unit) {
    val forma = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(forma)
            .border(1.5.dp, color, forma)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (glyph != null) {
            PandaGlyphIcon(glyph, MaterialTheme.colorScheme.surface, principal = color, size = 22.dp, animado = false)
            Spacer(Modifier.width(8.dp))
        }
        Text(texto, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
    }
}
