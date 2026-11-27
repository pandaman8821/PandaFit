// ============================================================================
//  Panda Fit - BleProtocol.kt
//  UUIDs y protocolo de las bandas/relojes compatibles.
//
//  ORDEN DE INTENTOS AL CONECTAR (de mas especifico a mas generico):
//    1. K56 PRO REAL  -> servicio 0xFEE7, notifica 0xFEA1, lee 0xFEC9
//                        (el K56 Pro no expone caracteristica de escritura: los
//                         comandos se envian como LECTURA en 0xFEC9)
//    2. MOYOUNG clasico -> servicio 0xFEEA, escribe 0xFEE2, notifica 0xFEE3
//                        (variantes de escritura: 0xFEE5, 0xFEE6)
//                        contador de pasos del dia: 0xFEE1
//    3. PERFIL ESTANDAR  -> pulso 0x180D/0x2A37, bateria 0x180F/0x2A19,
//                           oximetro 0x1822/0x2A5F
//
//  PROTOCOLO MOYOUNG (tramas):
//    Escritura: cabecera de 5 bytes {0xFE, 0xEA, modo|len_hi, len_lo, cmd} + payload
//               modo 0x20 = V2 (MTU), modo 0x10 = legado.
//    Notificacion: misma cabecera; payload = bytes[5..fin].
//
//  AVISO CRITICO DE CAMPO (K56 Pro): el comando 0x31 (poner hora) con timestamp
//  Unix NO se debe enviar NUNCA: el firmware del K56 Pro lo interpreta como hora
//  local y BORRA los contadores diarios (pasos y sueno a cero). Panda Fit no lo
//  envia jamas; la hora se ajusta con la app oficial DaFit o en el propio reloj.
// ============================================================================
package com.pandafit.app.ble

import java.util.UUID

object BleProtocol {

    // ------------------------------ PERFIL ESTANDAR (Bluetooth SIG) ----------
    val GENERIC_ACCESS: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val GENERIC_ATTRIBUTE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    /** Servicio de frecuencia cardiaca estandar. */
    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_CONTROL_POINT: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")
    val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")

    /** Servicio de bateria estandar. */
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** Nombre del dispositivo (0x2A00). */
    val GENERIC_DEVICE_NAME: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    /** Informacion del dispositivo (servicio 0x180A): fabricante, modelo, firmware. */
    val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    /** Oximetro continuo (SpO2) del perfil estandar. */
    val PLX_SERVICE: UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
    val PLX_SPOT_CHECK: UUID = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")
    val PLX_CONTINUOUS: UUID = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")

    // ------------------------ K56 PRO (firmware real medido en campo) --------
    /**
     * Servicio principal del **K56 Pro**.
     * Expone 0xFEA1 (READ + NOTIFY con CCCD) y 0xFEC9, que se usa para ENVIAR
     * comandos escribiendo... pero en este firmware los comandos se solicitan
     * LEYENDO 0xFEC9 (no hay caracteristica de escritura en el servicio).
     */
    val K56_SERVICE: UUID = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")
    val K56_NOTIFY: UUID = UUID.fromString("0000fea1-0000-1000-8000-00805f9b34fb")
    val K56_READ_COMMAND: UUID = UUID.fromString("0000fec9-0000-1000-8000-00805f9b34fb")

    // ------------------------ MOYOUNG / H Band / DaFit-like -------------------
    /** Servicio Moyoung clasico (mayoria de los relojes de esta familia). */
    val MOYOUNG_SERVICE_MAIN: UUID = UUID.fromString("0000feea-0000-1000-8000-00805f9b34fb")
    /** Canal de escritura de comandos. */
    val MOYOUNG_CHAR_WRITE: UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb")
    /** Canal de notificaciones (respuestas del reloj). */
    val MOYOUNG_CHAR_NOTIFY: UUID = UUID.fromString("0000fee3-0000-1000-8000-00805f9b34fb")
    /** Contador de pasos/calorias/distancia del dia (lectura directa). */
    val MOYOUNG_CHAR_STEPS: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    /** Canales alternativos de escritura de algunos firmwares Moyoung. */
    val MOYOUNG_WRITE_ALT_1: UUID = UUID.fromString("0000fee5-0000-1000-8000-00805f9b34fb")
    val MOYOUNG_WRITE_ALT_2: UUID = UUID.fromString("0000fee6-0000-1000-8000-00805f9b34fb")

    /** Descriptor de configuracion de notificaciones (CCCD). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Nombres con los que se anuncian los relojes compatibles. */
    val KNOWN_NAME_HINTS = listOf(
        "k56", "k56pro", "panda", "hband", "h band", "moyoung", "da fit", "dafit",
        "agefit", "fitcloud", "fit", "watch", "band", "hr", "smart", "id1", "id2", "y68"
    )

    /**
     * Caracteristicas a las que Panda Fit intenta suscribirse, en orden de
     * prioridad: primero el K56 real, despues Moyoung clasico y por ultimo los
     * perfiles estandar (pulso, SpO2, bateria).
     */
    val NOTIFY_PRIORITY: List<UUID> = listOf(
        K56_NOTIFY,
        MOYOUNG_CHAR_NOTIFY,
        HEART_RATE_MEASUREMENT,
        PLX_CONTINUOUS,
        PLX_SPOT_CHECK,
        BATTERY_LEVEL,
        MOYOUNG_CHAR_STEPS
    )

    /** Servicio propietario generico muy comun en relojes "DaFit-like". */
    val VENDOR_SERVICE_A: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val VENDOR_CHAR_A_NOTIFY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val VENDOR_CHAR_A_WRITE: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val VENDOR_SERVICE_B: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val VENDOR_CHAR_B_NOTIFY: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    // ========================================================================
    //  FAMILIAS DE RELOJ (compatibilidad)
    // ========================================================================
    /**
     * Familia detectada a partir de los servicios que publica el reloj.
     * Decide si se le pueden enviar comandos propietarios o solo escuchar.
     */
    enum class FamiliaReloj(val etiqueta: String, val aceptaComandosMoyoung: Boolean) {
        /** Relojes con solo el perfil 0xFEE7 (sin el canal DaFit 0xFEE2). */
        K56("K56 / perfil FEE7", true),
        /** DaFit / Moyoung / H Band clasico (servicio 0xFEEA). */
        MOYOUNG("DaFit / Moyoung (FEEA)", true),
        /** Relojes economicos "DaFit-like" con servicio 0xFFF0 o 0xFF00. */
        GENERICO_FFF0("Reloj generico (FFF0/FF00)", true),
        /**
         * Solo perfiles ESTANDAR de Bluetooth (pulso 0x180D, SpO2 0x1822,
         * bateria 0x180F). Amazfit, Xiaomi, Huawei, Garmin, Polar, Coros... en
         * modo "compartir frecuencia cardiaca". Se escucha, nunca se escribe.
         */
        ESTANDAR("Perfil estandar (pulso/SpO2/bateria)", false),
        /** Nada conocido: solo se escucha (nunca se escriben comandos). */
        DESCONOCIDA("Desconocida (solo escucha)", false)
    }

    /** Servicios estandar del Bluetooth SIG que traen datos de salud utiles. */
    private val SERVICIOS_SALUD_ESTANDAR = setOf(HEART_RATE_SERVICE, PLX_SERVICE)

    /** Detecta la familia del reloj por los UUID de sus servicios. */
    fun detectarFamilia(servicios: Set<UUID>): FamiliaReloj = when {
        // El K56 Pro real publica AMBOS (FEEA y FEE7): manda FEEA, que es el
        // que tiene canal de comandos (FEE2). FEE7 es el podometro WeChat.
        MOYOUNG_SERVICE_MAIN in servicios -> FamiliaReloj.MOYOUNG
        K56_SERVICE in servicios -> FamiliaReloj.K56
        VENDOR_SERVICE_A in servicios || VENDOR_SERVICE_B in servicios -> FamiliaReloj.GENERICO_FFF0
        servicios.any { it in SERVICIOS_SALUD_ESTANDAR } -> FamiliaReloj.ESTANDAR
        else -> FamiliaReloj.DESCONOCIDA
    }

    /** Servicios del estandar que nunca llevan el canal de datos propietario. */
    val SERVICIOS_ESTANDAR_BASE: Set<UUID> = setOf(
        GENERIC_ACCESS, GENERIC_ATTRIBUTE, DEVICE_INFORMATION
    )

    /** Servicios cuyo canal de comandos se intenta localizar (en este orden). */
    val COMMAND_SERVICE_PRIORITY: List<UUID> = listOf(
        K56_SERVICE,
        MOYOUNG_SERVICE_MAIN,
        VENDOR_SERVICE_A,
        VENDOR_SERVICE_B
    )

    // ========================================================================
    //  COMANDOS MOYOUNG
    // ========================================================================
    object Cmd {
        /** Hora local (6 bytes). NO ENVIAR en K56 Pro: borra los contadores. */
        const val HORA = 0x31
        const val SUENO_HOY = 0x32
        const val HISTORICO = 0x33
        const val PULSOS_HISTORICOS = 0x35
        const val PULSOS_HOY = 0x36
        /** Pulso en vivo: payload {0x00} iniciar, {0xFF} parar. */
        const val PULSOS_VIVO = 0x6D
        /** Oxigeno: payload {0x00} iniciar, {0xFF} parar. */
        const val OXIGENO = 0x6B
        /** Presion arterial: {0,0,0} iniciar, {0xFF,0xFF,0xFF} parar. */
        const val PRESION = 0x69
    }

    /** Argumentos del comando 0x33 (historico). */
    object Arg {
        const val PASOS_AYER = 1
        const val PASOS_ANTEAYER = 2
        const val SUENO_AYER = 3
        const val SUENO_ANTEAYER = 4

        /** Indice que pide el fabricante para el historico de pulso de hoy. */
        const val PULSO_HOY_INDICE = 0
    }

    // Rangos fisiologicos usados al validar (evita publicar basura como salud).
    private const val BPM_MIN = 25
    private const val BPM_MAX = 250
    private const val SPO2_MIN = 50
    private const val SPO2_MAX = 100
    private const val SISTOLICA_MIN = 60
    private const val SISTOLICA_MAX = 260
    private const val DIASTOLICA_MIN = 30
    private const val DIASTOLICA_MAX = 180

    // ========================================================================
    //  CONSTRUCCION DE TRAMAS
    // ========================================================================

    /**
     * Construye la trama "modo MTU" (V2) y la trocea para que cada escritura
     * quepa en (mtu - 3) bytes. La cabecera de 5 bytes viaja en el primer trozo.
     *
     * @param cmd     comando (ver [Cmd]).
     * @param payload datos del comando (vacio si no lleva argumentos).
     * @param mtu     MTU negociado (517 en Panda Fit, 23 por defecto si falla).
     */
    fun paquetes(cmd: Int, payload: ByteArray, mtu: Int = 20): List<ByteArray> {
        val longitud = 5 + payload.size
        val cabecera = byteArrayOf(
            0xFE.toByte(),
            0xEA.toByte(),
            (0x20 or ((longitud shr 8) and 0xFF)).toByte(),
            (longitud and 0xFF).toByte(),
            (cmd and 0xFF).toByte()
        )
        return trocear(cabecera, payload, mtu)
    }

    /**
     * Variante legada (anuncio "MOYOUNG"): la cabecera usa 0x10 en el byte 2.
     * Es el respaldo cuando un reloj antiguo no entiende el modo V2.
     */
    fun paquetesLegado(cmd: Int, payload: ByteArray, mtu: Int = 20): List<ByteArray> {
        val longitud = 5 + payload.size
        val cabecera = byteArrayOf(
            0xFE.toByte(),
            0xEA.toByte(),
            (0x10 or ((longitud shr 8) and 0xFF)).toByte(),
            (longitud and 0xFF).toByte(),
            (cmd and 0xFF).toByte()
        )
        return trocear(cabecera, payload, mtu)
    }

    /** Reparte cabecera + payload en trozos de como maximo (mtu - 3) bytes. */
    private fun trocear(cabecera: ByteArray, payload: ByteArray, mtu: Int): List<ByteArray> {
        val porTrozo = (mtu - 3).coerceAtLeast(cabecera.size + 1)
        val salida = ArrayList<ByteArray>(1 + payload.size / porTrozo)
        if (payload.isEmpty()) {
            salida.add(cabecera)
            return salida
        }
        var posicion = 0
        var primero = true
        while (posicion < payload.size) {
            val capacidad = if (primero) (porTrozo - cabecera.size).coerceAtLeast(1) else porTrozo
            val cuantos = minOf(capacidad, payload.size - posicion)
            val trozo = payload.copyOfRange(posicion, posicion + cuantos)
            salida.add(if (primero) cabecera + trozo else trozo)
            posicion += cuantos
            primero = false
        }
        return salida
    }

    // ---------------------- Payloads de los comandos salientes ----------------
    fun payloadSuenoHoy(): ByteArray = ByteArray(0)
    fun payloadSuenoAyer(): ByteArray = byteArrayOf(Arg.SUENO_AYER.toByte())
    fun payloadPasosAyer(): ByteArray = byteArrayOf(Arg.PASOS_AYER.toByte())
    fun payloadPasosAnteayer(): ByteArray = byteArrayOf(Arg.PASOS_ANTEAYER.toByte())
    fun payloadPulsosHistoricos(): ByteArray = byteArrayOf(Arg.PULSO_HOY_INDICE.toByte())

    /** Pulso en vivo: iniciar/parar la medicion continua. */
    fun payloadPulsosEnVivo(iniciar: Boolean): ByteArray =
        byteArrayOf(if (iniciar) 0x00 else 0xFF.toByte())

    /** Oxigeno en sangre: iniciar/parar. */
    fun payloadOxigeno(iniciar: Boolean): ByteArray =
        byteArrayOf(if (iniciar) 0x00 else 0xFF.toByte())

    /** Presion arterial: iniciar/parar (3 bytes, como en el K56 Pro). */
    fun payloadPresion(iniciar: Boolean): ByteArray =
        if (iniciar) byteArrayOf(0, 0, 0)
        else byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    // ========================================================================
    //  PARSEO DE NOTIFICACIONES
    // ========================================================================

    /** Paquete Moyoung ya interpretado: comando y payload (sin cabecera). */
    data class Paquete(val comando: Int, val payload: ByteArray)

    /**
     * Interpreta una notificacion del reloj. Detecta la cabecera 0xFE 0xEA y
     * soporta el modo V2 (0x2x) y el legado (0x1x). Devuelve null si la trama no
     * pertenece al protocolo (asi los canales estandar no se confunden).
     */
    fun parsear(bytes: ByteArray): Paquete? {
        if (bytes.size < 5) return null
        if ((bytes[0].toInt() and 0xFF) != 0xFE) return null
        if ((bytes[1].toInt() and 0xFF) != 0xEA) return null
        val marca = bytes[2].toInt() and 0xFF
        val modoValido = (marca and 0xF0) == 0x20 || (marca and 0xF0) == 0x10
        if (!modoValido) return null
        val declarada = ((marca and 0x0F) shl 8) or (bytes[3].toInt() and 0xFF)
        val comando = bytes[4].toInt() and 0xFF
        val fin = calcularFin(bytes.size, declarada)
        val payload = if (fin > 5) bytes.copyOfRange(5, fin) else ByteArray(0)
        return Paquete(comando, payload)
    }

    /**
     * La especificacion dice que el campo de longitud vale 5 + payload.size (el
     * tamano total). Algunos firmware cuentan solo el payload o dejan relleno al
     * final: se aceptan las variantes coherentes y, si no cuadra ninguna, se usa
     * todo lo recibido antes que perder datos.
     */
    private fun calcularFin(total: Int, declarada: Int): Int {
        val restante = total - 5
        return when {
            declarada == total -> total
            declarada == restante -> total
            declarada + 2 == total -> total
            declarada + 3 == total -> total
            declarada in 5..total -> declarada
            else -> total
        }
    }

    /**
     * El reloj cierra cada transaccion con un paquete cuyo payload es {0x00}.
     * Cuando llega, la cola de comandos puede pasar al siguiente paso.
     */
    fun esFinDeTransaccion(p: Paquete): Boolean =
        p.payload.size == 1 && p.payload[0] == 0x00.toByte()

    // ========================================================================
    //  REENSAMBLADO DE TRAMAS  (causa real de "no sincroniza")
    // ========================================================================
    /**
     * Resultado de intentar extraer una trama del buffer de recepcion.
     *
     * @param trama    trama completa lista para interpretar, o null si aun no
     *                 hay bytes suficientes (se espera al siguiente paquete BLE).
     * @param restante lo que sobra del buffer (incluye la trama no consumida).
     */
    class Extraccion(val trama: ByteArray?, val restante: ByteArray)

    /** Tamano maximo aceptado para una trama (evita crecimientos sin control). */
    const val MAX_TRAMA = 2048

    /**
     * El K56 Pro publica respuestas MAS LARGAS que el MTU de notificacion (20
     * bytes), asi que una trama llega partida en varios paquetes BLE. Si se
     * interpreta cada trozo por separado, el parser la descarta entera y la app
     * parece "no sincronizar". Esta funcion acumula y devuelve la trama completa.
     *
     * Formato Moyoung: `FE EA (modo<<4 | len_hi) len_lo cmd payload...`, donde
     * `len` es el tamano TOTAL de la trama (5 + payload).
     */
    fun extraerTrama(buffer: ByteArray): Extraccion {
        // 1) Se busca la cabecera FE EA; lo anterior se descarta.
        var inicio = -1
        var i = 0
        while (i < buffer.size - 1) {
            if ((buffer[i].toInt() and 0xFF) == 0xFE &&
                (buffer[i + 1].toInt() and 0xFF) == 0xEA
            ) {
                inicio = i
                break
            }
            i++
        }
        if (inicio < 0) {
            // Sin cabecera: solo se guarda el ultimo byte (podria ser un FE).
            val resto = if (buffer.isNotEmpty()) {
                buffer.copyOfRange(buffer.size - 1, buffer.size)
            } else {
                ByteArray(0)
            }
            return Extraccion(null, resto)
        }
        if (inicio > 0) {
            // Se descarta la basura anterior y se vuelve a intentar.
            return extraerTrama(buffer.copyOfRange(inicio, buffer.size))
        }
        if (buffer.size < 5) return Extraccion(null, buffer)

        val marca = buffer[2].toInt() and 0xFF
        val declarada = ((marca and 0x0F) shl 8) or (buffer[3].toInt() and 0xFF)
        if (declarada < 5 || declarada > MAX_TRAMA) {
            // Cabecera incoherente: se salta este FE EA y se sigue buscando.
            return extraerTrama(buffer.copyOfRange(2, buffer.size))
        }
        if (buffer.size < declarada) {
            // Falta el resto de la trama: se espera al siguiente paquete.
            return Extraccion(null, buffer)
        }

        return Extraccion(
            trama = buffer.copyOfRange(0, declarada),
            restante = buffer.copyOfRange(declarada, buffer.size)
        )
    }

    // ------------------------------ SUENO ------------------------------------
    /** Tipos de tramo de sueno del protocolo Moyoung/K56. */
    object TipoSueno {
        const val DESPIERTO = 0
        const val LIGERO = 1
        const val PROFUNDO = 2
    }

    /**
     * Sueno de una noche leido del reloj, en minutos por fase.
     * La calidad (0-100) es una ESTIMACION propia de Panda Fit a partir del
     * porcentaje de sueno profundo y del tiempo despierto; el reloj no la envia.
     */
    /** Un tramo de sueno: tipo (TipoSueno), minuto del dia en que empieza y duracion. */
    data class TramoSueno(val tipo: Int, val inicioMin: Int, val duracionMin: Int)

    data class SuenoBanda(
        val profundoMin: Int,
        val ligeroMin: Int,
        val despiertoMin: Int,
        /** Tramos en orden, para dibujar el hipnograma. */
        val tramos: List<TramoSueno> = emptyList(),
        /** Minuto del dia en que se durmio (-1 si no se sabe). */
        val dormirMin: Int = -1,
        /** Minuto del dia en que desperto (-1 si no se sabe). */
        val despertarMin: Int = -1
    ) {
        val dormidoMin: Int get() = profundoMin + ligeroMin
        val horas: Double get() = dormidoMin / 60.0
        val calidad: Int
            get() {
                val enCama = dormidoMin + despiertoMin
                if (enCama <= 0) return 0
                val profundo = profundoMin.toDouble() / dormidoMin.coerceAtLeast(1)
                val despierto = despiertoMin.toDouble() / enCama
                return (40 + profundo * 150 - despierto * 100).toInt().coerceIn(0, 100)
            }
    }

    /**
     * Respuesta de 0x32 (sueno de hoy) o 0x33 con argumento 3/4 (noches
     * anteriores). El payload es una lista de registros de 3 bytes
     * `{tipo, hora, minuto}`: cada registro marca el INICIO de una fase y dura
     * hasta el inicio del siguiente. El ultimo registro solo marca el final.
     * En 0x33 llega delante un byte con el dia pedido, que se descarta.
     *
     * Devuelve null si los datos no son coherentes (Panda Fit nunca inventa).
     */
    fun leerSueno(payload: ByteArray): SuenoBanda? {
        var datos = payload
        if (datos.size % 3 == 1) datos = datos.copyOfRange(1, datos.size)
        if (datos.size < 6 || datos.size % 3 != 0) return null

        val n = datos.size / 3
        val tipos = IntArray(n)
        val inicios = IntArray(n)
        for (i in 0 until n) {
            val tipo = datos[i * 3].toInt() and 0xFF
            val hora = datos[i * 3 + 1].toInt() and 0xFF
            val minuto = datos[i * 3 + 2].toInt() and 0xFF
            if (tipo > TipoSueno.PROFUNDO || hora > 23 || minuto > 59) return null
            tipos[i] = tipo
            inicios[i] = hora * 60 + minuto
        }

        var profundo = 0
        var ligero = 0
        var despierto = 0
        val tramos = ArrayList<TramoSueno>(n)
        for (i in 0 until n - 1) {
            var duracion = inicios[i + 1] - inicios[i]
            if (duracion < 0) duracion += 24 * 60          // cruza la medianoche
            if (duracion > 12 * 60) return null             // tramo imposible
            when (tipos[i]) {
                TipoSueno.PROFUNDO -> profundo += duracion
                TipoSueno.LIGERO -> ligero += duracion
                else -> despierto += duracion
            }
            if (duracion > 0) tramos += TramoSueno(tipos[i], inicios[i], duracion)
        }
        val dormido = profundo + ligero
        if (dormido <= 0 || dormido > 16 * 60) return null
        // Dormirse = primer tramo de sueno; despertar = fin del ultimo tramo de sueno.
        val primero = tramos.firstOrNull { it.tipo != TipoSueno.DESPIERTO }
        val ultimo = tramos.lastOrNull { it.tipo != TipoSueno.DESPIERTO }
        return SuenoBanda(
            profundoMin = profundo,
            ligeroMin = ligero,
            despiertoMin = despierto,
            tramos = tramos,
            dormirMin = primero?.inicioMin ?: -1,
            despertarMin = ultimo?.let { (it.inicioMin + it.duracionMin) % (24 * 60) } ?: -1
        )
    }

    /**
     * Podometro WeChat (0xFEA1): `{flags, pasos u24 LE, [distancia u24], [kcal u24]}`.
     * El bit 0 de flags indica que vienen pasos. Devuelve null si no hay.
     */
    fun leerPasosWeChat(data: ByteArray): Int? {
        if (data.size < 4) return null
        val flags = data[0].toInt() and 0xFF
        if ((flags and 0x01) == 0) return null
        val pasos = (data[1].toInt() and 0xFF) or
            ((data[2].toInt() and 0xFF) shl 8) or
            ((data[3].toInt() and 0xFF) shl 16)
        return pasos.takeIf { it in 0..200_000 }
    }

    // ------------------------------ LECTORES ---------------------------------
    /**
     * Respuesta de 0x6D (pulso en vivo): {bpm:u8}. Devuelve 0 si no es valido.
     */
    fun leerBpm(payload: ByteArray): Int {
        if (payload.isEmpty()) return 0
        val valor = payload[0].toInt() and 0xFF
        return if (valor in BPM_MIN..BPM_MAX) valor else 0
    }

    /** Lista de pulsos historicos: cada byte plausible del payload. */
    fun leerPulsosHistoricos(payload: ByteArray): List<Int> = payload
        .map { it.toInt() and 0xFF }
        .filter { it in BPM_MIN..BPM_MAX }

    /**
     * Respuesta de 0x6B (oxigeno). Lo habitual es {spo2:u8} en la posicion 0,
     * pero algunos firmwares anteponen un byte de estado (por ejemplo "00 62 00"
     * son 98 %): se acepta el PRIMER byte plausible de los cuatro primeros.
     */
    fun leerSpo2(payload: ByteArray): Int {
        if (payload.isEmpty()) return 0
        val limite = if (payload.size < 4) payload.size else 4
        for (indice in 0 until limite) {
            val valor = payload[indice].toInt() and 0xFF
            if (valor in SPO2_MIN..SPO2_MAX) return valor
        }
        return 0
    }

    /**
     * Respuesta de 0x69 (presion): {sinUsar:u8, sistolica:u8, diastolica:u8}.
     * Devuelve Pair(sistolica, diastolica) o null si no es valida.
     */
    fun leerPresion(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size < 3) return null
        val sistolica = payload[1].toInt() and 0xFF
        val diastolica = payload[2].toInt() and 0xFF
        if (sistolica !in SISTOLICA_MIN..SISTOLICA_MAX) return null
        if (diastolica !in DIASTOLICA_MIN..DIASTOLICA_MAX) return null
        return Pair(sistolica, diastolica)
    }

    /**
     * Pasos publicados en el canal 0xFEE1: tres enteros de 24 bits little-endian
     * SIN byte de prefijo (pasos, calorias, distancia en metros).
     * Tambien acepta un contador suelto de 3 o 4 bytes.
     * Devuelve Triple(pasos, calorias, distanciaMetros) o null.
     */
    fun leerPasosCanal(payload: ByteArray): Triple<Int, Int, Int>? {
        if (payload.size < 3) return null

        fun u24le(offset: Int): Int =
            (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                ((payload[offset + 2].toInt() and 0xFF) shl 16)

        fun u24be(offset: Int): Int =
            ((payload[offset].toInt() and 0xFF) shl 16) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                (payload[offset + 2].toInt() and 0xFF)

        if (payload.size >= 9) {
            val le = Triple(u24le(0), u24le(3), u24le(6))
            if (le.first in 0..200_000) return le
            val be = Triple(u24be(0), u24be(3), u24be(6))
            if (be.first in 0..200_000) return be
        }
        if (payload.size >= 4) {
            val valor = (payload[0].toInt() and 0xFF) or
                ((payload[1].toInt() and 0xFF) shl 8) or
                ((payload[2].toInt() and 0xFF) shl 16) or
                ((payload[3].toInt() and 0xFF) shl 24)
            if (valor in 0..200_000) return Triple(valor, 0, 0)
        }
        val corto = u24le(0)
        return if (corto in 0..200_000) Triple(corto, 0, 0) else null
    }

    // ========================================================================
    //  PARSEADORES DEL PERFIL ESTANDAR
    // ========================================================================

    /**
     * Frecuencia cardiaca segun el perfil estandar (0x2A37):
     *   byte 0 = flags; bit0: 0 = uint8, 1 = uint16.
     */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val isUint16 = (flags and 0x01) != 0
        val value = if (isUint16) {
            if (data.size < 3) return null
            ((data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8))
        } else {
            if (data.size < 2) return null
            (data[1].toInt() and 0xFF)
        }
        return value.takeIf { it in BPM_MIN..BPM_MAX }
    }

    /**
     * SpO2 segun el perfil PLX (0x2A5F): SFLOAT de 2 bytes + pulso SFLOAT.
     */
    fun parseSpo2(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 4) return null
        val spo2 = sfloatToInt(data, 0)
        val pulse = sfloatToInt(data, 2)
        return Pair(spo2, pulse)
    }

    /** Convierte un SFLOAT de 2 bytes (IEEE-11073) a entero. */
    private fun sfloatToInt(data: ByteArray, offset: Int): Int {
        val raw = ((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8))
        var mantissa = raw and 0x0FFF
        var exponent = raw shr 12
        if (exponent >= 0x0008) exponent -= 0x0010
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var value = mantissa
        repeat(kotlin.math.abs(exponent)) {
            value = if (exponent > 0) value * 10 else value / 10
        }
        return value
    }

    /** Pasos propietarios empaquetados en 2 bytes little-endian. */
    fun parseUint16Le(data: ByteArray, offset: Int = 0): Int? {
        if (data.size < offset + 2) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Bateria estandar (0x2A19): un byte 0-100. */
    fun parseBattery(data: ByteArray): Int? =
        if (data.isEmpty()) null else (data[0].toInt() and 0xFF).coerceIn(0, 100)

    /** Nombre del dispositivo desde 0x2A00. */
    fun parseDeviceName(data: ByteArray): String =
        runCatching { String(data, Charsets.UTF_8).trim('\u0000', ' ') }.getOrDefault("")

    /**
     * Prefijos de nombre tipicos de pulseras/relojes compatibles (K56 Pro,
     * DaFit/Moyoung, H Band...). Solo se usa para ORDENAR la lista de escaneo.
     */
    private val PREFIJOS_RELOJ = listOf(
        // DaFit / Moyoung y clones
        "K56", "K5", "DAFIT", "DA FIT", "MOYOUNG", "H BAND", "HBAND", "AGEFIT",
        "FITCLOUD", "C20", "C60", "C61", "M5", "M6", "M7", "Y68", "D20", "D13",
        "P8", "P22", "T500", "W26", "HW", "QS", "ID1", "ID2", "VERYFIT", "LEFUN",
        "ZEBLAZE", "COLMI", "KOSPET", "IWOWN",
        // Marcas que publican pulso por el perfil estandar (modo "compartir FC")
        "AMAZFIT", "MI BAND", "MI SMART BAND", "XIAOMI", "REDMI", "HUAWEI", "HONOR",
        "GALAXY", "GARMIN", "POLAR", "COROS", "SUUNTO", "WAHOO", "HRM", "FITBIT",
        // Genericos
        "WATCH", "BAND", "FIT", "SMART"
    )

    /** true si el nombre anunciado parece el de una pulsera o reloj. */
    fun pareceReloj(nombre: String?): Boolean {
        val n = nombre?.trim()?.uppercase() ?: return false
        if (n.isEmpty()) return false
        // Prefijos cortos (P8, HW, QS...) solo al INICIO del nombre, para no
        // confundir "[TV] Samsung" o una MAC con un reloj.
        return PREFIJOS_RELOJ.any {
            n.startsWith(it) || n.contains(" $it") || (it.length >= 4 && n.contains(it))
        }
    }

    /** Etiqueta legible de un UUID, para los diagnosticos de Logcat. */
    fun nombreCorto(uuid: UUID): String = when (uuid) {
        K56_SERVICE -> "K56_SERVICE(FEE7)"
        K56_NOTIFY -> "K56_NOTIFY(FEA1)"
        K56_READ_COMMAND -> "K56_CMD(FEC9)"
        MOYOUNG_SERVICE_MAIN -> "MOYOUNG(FEEA)"
        MOYOUNG_CHAR_WRITE -> "MOYOUNG_WRITE(FEE2)"
        MOYOUNG_CHAR_NOTIFY -> "MOYOUNG_NOTIFY(FEE3)"
        MOYOUNG_CHAR_STEPS -> "MOYOUNG_STEPS(FEE1)"
        HEART_RATE_SERVICE -> "HEART_RATE(180D)"
        HEART_RATE_MEASUREMENT -> "HR_MEASURE(2A37)"
        BATTERY_SERVICE -> "BATTERY(180F)"
        BATTERY_LEVEL -> "BATTERY_LEVEL(2A19)"
        PLX_SERVICE -> "PLX(1822)"
        PLX_CONTINUOUS -> "SPO2(2A5F)"
        DEVICE_INFORMATION -> "DEVICE_INFO(180A)"
        GENERIC_ACCESS -> "GAP(1800)"
        else -> {
            val s = uuid.toString().lowercase()
            if (s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x${s.substring(4, 8).uppercase()}" else s
        }
    }
}
