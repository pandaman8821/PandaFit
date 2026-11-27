// ============================================================================
//  Panda Fit - BienvenidaScreen.kt
//  v26: BIENVENIDA de la primera vez (4 pasos cortos):
//    1. Hola (que hace la app),
//    2. Tu: sexo, edad y peso (zonas de pulso y kcal),
//    3. Metas: pasos, sueno y agua,
//    4. Tu pulsera: pasos para enlazarla y permisos (Bluetooth y avisos).
//  Todo se puede cambiar despues en Perfil. "Saltar" siempre visible.
// ============================================================================
package com.pandafit.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandafit.app.PandaFitApplication
import com.pandafit.app.R
import com.pandafit.app.data.model.GenderMode
import com.pandafit.app.ui.theme.PandaFitTheme
import java.text.NumberFormat

@Composable
fun BienvenidaScreen(onFinished: () -> Unit) {
    val app = remember { PandaFitApplication.get() }
    val prefs = remember { app.preferences }
    val context = LocalContext.current
    val lima = MaterialTheme.colorScheme.primary
    val tinta = lerp(lima, Color.Black, 0.8f)

    var paso by remember { mutableIntStateOf(0) }
    var mujer by remember { mutableStateOf(prefs.genderMode == GenderMode.WOMAN) }
    var edad by remember { mutableIntStateOf(prefs.userAge) }
    var peso by remember { mutableIntStateOf(prefs.userWeightKg) }
    var estatura by remember { mutableIntStateOf(prefs.userHeightCm) }
    var metaPasos by remember { mutableIntStateOf(prefs.stepGoal) }
    var metaSueno by remember { mutableFloatStateOf(prefs.sleepGoalHours) }
    var metaAgua by remember { mutableIntStateOf(prefs.waterGoalGlasses) }

    fun guardar() {
        prefs.genderMode = if (mujer) GenderMode.WOMAN else GenderMode.MAN
        prefs.userAge = edad
        prefs.userWeightKg = peso
        prefs.userHeightCm = estatura
        prefs.stepGoal = metaPasos
        prefs.sleepGoalHours = metaSueno
        prefs.waterGoalGlasses = metaAgua
    }
    fun terminar(buscar: Boolean = false) {
        guardar()
        prefs.onboardingDone = true
        if (buscar) runCatching {
            com.pandafit.app.ble.PandaFitBackgroundService.start(context)
            app.bleManager.startScan()
        }
        onFinished()
    }

    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        terminar(buscar = r.values.all { it })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp)) {
            // ---- puntos de progreso + Saltar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(if (i == paso) 26.dp else 10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (i <= paso) lima else MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                Text(stringResource(R.string.wl_skip), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { terminar() }.padding(10.dp))
            }
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = paso,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "bienvenida"
            ) { p ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (p) {
                        0 -> PasoHola()
                        1 -> {
                            Titulo(stringResource(R.string.wl_about_title), stringResource(R.string.wl_about_sub))
                            Text(stringResource(R.string.wl_sex), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Eleccion(stringResource(R.string.profile_mode_man), !mujer, lima, Modifier.weight(1f)) { mujer = false }
                                Eleccion(stringResource(R.string.profile_mode_woman), mujer, lima, Modifier.weight(1f)) { mujer = true }
                            }
                            Spacer(Modifier.height(18.dp))
                            Ajuste(stringResource(R.string.profile_age_title), "$edad", stringResource(R.string.profile_age_unit), edad.toFloat(), 12f..95f, 82, lima) { edad = it.toInt() }
                            Ajuste(stringResource(R.string.profile_weight_title), "$peso", "kg", peso.toFloat(), 30f..200f, 169, lima) { peso = it.toInt() }
                            Ajuste(stringResource(R.string.profile_height_title), "$estatura", "cm", estatura.toFloat(), 120f..220f, 99, lima) { estatura = it.toInt() }
                        }
                        2 -> {
                            val nf = remember { NumberFormat.getIntegerInstance() }
                            Titulo(stringResource(R.string.wl_goals_title), stringResource(R.string.wl_goals_sub))
                            Ajuste(stringResource(R.string.wl_steps_day), nf.format(metaPasos), stringResource(R.string.unit_steps), metaPasos.toFloat(), 2_000f..30_000f, 55, PandaFitTheme.colors.steps) {
                                metaPasos = (it / 500).toInt() * 500
                            }
                            Ajuste(stringResource(R.string.metric_sleep), "%.1f".format(metaSueno), stringResource(R.string.unit_hours), metaSueno, 5f..10f, 9, PandaFitTheme.colors.sleep) {
                                metaSueno = (it * 2).toInt() / 2f
                            }
                            Ajuste(stringResource(R.string.wl_water), "$metaAgua", stringResource(R.string.wl_water_unit), metaAgua.toFloat(), 4f..16f, 11, PandaFitTheme.colors.spo2) {
                                metaAgua = it.toInt()
                            }
                        }
                        else -> PasoPulsera(lima)
                    }
                }
            }

            // ---- botones de abajo
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (paso > 0) {
                    Text(stringResource(R.string.wl_back), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { paso-- }.padding(horizontal = 16.dp, vertical = 18.dp))
                }
                val textoPrincipal = when (paso) { 0 -> stringResource(R.string.wl_start); 3 -> stringResource(R.string.wl_find_band); else -> stringResource(R.string.wl_next) }
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(50))
                        .background(lima)
                        .clickable {
                            if (paso < 3) { guardar(); paso++ }
                            else {
                                val necesarios = buildList {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
                                    } else add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                permisos.launch(necesarios.toTypedArray())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(textoPrincipal, color = tinta, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (paso == 3) {
                Text(stringResource(R.string.wl_later), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).clickable { terminar() }.padding(12.dp))
            }
        }
    }
}

@Composable
private fun PasoHola() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.logo_panda),
            contentDescription = null,
            modifier = Modifier.size(210.dp).clip(RoundedCornerShape(48.dp))
        )
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.wl_hello), fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.wl_intro),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PasoPulsera(lima: Color) {
    Titulo(stringResource(R.string.wl_band_title), stringResource(R.string.wl_band_sub))
    val pasos = listOf(
        stringResource(R.string.wl_band_1),
        stringResource(R.string.wl_band_2),
        stringResource(R.string.wl_band_3),
        stringResource(R.string.wl_band_4)
    )
    pasos.forEachIndexed { i, t ->
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(lima.copy(alpha = 0.16f)).border(1.dp, lima, CircleShape),
                contentAlignment = Alignment.Center
            ) { Text("${i + 1}", color = lima, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.wl_no_band),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Titulo(t: String, sub: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun Eleccion(texto: String, sel: Boolean, lima: Color, modifier: Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(54.dp)
            .clip(forma)
            .background(if (sel) lima.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
            .border(1.5.dp, if (sel) lima else MaterialTheme.colorScheme.outlineVariant, forma)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(texto, fontWeight = FontWeight.Bold, color = if (sel) lima else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Ajuste(
    titulo: String, valor: String, unidad: String, actual: Float, rango: ClosedFloatingPointRange<Float>,
    pasos: Int, color: Color, onChange: (Float) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(titulo, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(valor, fontSize = 26.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(Modifier.width(6.dp))
            Text(unidad, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Slider(
            value = actual.coerceIn(rango), onValueChange = onChange, valueRange = rango, steps = pasos,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = color.copy(alpha = 0.2f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        )
    }
}
