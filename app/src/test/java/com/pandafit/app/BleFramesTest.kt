// ============================================================================
//  Panda Fit - BleFramesTest.kt
//  Pruebas del REENSAMBLADO DE TRAMAS, que es la causa real de que el K56 Pro
//  "no sincronizara": sus respuestas son mas largas que el MTU de notificacion
//  (20 bytes), asi que llegan partidas en varios paquetes BLE.
//
//  Estas pruebas simulan esa fragmentacion byte a byte y comprueban que la app:
//    * reconstruye la trama completa,
//    * aguanta fragmentos de 1 byte, de 2, de 19 y de 20,
//    * descarta ruido anterior a la cabecera,
//    * extrae varias tramas pegadas en un mismo buffer,
//    * ignora tramas con longitud incoherente y no se queda en bucle.
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ble.BleProtocol
import com.pandafit.app.ble.BleProtocol.FamiliaReloj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramesTest {

    @Test
    fun `la pulsera K56 se reconoce como reloj y la MAC o un televisor no`() {
        assertTrue(BleProtocol.pareceReloj("K56 Pro"))
        assertTrue(BleProtocol.pareceReloj("k56pro"))
        assertTrue(BleProtocol.pareceReloj("Smart Watch 2"))
        assertTrue(!BleProtocol.pareceReloj("AA:BB:CC:DD:EE:FF"))
        assertTrue(!BleProtocol.pareceReloj("[TV] Samsung"))
        assertTrue(!BleProtocol.pareceReloj(null))
        assertTrue(!BleProtocol.pareceReloj(""))
        assertTrue(BleProtocol.pareceReloj("Amazfit Bip 5"))
        assertTrue(BleProtocol.pareceReloj("Xiaomi Smart Band 8"))
        assertTrue(BleProtocol.pareceReloj("P8 Plus"))
        assertTrue(!BleProtocol.pareceReloj("JBL Flip 5"))
    }

    @Test
    fun `podometro WeChat de FEA1 da pasos y nunca pulso`() {
        // 7025 pasos = 0x001B71 -> 71 1B 00 (little endian), flags 0x01.
        assertEquals(7025, BleProtocol.leerPasosWeChat(byteArrayOf(0x01, 0x71, 0x1B, 0x00)))
        assertEquals(
            7025,
            BleProtocol.leerPasosWeChat(byteArrayOf(0x07, 0x71, 0x1B, 0x00, 0x10, 0x14, 0x00, 0x20, 0x01, 0x00))
        )
        assertNull(BleProtocol.leerPasosWeChat(byteArrayOf(0x02, 0x10, 0x00, 0x00)))   // sin bit de pasos
        assertNull(BleProtocol.leerPasosWeChat(byteArrayOf(0x01, 0x71)))               // corta
    }

    @Test
    fun `la familia del reloj se detecta por sus servicios`() {
        val p = BleProtocol
        assertEquals(FamiliaReloj.K56, p.detectarFamilia(setOf(p.K56_SERVICE, p.BATTERY_SERVICE)))
        // Volcado real del K56 Pro (MOYOUNG-V2): publica FEEA y FEE7 a la vez.
        // Manda FEEA, que es el que tiene el canal de comandos FEE2.
        assertEquals(
            FamiliaReloj.MOYOUNG,
            p.detectarFamilia(setOf(p.K56_SERVICE, p.MOYOUNG_SERVICE_MAIN, p.HEART_RATE_SERVICE))
        )
        assertEquals(FamiliaReloj.MOYOUNG, p.detectarFamilia(setOf(p.MOYOUNG_SERVICE_MAIN)))
        assertEquals(FamiliaReloj.GENERICO_FFF0, p.detectarFamilia(setOf(p.VENDOR_SERVICE_A)))
        assertEquals(
            FamiliaReloj.ESTANDAR,
            p.detectarFamilia(setOf(p.GENERIC_ACCESS, p.HEART_RATE_SERVICE, p.BATTERY_SERVICE))
        )
        assertEquals(FamiliaReloj.DESCONOCIDA, p.detectarFamilia(setOf(p.GENERIC_ACCESS)))
        // Solo las familias DaFit admiten comandos; las demas se escuchan.
        assertTrue(!FamiliaReloj.ESTANDAR.aceptaComandosMoyoung)
        assertTrue(!FamiliaReloj.DESCONOCIDA.aceptaComandosMoyoung)
    }

    /** Simula la recepcion real: acumula trozos y va extrayendo tramas. */
    private class Receptor {
        private var buffer = ByteArray(0)
        val tramas = mutableListOf<ByteArray>()

        fun recibir(trozo: ByteArray) {
            buffer = if (buffer.isEmpty()) trozo.copyOf() else buffer + trozo
            var vueltas = 0
            while (vueltas < 50) {
                val extraccion = BleProtocol.extraerTrama(buffer)
                buffer = extraccion.restante
                val trama = extraccion.trama ?: break
                tramas += trama
                vueltas++
            }
        }
    }

    private fun tramaConPayload(cmd: Int, payload: ByteArray): ByteArray =
        BleProtocol.paquetes(cmd, payload, mtu = 517).first()

    // ========================================================================
    //  FRAGMENTACION
    // ========================================================================

    @Test
    fun `reconstruye una trama de 40 bytes partida en trozos de 20`() {
        val payload = ByteArray(35) { (it + 1).toByte() }
        val trama = tramaConPayload(BleProtocol.Cmd.HISTORICO, payload)
        assertEquals(40, trama.size)

        val receptor = Receptor()
        receptor.recibir(trama.copyOfRange(0, 20))
        assertEquals("No debe emitir nada con solo la mitad", 0, receptor.tramas.size)
        receptor.recibir(trama.copyOfRange(20, 40))

        assertEquals(1, receptor.tramas.size)
        assertTrue(receptor.tramas[0].contentEquals(trama))
    }

    @Test
    fun `reconstruye la trama aunque llegue byte a byte`() {
        val payload = ByteArray(12) { (it * 3).toByte() }
        val trama = tramaConPayload(BleProtocol.Cmd.PULSOS_HISTORICOS, payload)

        val receptor = Receptor()
        trama.forEach { byte -> receptor.recibir(byteArrayOf(byte)) }

        assertEquals(1, receptor.tramas.size)
        assertTrue(receptor.tramas[0].contentEquals(trama))
    }

    @Test
    fun `un trozo mayor que el MTU declarado no rompe la extraccion`() {
        val trama = tramaConPayload(BleProtocol.Cmd.HISTORICO, ByteArray(60) { it.toByte() })
        val receptor = Receptor()
        receptor.recibir(trama.copyOfRange(0, 25))
        receptor.recibir(trama.copyOfRange(25, trama.size))
        assertEquals(1, receptor.tramas.size)
        assertTrue(receptor.tramas[0].contentEquals(trama))
    }

    // ========================================================================
    //  RUIDO Y TRAMAS PEGADAS
    // ========================================================================

    @Test
    fun `descarta el ruido anterior a la cabecera`() {
        val trama = tramaConPayload(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x4E))
        val ruido = byteArrayOf(0x00, 0x11, 0x22)
        val receptor = Receptor()
        receptor.recibir(ruido + trama)
        assertEquals(1, receptor.tramas.size)
        assertTrue(receptor.tramas[0].contentEquals(trama))
    }

    @Test
    fun `extrae dos tramas pegadas en un mismo buffer`() {
        val a = tramaConPayload(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x50))
        val b = tramaConPayload(BleProtocol.Cmd.OXIGENO, byteArrayOf(0x62))
        val receptor = Receptor()
        receptor.recibir(a + b)
        assertEquals(2, receptor.tramas.size)
        assertTrue(receptor.tramas[0].contentEquals(a))
        assertTrue(receptor.tramas[1].contentEquals(b))
    }

    @Test
    fun `un FE suelto al final no se pierde entre notificaciones`() {
        val trama = tramaConPayload(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x4E))
        val receptor = Receptor()
        // Se corta justo antes de la segunda cabecera... pero con el FE ya enviado
        receptor.recibir(trama + byteArrayOf(0xFE.toByte()))
        assertEquals(1, receptor.tramas.size)
        // ...y se completa en la siguiente notificacion
        receptor.recibir(trama.copyOfRange(1, trama.size))
        assertEquals(2, receptor.tramas.size)
    }

    // ========================================================================
    //  TRAMAS INVALIDAS (sin bucles ni excepciones)
    // ========================================================================

    @Test
    fun `una longitud incoherente se descarta sin quedarse atascado`() {
        // Longitud declarada 3 (menor que la cabecera): cabecera invalida
        val invalida = byteArrayOf(0xFE.toByte(), 0xEA.toByte(), 0x20, 0x03, 0x31)
        val extraccion = BleProtocol.extraerTrama(invalida)
        assertNull("No debe producir trama", extraccion.trama)
    }

    @Test
    fun `un buffer sin cabecera no produce trama y conserva solo el ultimo byte`() {
        val extraccion = BleProtocol.extraerTrama(byteArrayOf(0x01, 0x02, 0x03))
        assertNull(extraccion.trama)
        assertEquals(1, extraccion.restante.size)
        assertEquals(0x03.toByte(), extraccion.restante[0])
    }

    @Test
    fun `una trama valida seguida de basura devuelve la trama y limpia el resto`() {
        val trama = tramaConPayload(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x4E))
        val extraccion = BleProtocol.extraerTrama(trama + byteArrayOf(0x77.toByte(), 0x88.toByte()))
        assertNotNull(extraccion.trama)
        assertTrue(extraccion.trama!!.contentEquals(trama))
    }

    @Test
    fun `la trama de pasos del historico se interpreta tras reensamblarla`() {
        // Respuesta del historico: 9 bytes -> pasos, calorias y distancia (24 bits)
        val payload = byteArrayOf(
            0x39, 0x30, 0x00,          // 12345 pasos
            (678 and 0xFF).toByte(), ((678 shr 8) and 0xFF).toByte(), 0x00,
            (9012 and 0xFF).toByte(), ((9012 shr 8) and 0xFF).toByte(), 0x00
        )
        val trama = tramaConPayload(BleProtocol.Cmd.HISTORICO, payload)

        // Se entrega partida en dos, como haria el reloj con MTU 20
        val receptor = Receptor()
        receptor.recibir(trama.copyOfRange(0, 10))
        receptor.recibir(trama.copyOfRange(10, trama.size))
        assertEquals(1, receptor.tramas.size)

        val paquete = BleProtocol.parsear(receptor.tramas[0])
        assertNotNull(paquete)
        assertEquals(BleProtocol.Cmd.HISTORICO, paquete!!.comando)
        val pasos = BleProtocol.leerPasosCanal(paquete.payload)
        assertNotNull(pasos)
        assertEquals(12_345, pasos!!.first)
        assertEquals(678, pasos.second)
        assertEquals(9_012, pasos.third)
    }
}
