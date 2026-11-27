// ============================================================================
//  Panda Fit - RedTest.kt
//  0.0.7: el motivo de "sin conexion" se explica bien.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.AiError
import com.pandafit.app.ai.Red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RedTest {

    private val dns = UnknownHostException(
        "Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname"
    )

    @Test
    fun `dns fallido con el movil conectado es la app bloqueada`() {
        assertEquals(Red.Fallo.APP_BLOQUEADA, Red.clasificar(dns, telefonoOnline = true))
        assertEquals(Red.Fallo.SIN_INTERNET, Red.clasificar(dns, telefonoOnline = false))
        // El error del chat llega envuelto con el texto "Sin conexion".
        assertEquals(Red.Fallo.APP_BLOQUEADA, Red.clasificar(AiError.Network(dns.message!!), true))
    }

    @Test
    fun `lento y errores del servicio`() {
        assertEquals(Red.Fallo.LENTO, Red.clasificar(SocketTimeoutException("timeout"), true))
        assertEquals(Red.Fallo.SERVICIO, Red.clasificar(IllegalStateException("HTTP 403 key invalid"), true))
    }

    @Test
    fun `los mensajes estan en espanol y sin jerga`() {
        val m = Red.mensaje(Red.Fallo.APP_BLOQUEADA, "uso la voz del teléfono")
        assertTrue(m.contains("Ajustes > Apps > Panda Fit"))
        assertTrue(!m.contains("resolve", ignoreCase = true))
    }
}
