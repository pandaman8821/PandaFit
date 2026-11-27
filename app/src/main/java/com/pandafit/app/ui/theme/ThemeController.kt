// ============================================================================
//  Panda Fit - ThemeController.kt
//  Controlador observable del TEMA: permite cambiar claro/oscuro/automatico y
//  el color dinamico desde la pantalla de Perfil sin reiniciar la app.
// ============================================================================
package com.pandafit.app.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeController {

    /** -1 = seguir al sistema (automatico), 0 = claro, 1 = oscuro. */
    private val _mode = MutableStateFlow(-1)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(false)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun update(mode: Int, dynamicColor: Boolean) {
        _mode.value = mode
        _dynamicColor.value = dynamicColor
    }
}
