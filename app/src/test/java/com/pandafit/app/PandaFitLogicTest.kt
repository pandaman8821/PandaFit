// ============================================================================
//  Panda Fit - PandaFitLogicTest.kt
//  PRUEBAS UNITARIAS de la logica pura (sin Android, sin red):
//    * Parsers del protocolo BLE (perfil estandar de pulso, PLX/SpO2, bateria).
//    * Parser del plan de entrenamiento que devuelve Gemini.
//    * Motor local (FitnessEngine): insights, puntuacion y planes.
//    * Simulador de datos.
//
//  Se ejecutan con:  ./gradlew :app:testDebugUnitTest
// ============================================================================
package com.pandafit.app

import com.pandafit.app.ai.FitnessEngine
import com.pandafit.app.ai.PromptBuilder
import com.pandafit.app.ai.TrainingPlanParser
import com.pandafit.app.ble.BleProtocol
import com.pandafit.app.data.MetricsSimulator
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.GenderMode
import com.pandafit.app.data.model.MetricSeries
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PandaFitLogicTest {

    // ========================================================================
    //  1. PROTOCOLO BLE
    // ========================================================================

    @Test
    fun `parseHeartRate lee el formato de 8 bits del perfil estandar`() {
        // flags = 0x00 (uint8), valor = 72 ppm
        val frame = byteArrayOf(0x00, 72)
        assertEquals(72, BleProtocol.parseHeartRate(frame))
    }

    @Test
    fun `parseHeartRate lee el formato de 16 bits`() {
        // flags = 0x01 (uint16), valor = 0x0096 = 150 ppm
        val frame = byteArrayOf(0x01, 0x96.toByte(), 0x00)
        assertEquals(150, BleProtocol.parseHeartRate(frame))
    }

    @Test
    fun `parseHeartRate descarta valores no fisiologicos`() {
        // 290 ppm no es un pulso humano: no se publica como dato de salud.
        val absurdo = byteArrayOf(0x01, 0x22, 0x01)
        assertNull(BleProtocol.parseHeartRate(absurdo))
        // 5 ppm tampoco
        assertNull(BleProtocol.parseHeartRate(byteArrayOf(0x00, 0x05)))
    }

    @Test
    fun `parseHeartRate devuelve null con tramas incompletas`() {
        assertNull(BleProtocol.parseHeartRate(byteArrayOf()))
        assertNull(BleProtocol.parseHeartRate(byteArrayOf(0x01)))
        assertNull(BleProtocol.parseHeartRate(byteArrayOf(0x01, 0x22)))
    }

    @Test
    fun `parseSpo2 convierte SFLOAT de 2 bytes`() {
        // SpO2 = 98 -> mantisa 98, exponente 0; pulso = 71
        val spo2Raw = 98 and 0x0FFF
        val pulseRaw = 71 and 0x0FFF
        val frame = byteArrayOf(
            (spo2Raw and 0xFF).toByte(), ((spo2Raw shr 8) and 0xFF).toByte(),
            (pulseRaw and 0xFF).toByte(), ((pulseRaw shr 8) and 0xFF).toByte()
        )
        val parsed = BleProtocol.parseSpo2(frame)
        assertNotNull(parsed)
        assertEquals(98, parsed!!.first)
        assertEquals(71, parsed.second)
    }

    @Test
    fun `parseBattery acota el valor entre 0 y 100`() {
        assertEquals(87, BleProtocol.parseBattery(byteArrayOf(87.toByte())))
        assertEquals(100, BleProtocol.parseBattery(byteArrayOf(0xFF.toByte())))
        assertNull(BleProtocol.parseBattery(byteArrayOf()))
    }

    @Test
    fun `parseUint16Le lee pasos en little endian`() {
        // 12000 = 0x2EE0 -> bytes E0 2E
        assertEquals(12_000, BleProtocol.parseUint16Le(byteArrayOf(0xE0.toByte(), 0x2E)))
        assertNull(BleProtocol.parseUint16Le(byteArrayOf(0x01)))
    }

    @Test
    fun `los comandos de medicion siguen el formato Moyoung del K56 Pro`() {
        // Antes existia una trama "0x69 + comando" generica; ahora se usa el
        // formato real del fabricante (cabecera 0xFE 0xEA). Se comprueba que el
        // pulso en vivo viaja correctamente empaquetado.
        val trama = BleProtocol.paquetes(
            BleProtocol.Cmd.PULSOS_VIVO,
            BleProtocol.payloadPulsosEnVivo(true)
        ).first()
        assertEquals(0xFE, trama[0].toInt() and 0xFF)
        assertEquals(0xEA, trama[1].toInt() and 0xFF)
        assertEquals(BleProtocol.Cmd.PULSOS_VIVO, trama[4].toInt() and 0xFF)
        assertEquals(0x00, trama[5].toInt() and 0xFF)
    }

    // ========================================================================
    //  1b. PROTOCOLO MOYOUNG / K56 PRO (tramas 0xFE 0xEA)
    // ========================================================================

    @Test
    fun `la trama Moyoung tiene cabecera FE EA con longitud y comando`() {
        val trozos = BleProtocol.paquetes(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x00), mtu = 20)
        assertEquals(1, trozos.size)
        val trama = trozos.first()
        assertEquals("FEEA", "%02X%02X".format(trama[0], trama[1]))
        // longitud = 5 + payload(1) = 6 = 0x0006; modo V2 = 0x20
        assertEquals(0x20, trama[2].toInt() and 0xF0)
        assertEquals(6, ((trama[2].toInt() and 0x0F) shl 8) or (trama[3].toInt() and 0xFF))
        assertEquals(BleProtocol.Cmd.PULSOS_VIVO, trama[4].toInt() and 0xFF)
        assertEquals(0x00, trama[5].toInt() and 0xFF)
    }

    @Test
    fun `el parser reconoce una trama Moyoung y extrae comando y payload`() {
        val trama = BleProtocol.paquetes(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x4E)).first()
        val paquete = BleProtocol.parsear(trama)
        assertNotNull(paquete)
        assertEquals(BleProtocol.Cmd.PULSOS_VIVO, paquete!!.comando)
        assertEquals(1, paquete.payload.size)
        // 0x4E = 78 ppm
        assertEquals(78, BleProtocol.leerBpm(paquete.payload))
    }

    @Test
    fun `el parser rechaza tramas que no son del protocolo`() {
        assertNull(BleProtocol.parsear(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)))
        assertNull(BleProtocol.parsear(byteArrayOf(0xFE.toByte(), 0xEA.toByte())))
        // modo invalido (0x30 no es ni V2 0x2x ni legado 0x1x)
        assertNull(
            BleProtocol.parsear(
                byteArrayOf(0xFE.toByte(), 0xEA.toByte(), 0x30, 0x06, 0x6D, 0x00)
            )
        )
    }

    @Test
    fun `la variante legada usa el modo 0x10`() {
        val trama = BleProtocol.paquetesLegado(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x00)).first()
        assertEquals(0x10, trama[2].toInt() and 0xF0)
        assertNotNull(BleProtocol.parsear(trama))
    }

    @Test
    fun `el fin de transaccion se detecta con payload 00`() {
        val trama = BleProtocol.paquetes(BleProtocol.Cmd.PULSOS_VIVO, byteArrayOf(0x00)).first()
        val fin = BleProtocol.parsear(trama)!!
        assertTrue(BleProtocol.esFinDeTransaccion(fin))
    }

    @Test
    fun `los payloads de los comandos del K56 Pro son los del fabricante`() {
        // Pulso en vivo y oxigeno: {00} iniciar, {FF} parar
        assertArrayEquals(byteArrayOf(0x00), BleProtocol.payloadPulsosEnVivo(true))
        assertArrayEquals(byteArrayOf(0xFF.toByte()), BleProtocol.payloadPulsosEnVivo(false))
        assertArrayEquals(byteArrayOf(0x00), BleProtocol.payloadOxigeno(true))
        // Presion arterial: 3 bytes
        assertArrayEquals(byteArrayOf(0, 0, 0), BleProtocol.payloadPresion(true))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            BleProtocol.payloadPresion(false)
        )
        // Historico de pulso de hoy: indice 0
        assertArrayEquals(byteArrayOf(0x00), BleProtocol.payloadPulsosHistoricos())
        // Pasos de ayer: argumento 1
        assertArrayEquals(byteArrayOf(0x01), BleProtocol.payloadPasosAyer())
    }

    @Test
    fun `el SpO2 del K56 Pro tolera un byte de estado delante`() {
        // "00 62 00" = 98 % con un 0 delante
        assertEquals(98, BleProtocol.leerSpo2(byteArrayOf(0x00, 0x62, 0x00)))
        // Formato limpio {62}
        assertEquals(98, BleProtocol.leerSpo2(byteArrayOf(0x62)))
        // Sin medida (0) -> 0
        assertEquals(0, BleProtocol.leerSpo2(byteArrayOf(0x00)))
    }

    @Test
    fun `el parser de pasos lee los tres enteros de 24 bits del canal FEE1`() {
        // pasos=12345, calorias=678, distancia=9012 (little-endian, 24 bits)
        val datos = byteArrayOf(
            0x39, 0x30, 0x00,
            (678 and 0xFF).toByte(), ((678 shr 8) and 0xFF).toByte(), 0x00,
            (9012 and 0xFF).toByte(), ((9012 shr 8) and 0xFF).toByte(), 0x00
        )
        val leido = BleProtocol.leerPasosCanal(datos)
        assertNotNull(leido)
        assertEquals(12_345, leido!!.first)
        assertEquals(678, leido.second)
        assertEquals(9_012, leido.third)
    }

    @Test
    fun `la presion arterial se valida dentro de rangos fisiologicos`() {
        val valida = BleProtocol.leerPresion(byteArrayOf(0x00, 120.toByte(), 80.toByte()))
        assertNotNull(valida)
        assertEquals(120, valida!!.first)
        assertEquals(80, valida.second)
        // Valores absurdos -> null (nunca se publica basura)
        assertNull(BleProtocol.leerPresion(byteArrayOf(0x00, 5, 2)))
        assertNull(BleProtocol.leerPresion(byteArrayOf(0x00, 120.toByte())))
    }

    @Test
    fun `el nombre corto identifica los UUID del K56 Pro y Moyoung`() {
        assertEquals("K56_NOTIFY(FEA1)", BleProtocol.nombreCorto(BleProtocol.K56_NOTIFY))
        assertEquals("K56_CMD(FEC9)", BleProtocol.nombreCorto(BleProtocol.K56_READ_COMMAND))
        assertEquals("MOYOUNG_WRITE(FEE2)", BleProtocol.nombreCorto(BleProtocol.MOYOUNG_CHAR_WRITE))
    }

    // ========================================================================
    //  2. PARSER DEL PLAN DE GEMINI
    // ========================================================================

    private val planDeGemini = """
        PLAN: Panda Fuerza 4 dias
        OBJETIVO: Ganar fuerza y mejorar el sueno
        DIAS_SEMANA: 4
        MINUTOS_SEMANA: 160
        NIVEL: Intermedio

        LUNES: Fuerza cuerpo completo | 40 | Sentadillas: 3x12 ; Flexiones: 3x10 ; Plancha: 3x45 segundos
        MARTES: descanso
        MIERCOLES: Cardio suave | 35 | Caminata rapida: 25 minutos ; Intervalos: 4x1 minutos
        JUEVES: Fuerza tren inferior | 40 | Zancadas: 3x10 ; Puente de gluteo: 3x15
        VIERNES: Movilidad | 25 | Gato-camello: 2x10 ; Estiramientos: 10 minutos
        SABADO: descanso
        DOMINGO: descanso

        MOTIVO: Tus pasos medios y tu sueno de 7.2 h permiten subir intensidad poco a poco.
    """.trimIndent()

    @Test
    fun `el parser convierte el plan de Gemini en un objeto TrainingPlan`() {
        val plan = TrainingPlanParser.parse(planDeGemini)
        assertNotNull(plan)
        plan!!
        assertEquals("Panda Fuerza 4 dias", plan.title)
        assertEquals("Ganar fuerza y mejorar el sueno", plan.goal)
        assertEquals(4, plan.daysPerWeek)
        assertEquals(160, plan.weeklyMinutes)
        assertEquals("Intermedio", plan.level)
        assertTrue(plan.rationale.contains("7.2"))
        assertTrue(plan.days.isNotEmpty())
    }

    @Test
    fun `el parser entiende series, repeticiones, minutos y segundos`() {
        val plan = TrainingPlanParser.parse(planDeGemini)!!
        val lunes = plan.days.first { it.day == "LUNES" }
        assertEquals("Fuerza cuerpo completo", lunes.focus)
        assertEquals(40, lunes.totalMinutes)

        val sentadillas = lunes.exercises.first { it.name.startsWith("Sentadillas") }
        assertEquals(3, sentadillas.sets)
        assertEquals("12", sentadillas.reps)

        val plancha = lunes.exercises.first { it.name.startsWith("Plancha") }
        assertEquals(3, plancha.sets)
        assertEquals("45", plancha.reps)

        val miercoles = plan.days.first { it.day == "MIERCOLES" || it.day == "MIÉRCOLES" }
        val caminata = miercoles.exercises.first { it.name.startsWith("Caminata") }
        assertEquals(25, caminata.durationMinutes)
    }

    @Test
    fun `el parser devuelve null si el texto no tiene ningun dia`() {
        assertNull(TrainingPlanParser.parse("Hola, soy tu asistente y no hay plan aqui."))
    }

    // ========================================================================
    //  3. MOTOR LOCAL (FitnessEngine)
    // ========================================================================

    @Test
    fun `un dia con sueno bajo y pocos pasos genera avisos`() {
        val today = DailyMetrics(
            dateKey = LocalDate.now().toString(),
            steps = 2_000,
            stepGoal = 10_000,
            sleepHours = 4.5,
            sleepQuality = 40,
            restingHeartRate = 82,
            spo2 = 93,
            caloriesKcal = 90,
            source = "simulado"
        )
        val report = FitnessEngine.analyze(today, MetricSeries(), sleepGoalHours = 8f)
        assertTrue(report.score < 60)
        assertTrue(report.insights.any { it.title.contains("Sueño bajo") })
        assertTrue(report.insights.any { it.title.contains("Oxigeno") })
        assertTrue(report.insights.any { it.severity >= 1 })
    }

    @Test
    fun `un dia perfecto obtiene una puntuacion alta`() {
        val today = DailyMetrics(
            dateKey = LocalDate.now().toString(),
            steps = 12_000,
            stepGoal = 10_000,
            sleepHours = 8.2,
            sleepQuality = 92,
            restingHeartRate = 58,
            spo2 = 98,
            caloriesKcal = 540,
            source = "health_connect"
        )
        val report = FitnessEngine.analyze(today, MetricSeries(), sleepGoalHours = 8f)
        assertTrue("Puntuacion esperada alta pero fue ${report.score}", report.score >= 85)
        assertTrue(report.insights.any { it.title.contains("Meta de pasos") })
    }

    @Test
    fun `el plan local se adapta al sueno bajo reduciendo dias e intensidad`() {
        val today = DailyMetrics(steps = 4_000, stepGoal = 10_000, sleepHours = 5.5)
        val history = MetricSeries(
            (0..6).map {
                DailyMetrics(
                    dateKey = LocalDate.now().minusDays(it.toLong()).toString(),
                    steps = 4_200,
                    stepGoal = 10_000,
                    sleepHours = 5.4,
                    spo2 = 97,
                    restingHeartRate = 70
                )
            }
        )
        val plan = FitnessEngine.localPlan(today, history)
        assertEquals(3, plan.daysPerWeek)
        assertTrue(plan.level == "Principiante")
        assertTrue(plan.rationale.contains("descanso", ignoreCase = true) ||
            plan.rationale.contains("recuperacion", ignoreCase = true))
        assertEquals(7, plan.days.size)
        assertTrue(plan.weeklyMinutes > 0)
    }

    @Test
    fun `el resumen anonimo no contiene datos personales`() {
        val today = DailyMetrics(steps = 8_000, sleepHours = 7.0, spo2 = 97)
        val summary = today.toAnonymousSummary()
        listOf("nombre", "correo", "email", "telefono", "direccion").forEach { campo ->
            assertTrue(
                "El resumen no debe contener '$campo'",
                !summary.contains(campo, ignoreCase = true)
            )
        }
        assertTrue(summary.contains("Pasos"))
    }

    // ========================================================================
    //  4. SIMULADOR
    // ========================================================================

    @Test
    fun `el simulador es determinista por dia`() {
        val date = LocalDate.of(2026, 10, 12)
        val a = MetricsSimulator.fullDay(date, GenderMode.MAN, 10_000)
        val b = MetricsSimulator.fullDay(date, GenderMode.MAN, 10_000)
        assertEquals(a.steps, b.steps)
        assertEquals(a.sleepHours, b.sleepHours, 0.001)
    }

    @Test
    fun `a primera hora del dia los pasos son menores que a ultima hora`() {
        val date = LocalDate.now()
        val temprano = MetricsSimulator.today(
            GenderMode.MAN, 10_000, LocalTime.of(6, 30), date
        )
        val tarde = MetricsSimulator.today(
            GenderMode.MAN, 10_000, LocalTime.of(21, 0), date
        )
        assertTrue(
            "A las 6:30 (${temprano.steps}) debe haber menos pasos que a las 21:00 (${tarde.steps})",
            temprano.steps < tarde.steps
        )
    }

    @Test
    fun `todos los valores simulados estan en rangos fisiologicos`() {
        val history = MetricsSimulator.history(14, GenderMode.WOMAN, 8_000)
        assertEquals(14, history.days.size)
        history.days.forEach { day ->
            assertTrue("Pasos fuera de rango: ${day.steps}", day.steps in 0..60_000)
            assertTrue("Sueno fuera de rango: ${day.sleepHours}", day.sleepHours in 3.0..12.0)
            assertTrue("SpO2 fuera de rango: ${day.spo2}", day.spo2 in 90..100)
            assertTrue("Pulso fuera de rango: ${day.restingHeartRate}", day.restingHeartRate in 40..110)
            assertTrue("Calidad fuera de rango: ${day.sleepQuality}", day.sleepQuality in 0..100)
        }
    }

    @Test
    fun `la meta de pasos produce el progreso correcto`() {
        val today = DailyMetrics(steps = 5_000, stepGoal = 10_000)
        assertEquals(0.5f, today.stepProgress, 0.001f)
        val sinMeta = DailyMetrics(steps = 5_000, stepGoal = 0)
        assertEquals(0f, sinMeta.stepProgress, 0.001f)
    }

    // ========================================================================
    //  5. PRIVACIDAD: SCRUBBER DE DATOS PERSONALES
    // ========================================================================

    @Test
    fun `el scrubber enmascara correos, telefonos, MAC y documentos`() {
        val original = "Soy Ana, mi correo es ana.lopez@mail.com, mi movil 809-555-1234 " +
            "y la MAC 3C:5A:B4:11:22:33. Mi cedula es ABC1234567."
        val limpio = PromptBuilder.PiiScrubber.scrub(original)

        assertTrue("No debe quedar el correo", !limpio.contains("ana.lopez@mail.com"))
        assertTrue("Debe aparecer [EMAIL]", limpio.contains("[EMAIL]"))
        assertTrue("No debe quedar el telefono", !limpio.contains("809-555-1234"))
        assertTrue("Debe aparecer [TELEFONO]", limpio.contains("[TELEFONO]"))
        assertTrue("No debe quedar la MAC", !limpio.contains("3C:5A:B4:11:22:33"))
        assertTrue("Debe aparecer [MAC]", limpio.contains("[MAC]"))
        assertTrue("No debe quedar el documento", !limpio.contains("ABC1234567"))
    }

    @Test
    fun `el prompt libre enviado a Gemini no contiene datos personales`() {
        val today = DailyMetrics(steps = 7_000, stepGoal = 10_000, sleepHours = 7.1, spo2 = 97)
        val prompt = PromptBuilder.freeChatPrompt(
            userMessage = "Me llamo Ana y mi email es ana@ejemplo.com, dame un plan",
            today = today,
            history = MetricSeries(),
            stepGoal = 10_000,
            sleepGoalHours = 8f
        )
        assertTrue(!prompt.contains("ana@ejemplo.com"))
        assertTrue(prompt.contains("[EMAIL]"))
        assertTrue(prompt.contains("CONTEXTO ANONIMO"))
        assertTrue(prompt.contains("Datos personales incluidos: NINGUNO"))
    }

    @Test
    fun `el scrubber deja intacto el texto sin datos personales`() {
        val texto = "Dame un plan de entrenamiento de 4 dias y una rutina de sueno"
        assertEquals(texto, PromptBuilder.PiiScrubber.scrub(texto))
    }
}
