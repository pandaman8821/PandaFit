// ============================================================================
//  Panda Fit - PandaFitNavigation.kt
//  GRAFO DE NAVEGACION + barra inferior de cristal (estilo DaFit, botones
//  grandes) + envoltorio comun de pantalla con aura liquida de fondo.
// ============================================================================
package com.pandafit.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pandafit.app.R
import com.pandafit.app.ai.AIAssistantScreen
import com.pandafit.app.ui.screens.HealthScreen
import com.pandafit.app.ui.screens.HomeScreen
import com.pandafit.app.ui.screens.ProfileScreen
import com.pandafit.app.ui.screens.SplashScreen
import com.pandafit.app.ui.screens.TrainingScreen
import com.pandafit.app.ui.theme.PandaFitTheme
import com.pandafit.app.ui.components.NavIcono
import com.pandafit.app.ui.components.PandaNavIcon
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import com.pandafit.app.ui.theme.liquidAura
import com.pandafit.app.ui.theme.liquidGlass

object PandaRoutes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val TRAINING = "training"
    const val AI = "ai_assistant"
    const val HEALTH = "health"
    const val PROFILE = "profile"
    const val LOGROS = "logros"
    const val BIENVENIDA = "bienvenida"
}

private data class PandaTab(val route: String, val labelRes: Int, val icono: NavIcono)

// v23: iconos premium de trazo fino / relleno (PandaNavIcons.kt).
private val PANDA_TABS = listOf(
    PandaTab(PandaRoutes.HOME, R.string.nav_home, NavIcono.INICIO),
    PandaTab(PandaRoutes.TRAINING, R.string.nav_training, NavIcono.ENTRENAR),
    PandaTab(PandaRoutes.AI, R.string.nav_ai, NavIcono.IA),
    PandaTab(PandaRoutes.HEALTH, R.string.nav_health, NavIcono.SALUD),
    PandaTab(PandaRoutes.PROFILE, R.string.nav_profile, NavIcono.PERFIL)
)

/**
 * Pantalla completa de Panda Fit: aura de fondo + contenido + barra inferior.
 */
@Composable
fun PandaScreen(
    title: String? = null,
    scrollable: Boolean = true,
    content: @Composable (Modifier) -> Unit
) {
    val tokens = PandaFitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .liquidAura()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
                )
            }
            content(Modifier.fillMaxSize())
        }
    }
}

/** Barra inferior de cristal con 5 destinos. */
@Composable
private fun PandaBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit
) {
    val barShape = RoundedCornerShape(28.dp)
    val tinta = MaterialTheme.colorScheme.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            // v23: barra flotante "premium": sombra suave, fondo solido y un
            // filo fino; sin brillos ni cristal.
            .shadow(18.dp, barShape, clip = false,
                ambientColor = Color.Black, spotColor = Color.Black)
            .background(MaterialTheme.colorScheme.surface, barShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), barShape)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PANDA_TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            val accent = MaterialTheme.colorScheme.primary
            val gris = MaterialTheme.colorScheme.onSurfaceVariant
            // Pequeno "pop" al seleccionar, sin rebotes exagerados.
            val salto by animateFloatAsState(
                targetValue = if (selected) 1.06f else 1f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                label = "tabPop"
            )
            val anchoPastilla by animateDpAsState(
                targetValue = if (selected) 54.dp else 34.dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                label = "tabPill"
            )
            val fondoPastilla by animateColorAsState(
                targetValue = if (selected) accent else accent.copy(alpha = 0f),
                animationSpec = tween(220),
                label = "tabPillColor"
            )
            val colorIcono by animateColorAsState(
                targetValue = if (selected) tinta else gris,
                animationSpec = tween(220),
                label = "tabIconColor"
            )
            // Cada destino recibe el MISMO ancho (weight) y su etiqueta se
            // dibuja en una sola linea con elipsis: antes los textos se
            // solapaban entre si ("Asistente IA" encima de "Entrenar").
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 4.dp)
            ) {
                // Activa: pastilla lima solida con el icono RELLENO en tinta oscura.
                // Inactivas: icono de trazo fino en gris.
                Box(
                    modifier = Modifier
                        .size(width = anchoPastilla, height = 32.dp)
                        .graphicsLayer { scaleX = salto; scaleY = salto }
                        .clip(RoundedCornerShape(16.dp))
                        .background(fondoPastilla),
                    contentAlignment = Alignment.Center
                ) {
                    PandaNavIcon(
                        icono = tab.icono,
                        seleccionado = selected,
                        color = colorIcono,
                        size = 22.dp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else gris,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Host de navegacion de Panda Fit.
 */
@Composable
fun PandaFitNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null &&
        currentRoute != PandaRoutes.SPLASH &&
        currentRoute != PandaRoutes.BIENVENIDA &&
        currentRoute != PandaRoutes.AI

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = PandaRoutes.SPLASH,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(320)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(260)) },
            popExitTransition = {
                slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(260))
            }
        ) {
            composable(PandaRoutes.SPLASH) {
                SplashScreen(onFinished = {
                    // v26: la primera vez se muestra la bienvenida (edad, peso, metas, pulsera).
                    val primeraVez = !com.pandafit.app.PandaFitApplication.get().preferences.onboardingDone
                    navController.navigate(if (primeraVez) PandaRoutes.BIENVENIDA else PandaRoutes.HOME) {
                        popUpTo(PandaRoutes.SPLASH) { inclusive = true }
                    }
                })
            }
            composable(PandaRoutes.HOME) {
                HomeScreen(
                    onOpenAssistant = { navController.navigate(PandaRoutes.AI) },
                    onOpenTraining = { navController.navigate(PandaRoutes.TRAINING) },
                    onOpenHealth = { navController.navigate(PandaRoutes.HEALTH) },
                    onOpenLogros = { navController.navigate(PandaRoutes.LOGROS) }
                )
            }
            composable(PandaRoutes.LOGROS) {
                com.pandafit.app.ui.screens.LogrosScreen(onBack = { navController.popBackStack() })
            }
            composable(PandaRoutes.BIENVENIDA) {
                com.pandafit.app.ui.screens.BienvenidaScreen(onFinished = {
                    navController.navigate(PandaRoutes.HOME) {
                        popUpTo(PandaRoutes.BIENVENIDA) { inclusive = true }
                    }
                })
            }
            composable(PandaRoutes.TRAINING) {
                TrainingScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = PandaRoutes.AI,
                arguments = listOf(navArgument("prompt") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) {
                AIAssistantScreen(onBack = { navController.popBackStack() })
            }
            composable(PandaRoutes.HEALTH) {
                HealthScreen(onBack = { navController.popBackStack() })
            }
            composable(PandaRoutes.PROFILE) {
                ProfileScreen(onBack = { navController.popBackStack() })
            }
        }

        if (showBottomBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                PandaBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(PandaRoutes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    }
}
