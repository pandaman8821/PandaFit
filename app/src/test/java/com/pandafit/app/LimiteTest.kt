// ============================================================================
//  Panda Fit - LimiteTest.kt
//  0.1.2: lectura de la respuesta "limite de uso" (429) de Google.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.Limite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiteTest {

    private val porMinuto = """
        {"error":{"code":429,"message":"You exceeded your current quota. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 5, model: gemini-4-hydrogen. Please retry in 21.6s.",
        "status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
        {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"21s"}]}}
    """.trimIndent()

    private val porDia = """{"error":{"code":429,"message":"Quota exceeded","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""

    @Test
    fun `lee cuanto hay que esperar`() {
        assertEquals(21, Limite.segundosDeEspera(porMinuto))
        assertEquals(13, Limite.segundosDeEspera("Rate limit reached. Please try again in 12.5s."))
        assertNull(Limite.segundosDeEspera(porDia))
    }

    @Test
    fun `distingue limite diario y por minuto`() {
        assertFalse(Limite.esDiario(porMinuto))
        assertTrue(Limite.esDiario(porDia))
    }

    @Test
    fun `mensaje legible del proveedor`() {
        assertTrue(Limite.mensajeProveedor(porMinuto)!!.startsWith("You exceeded your current quota"))
    }
}
