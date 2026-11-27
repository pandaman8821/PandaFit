// ============================================================================
//  Panda Fit - BleManager.kt
//  CONECTIVIDAD BLE "mejorada al maximo":
//
//    * Escaneo agresivo (SCAN_MODE_LOW_LATENCY, 100% de ventana) para conexion
//      INSTANTANEA, y escaneo equilibrado cuando ya hay un dispositivo guardado.
//    * Conexion directa a la ultima banda conocida sin mostrar lista.
//    * Reconexion automatica con backoff exponencial + watchdog de enlace
//      (si el GATT se queda "colgado" mas de 25 s sin datos, se reconecta).
//    * Request de MTU 517, descubrimiento de servicios y suscripcion inteligente
//      a todos los canales de notificacion utiles (pulso, SpO2, bateria...).
//    * Cola de comandos con retardo (los relojes economicos se saturan si les
//      escribes dos comandos seguidos).
//    * Parsers de perfil ESTANDAR (0x180D, 0x1822, 0x180F) + propietarios.
//    * Los datos de salud NUNCA se quedan en RAM: se emiten por StateFlow y el
//      servicio de segundo plano los persiste + sube a Firestore.
// ============================================================================
package com.pandafit.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.pandafit.app.data.model.BleState
import com.pandafit.app.data.model.LiveVitals
import com.pandafit.app.data.model.PandaDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class BleManager(context: Context) {

    /**
     * Contexto de APLICACION (nunca una Activity): este objeto vive toda la
     * sesion y sus callbacks de GATT, asi que no puede retener una pantalla.
     */
    private val appContext: Context = context.applicationContext

    // ------------------------------------------------------------------ ESTADO
    private val _state = MutableStateFlow(BleState.DISCONNECTED)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<PandaDevice>>(emptyList())
    val devices: StateFlow<List<PandaDevice>> = _devices.asStateFlow()

    private val _vitals = MutableStateFlow(LiveVitals())
    val vitals: StateFlow<LiveVitals> = _vitals.asStateFlow()

    private val _stepsFromBand = MutableStateFlow<Int?>(null)
    val stepsFromBand: StateFlow<Int?> = _stepsFromBand.asStateFlow()

    /** Sueno de la ultima noche segun el reloj (null hasta que lo envie). */
    /**
     * SUENO GUARDADO EN PANDA FIT (v10). Pedir el sueno al reloj (0x32) puede
     * dejarlo a cero en el propio reloj (paso con el K56 Pro real), asi que:
     *   * se pide UNA SOLA VEZ AL DIA (desde las 6:00, en la primera conexion),
     *     nunca en cada reconexion ni al sincronizar;
     *   * lo recibido se GUARDA en el telefono: aunque el reloj vuelva a mostrar
     *     0, la noche sigue en la app;
     *   * una respuesta vacia o incoherente NUNCA sustituye a lo guardado.
     */
    private val suenoPrefs = appContext.getSharedPreferences("panda_sueno", Context.MODE_PRIVATE)

    private val _sleepFromBand = MutableStateFlow(cargarSuenoGuardado())
    val sleepFromBand: StateFlow<BleProtocol.SuenoBanda?> = _sleepFromBand.asStateFlow()

    private fun hoyKey(): String = java.time.LocalDate.now().toString()

    private fun guardarSueno(s: BleProtocol.SuenoBanda) {
        val tramos = s.tramos.joinToString(";") { "${it.tipo},${it.inicioMin},${it.duracionMin}" }
        suenoPrefs.edit()
            .putString("fecha", hoyKey())
            .putString(
                "datos",
                "${s.profundoMin}|${s.ligeroMin}|${s.despiertoMin}|${s.dormirMin}|${s.despertarMin}|$tramos"
            )
            .apply()
    }

    private fun cargarSuenoGuardado(): BleProtocol.SuenoBanda? = runCatching {
        if (suenoPrefs.getString("fecha", null) != hoyKey()) return@runCatching null
        val p = suenoPrefs.getString("datos", null)?.split("|") ?: return@runCatching null
        if (p.size < 6) return@runCatching null
        val tramos = p[5].split(";").filter { it.isNotBlank() }.map { t ->
            val (tipo, ini, dur) = t.split(",").map { it.toInt() }
            BleProtocol.TramoSueno(tipo, ini, dur)
        }
        BleProtocol.SuenoBanda(
            profundoMin = p[0].toInt(), ligeroMin = p[1].toInt(), despiertoMin = p[2].toInt(),
            tramos = tramos, dormirMin = p[3].toInt(), despertarMin = p[4].toInt()
        )
    }.getOrNull()

    /**
     * 0.1.6: toca pedir el sueno si son las 6:00-13:00, ha pasado 1 hora desde
     * el ultimo pedido y la lectura de hoy aun no es la definitiva (ver LecturaSueno).
     */
    private fun tocaLeerSuenoHoy(): Boolean {
        val ultimo = if (suenoPrefs.getString("pedido", null) == hoyKey()) suenoPrefs.getLong("pedido_ms", 0L) else 0L
        val completo = suenoPrefs.getString("completo", null) == hoyKey()
        return LecturaSueno.tocaPedir(java.time.LocalDateTime.now(), ultimo, System.currentTimeMillis(), completo)
    }

    /** Envia el pedido de sueno (0x32) y lo apunta ANTES de enviar (nunca se insiste). */
    private suspend fun pedirSueno(g: BluetoothGatt, esK56: Boolean, motivo: String) {
        suenoPrefs.edit().putString("pedido", hoyKey()).putLong("pedido_ms", System.currentTimeMillis()).apply()
        delay(400)
        _log.emit(motivo)
        if (esK56) requestK56(g, BleProtocol.Cmd.SUENO_HOY, BleProtocol.payloadSuenoHoy())
        else writeCommand(g, BleProtocol.Cmd.SUENO_HOY, BleProtocol.payloadSuenoHoy())
    }

    /**
     * 0.1.6: lo llama el servicio cada minuto. Si la lectura ya es definitiva
     * (te levantaste y caminas) la marca; si no, y toca, vuelve a pedir el sueno
     * (como mucho 1 vez por hora, de 6:00 a 13:00).
     */
    fun revisarSuenoProgramado(pasosHoy: Int) {
        val ahora = java.time.LocalTime.now()
        val sueno = _sleepFromBand.value
        if (suenoPrefs.getString("completo", null) != hoyKey() &&
            LecturaSueno.lecturaCompleta(sueno?.despertarMin, ahora.hour * 60 + ahora.minute, pasosHoy)
        ) {
            suenoPrefs.edit().putString("completo", hoyKey()).apply()
            _log.tryEmit("Sueño de anoche completo: ya no se pide más hoy")
            return
        }
        val g = gatt ?: return
        if (_state.value != BleState.CONNECTED || familia?.aceptaComandosMoyoung != true) return
        if (!tocaLeerSuenoHoy()) return
        scope.launch { pedirSueno(g, usarCanalK56(g), "Actualizando el sueño de anoche (cada hora hasta que te levantes)") }
    }

    /** Ultima presion arterial medida por el reloj: (sistolica, diastolica). */
    private val _presion = MutableStateFlow<Pair<Int, Int>?>(null)
    val bloodPressure: StateFlow<Pair<Int, Int>?> = _presion.asStateFlow()

    /** Medianoche: los contadores del reloj vuelven a 0 y el sueno es de otra noche. */
    fun nuevoDia() {
        _stepsFromBand.value = null
        // El sueno guardado es de la noche anterior: con el dia nuevo se recarga
        // (sera null hasta que se lea el de esta noche).
        _sleepFromBand.value = cargarSuenoGuardado()
    }

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ GATT
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var targetAddress: String? = null
    /** Buffer de reensamblado: el K56 Pro parte las respuestas en varios paquetes BLE. */
    @Volatile
    private var reassemblyBuffer: ByteArray = ByteArray(0)

    private var lastPacketAt: Long = 0L
    private var reconnectAttempts = 0
    private var watchdogJob: Job? = null
    private var autoReconnect = true

    /**
     * Android solo admite UNA operacion GATT a la vez. Si se solapan dos
     * escrituras (o una escritura con un descubrimiento), la segunda devuelve
     * error y el reloj deja de responder: causa directa de "no sincroniza".
     * Este mutex serializa todas las operaciones.
     */
    private val gattLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Operacion GATT en curso. El mutex solo no basta: Android responde cada
     * operacion de forma ASINCRONA (onDescriptorWrite, onCharacteristicWrite,
     * onCharacteristicRead, onMtuChanged, onServicesDiscovered). Hasta que llega
     * ese callback, cualquier otra operacion devuelve "busy" y se pierde. Antes
     * se encadenaban 3-7 writeDescriptor seguidos y solo el PRIMERO se aplicaba:
     * el reloj no quedaba suscrito, no llegaban datos y el watchdog cortaba el
     * enlace en bucle ("no conecta la pulsera").
     */
    @Volatile
    private var pendingOp: CompletableDeferred<Boolean>? = null

    /** Familia del reloj conectado (decide si se le envian comandos o solo se escucha). */
    @Volatile
    var familia: BleProtocol.FamiliaReloj? = null
        private set

    /** Fabricante / modelo / firmware leidos del servicio estandar 0x180A. */
    private val infoDispositivo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Identifica el intento de conexion vigente (para descartar timeouts viejos). */
    @Volatile
    private var connectToken = 0

    /**
     * Ejecuta UNA operacion GATT y espera a su callback (o a [timeoutMs]).
     * @param start lanza la operacion; devuelve true si Android la acepto.
     */
    private suspend fun gattOp(timeoutMs: Long = 2_500, start: () -> Boolean): Boolean =
        gattLock.withLock {
            val espera = CompletableDeferred<Boolean>()
            pendingOp = espera
            val aceptada = runCatching(start).getOrDefault(false)
            val ok = if (aceptada) {
                withTimeoutOrNull(timeoutMs) { espera.await() } ?: false
            } else {
                false
            }
            pendingOp = null
            ok
        }

    private fun completeOp(ok: Boolean) {
        pendingOp?.complete(ok)
    }

    private val bluetoothManager: BluetoothManager?
        get() = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    val connectedName: String
        // Leer device.name exige BLUETOOTH_CONNECT en API 31+: si el usuario la
        // revoca, se cae al nombre publicitario o a la direccion (nunca lanza).
        @SuppressLint("MissingPermission")
        get() = runCatching { connectedDevice?.name }.getOrNull()
            ?: connectedDevice?.address ?: ""

    // ========================================================================
    //  PERMISOS
    // ========================================================================
    private fun hasBlePermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ========================================================================
    //  ESCANEO
    // ========================================================================
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            // device.name exige BLUETOOTH_CONNECT: se prefiere el nombre del
            // anuncio (no requiere permiso) y, si falla, se usa la direccion.
            val name = runCatching { result.scanRecord?.deviceName ?: device.name }.getOrNull()
            val label = name?.takeIf { it.isNotBlank() } ?: device.address
            val previous = _devices.value.firstOrNull { it.address == device.address }
            val item = PandaDevice(
                address = device.address,
                name = label,
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis()
            )
            _devices.value = ordenarDispositivos(
                _devices.value.filterNot { it.address == device.address } + item
            )
            if (previous == null) {
                scope.launch { _log.emit("Encontrado: $label (${result.rssi} dBm)") }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch { _log.emit("Escaneo fallido (codigo $errorCode)") }
            _state.value = BleState.ERROR
        }
    }

    /**
     * Inicia el escaneo BLE.
     * @param aggressive true = latencia minima (conexion instantanea).
     */
    @SuppressLint("MissingPermission")
    fun startScan(aggressive: Boolean = true) {
        if (!hasBlePermissions()) {
            scope.launch { _log.emit("Faltan permisos de Bluetooth cercano") }
            _state.value = BleState.ERROR
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = BleState.ERROR
            return
        }
        stopScan()
        // Un reloj YA conectado al telefono (por la app DaFit o desde Ajustes >
        // Bluetooth) deja de anunciarse y nunca aparecia en el escaneo. Se
        // anaden a la lista los relojes emparejados y los enlazados por GATT.
        _devices.value = ordenarDispositivos(dispositivosYaConocidos())
        _state.value = BleState.SCANNING

        val settings = ScanSettings.Builder()
            .setScanMode(
                if (aggressive) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED
            )
            .setReportDelay(0)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        // En modo agresivo no filtramos (queremos ver TODO ya).
        val filters = if (aggressive) emptyList<ScanFilter>() else emptyList()
        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure {
                _state.value = BleState.ERROR
                scope.launch { _log.emit("No se pudo escanear: ${it.message}") }
            }

        // El escaneo agresivo se corta solo a los 20 s para no gastar bateria.
        main.postDelayed({
            if (_state.value == BleState.SCANNING) stopScan()
        }, if (aggressive) 20_000L else 45_000L)
    }

    /** Relojes emparejados o ya conectados por GATT (no se anuncian por radio). */
    @SuppressLint("MissingPermission")
    private fun dispositivosYaConocidos(): List<PandaDevice> {
        val conectados = runCatching {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        }.getOrDefault(emptyList())
        val emparejados = runCatching {
            adapter?.bondedDevices?.filter {
                it.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    it.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }.orEmpty()
        }.getOrDefault(emptyList())
        return (conectados + emparejados)
            .distinctBy { it.address }
            .map { d ->
                val nombre = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() }
                PandaDevice(
                    address = d.address,
                    name = nombre ?: d.address,
                    // Sin RSSI real: se marca como senal media para que se vea.
                    rssi = -70
                )
            }
    }

    /**
     * Orden de la lista: primero los que parecen relojes (K56, DaFit...), luego
     * los que tienen nombre y al final los anonimos (solo MAC), que suelen ser
     * auriculares, TV o balizas. Antes se ordenaba solo por senal y la pulsera
     * quedaba enterrada entre decenas de MAC sin nombre.
     */
    private fun ordenarDispositivos(lista: List<PandaDevice>): List<PandaDevice> =
        lista.sortedWith(
            compareByDescending<PandaDevice> { BleProtocol.pareceReloj(it.name) }
                .thenByDescending { it.name != it.address }
                .thenByDescending { it.rssi }
        )

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == BleState.SCANNING) _state.value = BleState.DISCONNECTED
    }

    // ========================================================================
    //  CONEXION
    // ========================================================================

    /** Conectar por direccion MAC (conexion directa, sin pasar por la lista). */
    @SuppressLint("MissingPermission")
    fun connect(
        address: String,
        autoReconnect: Boolean = true,
        autoConnect: Boolean = false
    ) {
        if (!hasBlePermissions()) {
            _state.value = BleState.ERROR
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: run {
            _state.value = BleState.ERROR
            return
        }
        this.autoReconnect = autoReconnect
        targetAddress = address
        stopScan()
        _state.value = BleState.CONNECTING
        // El buffer de reensamblado pertenece al enlace anterior.
        reassemblyBuffer = ByteArray(0)
        scope.launch { _log.emit("Conectando a $address ...") }
        closeGatt()
        familia = null
        val token = ++connectToken
        // autoConnect=true (desde el 3er reintento): Android espera al reloj
        // sin limite y conecta en cuanto se anuncia. Antes se ignoraba y
        // siempre se usaba false.
        gatt = runCatching {
            device.connectGatt(appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            _state.value = BleState.ERROR
            scope.launch { _log.emit("Android rechazo la conexion GATT") }
            return
        }
        connectedDevice = device
        // Con autoConnect no hay timeout: la espera la gestiona el sistema.
        if (!autoConnect) armarTimeoutDeConexion(token)
    }

    /**
     * Si en 12 s no se completa la conexion, se informa y se reintenta.
     *
     * Sin esto, un K56 Pro que rechaza el primer intento (pasa a menudo si el
     * reloj ya esta emparejado con la app oficial DaFit) se quedaba en
     * "Conectando..." indefinidamente y parecia que no sincronizaba.
     */
    private fun armarTimeoutDeConexion(token: Int) {
        scope.launch {
            // 15 s: algunos relojes economicos tardan >12 s en el primer enlace.
            delay(15_000)
            // Solo actua sobre SU intento: un timeout viejo no debe cortar uno nuevo.
            if (token == connectToken && _state.value == BleState.CONNECTING) {
                _log.emit("La conexion tardo demasiado: reintentando")
                closeGatt()
                _state.value = BleState.RECONNECTING
                scheduleReconnect()
            }
        }
    }

    /** Conexion al ultimo dispositivo conocido (arranque instantaneo). */
    fun connectLastKnown(address: String?) {
        if (!address.isNullOrBlank()) connect(address)
    }

    fun disconnect() {
        autoReconnect = false
        watchdogJob?.cancel()
        scope.launch { _log.emit("Desconectado por el usuario") }
        closeGatt()
        _state.value = BleState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        // Libera a quien esperaba un callback de este enlace.
        completeOp(false)
        // El buffer de reensamblado pertenece al enlace anterior: se descarta.
        reassemblyBuffer = ByteArray(0)
    }

    // ========================================================================
    //  CALLBACK GATT
    // ========================================================================
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Eventos de un enlace VIEJO (ya sustituido) no deben tocar el actual:
            // antes su "desconectado" cerraba la conexion nueva recien abierta.
            val actual = gatt
            if (actual != null && g !== actual) {
                runCatching { g.close() }
                return
            }
            when {
                newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS -> {
                    reconnectAttempts = 0
                    lastPacketAt = System.currentTimeMillis()
                    _state.value = BleState.CONNECTED
                    scope.launch {
                        _log.emit("Conectado: ${connectedName.ifBlank { "banda" }}")
                    }
                    watchdogJob?.cancel()
                    scope.launch { prepararEnlace(g) }
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    // "Conectado" con error (p. ej. 133): el enlace no sirve.
                    scope.launch { _log.emit("Conexion con error (status=$status): reintentando") }
                    _state.value = BleState.RECONNECTING
                    closeGatt()
                    scheduleReconnect("status $status")
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = if (autoReconnect) BleState.RECONNECTING else BleState.DISCONNECTED
                    // Status 0 = desconexion normal; 8 = enlace perdido por
                    // supervision (fuera de rango); 19 = el periferico termino;
                    // 133 = fallo generico de la pila (muy comun, se reintenta).
                    scope.launch { _log.emit("Enlace caido (status=$status)") }
                    watchdogJob?.cancel()
                    closeGatt()
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch { _log.emit("MTU negociado: $mtu (status $status)") }
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristic(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(characteristic.uuid, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                handleCharacteristic(characteristic.uuid, characteristic.value ?: ByteArray(0))
            }
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristic(characteristic.uuid, value)
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Secuencia tras conectar, UNA operacion cada vez y esperando su respuesta:
     * MTU -> prioridad -> servicios -> suscripciones -> comandos -> watchdog.
     */
    @SuppressLint("MissingPermission")
    private suspend fun prepararEnlace(g: BluetoothGatt) {
        // Respiro: varios firmwares (y Android con enlace cifrado) rechazan
        // operaciones lanzadas en el mismo instante de la conexion.
        delay(600)
        if (gatt !== g) return

        // MTU grande = mas datos por notificacion. Si el reloj no responde,
        // el timeout deja seguir igual (se queda en 23).
        gattOp(timeoutMs = 3_000) { g.requestMtu(517) }
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        delay(200)
        if (gatt !== g) return

        // Descubrimiento de servicios (con un reintento).
        var descubierto = gattOp(timeoutMs = 10_000) { g.discoverServices() }
        if (!descubierto && gatt === g) {
            delay(800)
            descubierto = gattOp(timeoutMs = 10_000) { g.discoverServices() }
        }
        if (gatt !== g) return
        if (!descubierto || g.services.isNullOrEmpty()) {
            _log.emit("No se pudieron leer los servicios del reloj: reconectando")
            closeGatt()
            _state.value = BleState.RECONNECTING
            scheduleReconnect("sin servicios")
            return
        }
        _log.emit("Servicios descubiertos: ${g.services.size}")
        Log.i(TAG, dumpGattServices(g))

        val detectada = BleProtocol.detectarFamilia(g.services.map { it.uuid }.toSet())
        familia = detectada
        infoDispositivo.clear()
        _log.emit("Reloj detectado: ${detectada.etiqueta}")

        subscribeToKnownCharacteristics(g)
        if (gatt !== g) return
        startCommandQueue(g)
        startWatchdog(g)
    }

    // ========================================================================
    //  NOTIFICACIONES
    // ========================================================================
    @SuppressLint("MissingPermission")
    private suspend fun subscribeToKnownCharacteristics(g: BluetoothGatt) {
        val services = g.services ?: return
        var subscribed = 0
        var canalDeDatos = false
        for (service in services) {
            for (ch in service.characteristics) {
                val uuid = ch.uuid
                val notify = ch.properties and (
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0
                if (notify && BleProtocol.NOTIFY_PRIORITY.contains(uuid)) {
                    if (enableNotifications(g, ch)) {
                        subscribed++
                        if (uuid != BleProtocol.BATTERY_LEVEL) canalDeDatos = true
                    }
                }
            }
        }
        // Reloj de otra marca: si no hay ningun canal de DATOS conocido (la
        // bateria sola no cuenta), se escuchan los canales notificables de sus
        // servicios propios (max. 6). Antes solo se probaba si no habia NADA
        // suscrito, asi que un reloj con bateria estandar nunca se escuchaba.
        if (!canalDeDatos) {
            val candidatos = services
                .filterNot { it.uuid in BleProtocol.SERVICIOS_ESTANDAR_BASE }
                .flatMap { it.characteristics }
                .filter {
                    (it.properties and (
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE
                        )) != 0 && it.uuid !in BleProtocol.NOTIFY_PRIORITY
                }
                .take(6)
            for (ch in candidatos) {
                if (enableNotifications(g, ch)) subscribed++
            }
        }
        _log.emit(
            if (subscribed > 0) "Suscrito a $subscribed canal(es) de datos"
            else "Este dispositivo no publica notificaciones estandar"
        )
    }

    /** Activa notificaciones y ESPERA a que el reloj confirme el descriptor. */
    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val okLocal = runCatching {
            g.setCharacteristicNotification(characteristic, true)
        }.getOrDefault(false)
        val cccd = characteristic.getDescriptor(BleProtocol.CCCD) ?: return okLocal
        val value = if ((characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        ) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!okLocal) return false
        return gattOp {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: se compara con BluetoothStatusCodes (no con GATT_SUCCESS).
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    // ========================================================================
    //  COLA DE COMANDOS (evita saturar a los relojes economicos)
    // ========================================================================
    /**
     * Secuencia de arranque tras conectar. Se adapta al firmware detectado:
     *
     *   * K56 Pro (servicio 0xFEE7): sus comandos se piden por el canal 0xFEC9.
     *   * Moyoung clasico (0xFEEA): se escriben en 0xFEE2 (o 0xFEE5/0xFEE6).
     *
     * NUNCA se envia el comando de hora (0x31) al K56 Pro: su firmware lo
     * interpreta como hora local y BORRA los contadores diarios de pasos y sueno.
     */
    @SuppressLint("MissingPermission")
    private fun startCommandQueue(g: BluetoothGatt) {
        scope.launch {
            delay(600)   // el reloj necesita un respiro tras descubrir servicios
            val esK56 = usarCanalK56(g)
            val aceptaComandos = familia?.aceptaComandosMoyoung == true

            // Informacion del dispositivo (estandar, valida para cualquier marca).
            readIfPresent(g, BleProtocol.MANUFACTURER_NAME)
            readIfPresent(g, BleProtocol.MODEL_NUMBER)
            readIfPresent(g, BleProtocol.FIRMWARE_REVISION)

            comandosRespondidos.clear()
            if (aceptaComandos) {
                _log.emit(
                    if (esK56) "Canal de comandos: FEC9 (perfil K56)"
                    else "Canal de comandos: FEE2 (DaFit)"
                )
            }
            if (!aceptaComandos) {
                // Reloj de otra marca o solo perfil estandar: NO se le escriben
                // comandos DaFit (antes se escribian en el primer canal
                // escribible y podian confundir o bloquear al reloj). Solo se
                // escucha el pulso/SpO2 estandar y se lee la bateria.
                _log.emit("Modo solo escucha: pulso, SpO2 y bateria estandar")
            } else {
                // PROTECCION DE DATOS DEL RELOJ (v9): al conectar o reconectar ya
                // NO se escribe NINGUNA orden en el reloj. Hasta la v8 se enviaban
                // 0x33 (pasos de ayer), 0x35 (historial de pulso) y 0x32 (sueno)
                // en cada conexion; con el K56 Pro real (MOYOUNG-V2) el reloj se
                // quedo con el SUENO A CERO. Ninguna de esas ordenes esta
                // verificada en este firmware, asi que la app solo LEE canales
                // pasivos (pasos 0xFEE1, bateria, info) y escucha lo que el reloj
                // publique por su cuenta (presion, pasos WeChat, pulso estandar).
                _log.emit("Conexion en modo solo lectura: no se escribe nada en el reloj")
                // UNICA excepcion, pedida por el usuario: el sueno, 1 vez al dia.
                // Se marca como pedido ANTES de enviar: si la respuesta se pierde,
                // una reconexion no vuelve a pedirlo (no se insiste al reloj).
                // 0.1.6: de 6:00 a 13:00, cada hora hasta que te levantes.
                if (tocaLeerSuenoHoy()) pedirSueno(g, esK56, "Leyendo el sueño de anoche")
            }
            // IMPORTANTE: al conectar ya NO se arranca la medicion de pulso ni de
            // SpO2. Antes se hacia en cada conexion y reconexion y el reloj se
            // ponia a medir "solo". Ahora solo mide cuando el usuario pulsa un
            // boton (requestHeartRate / requestSpo2 / requestBloodPressure).

            // Canales estandar de apoyo (bateria, pasos directos y nombre).
            readIfPresent(g, BleProtocol.BATTERY_LEVEL)
            readIfPresent(g, BleProtocol.MOYOUNG_CHAR_STEPS)
            readIfPresent(g, BleProtocol.GENERIC_DEVICE_NAME)
        }
    }

    /**
     * Comprobado con el volcado de un K56 Pro real (fabricante "MOYOUNG-V2"):
     * el servicio 0xFEE7 (FEA1/FEC9) es el perfil de podometro de WeChat y
     * 0xFEC9 es de SOLO LECTURA, asi que los comandos enviados por ahi nunca
     * salian (ni pulso, ni SpO2, ni sueno). El canal de comandos real es
     * 0xFEE2 (servicio 0xFEEA) y las respuestas llegan por 0xFEE3.
     * Solo se usa la ruta FEC9 si el reloj NO tiene 0xFEE2.
     */
    private fun usarCanalK56(g: BluetoothGatt): Boolean =
        findCharacteristic(g, BleProtocol.MOYOUNG_CHAR_WRITE) == null &&
            (findCharacteristic(g, BleProtocol.K56_NOTIFY) != null ||
                findCharacteristic(g, BleProtocol.K56_READ_COMMAND) != null)

    /** Comandos a los que el reloj ya ha respondido en esta conexion (para el registro). */
    private val comandosRespondidos = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    )

    /** Escribe una trama Moyoung completa en el canal de comandos disponible. */
    @SuppressLint("MissingPermission")
    private suspend fun writeCommand(g: BluetoothGatt, cmd: Int, payload: ByteArray): Boolean {
        // MTU conservador: los relojes economicos ignoran tramas mas largas.
        var enviado = false
        BleProtocol.paquetes(cmd, payload, 20).forEach { trozo ->
            var canal = findCharacteristic(g, BleProtocol.MOYOUNG_CHAR_WRITE)
            if (canal == null) canal = findCharacteristic(g, BleProtocol.MOYOUNG_WRITE_ALT_1)
            if (canal == null) canal = findCharacteristic(g, BleProtocol.MOYOUNG_WRITE_ALT_2)
            if (canal == null) canal = writableCandidates(g).firstOrNull()
            if (writeCharacteristic(g, canal, trozo)) enviado = true
        }
        return enviado
    }

    /**
     * K56 Pro: pide un comando por su canal 0xFEC9. Si el firmware no lo acepta
     * como escritura, se intenta como LECTURA (asi responde el K56 Pro real).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestK56(g: BluetoothGatt, cmd: Int, payload: ByteArray): Boolean {
        val canal = findCharacteristic(g, BleProtocol.K56_READ_COMMAND)
            ?: findCharacteristic(g, BleProtocol.K56_NOTIFY)
            ?: return false
        BleProtocol.paquetes(cmd, payload, 20).forEach { trozo ->
            if (!writeCharacteristic(g, canal, trozo)) {
                readCharacteristicQueued(g, canal)
            }
        }
        return true
    }

    /**
     * Escribe en una caracteristica concreta (API 33+ y anteriores).
     *
     * Usa [gattLock] para NO solapar operaciones: Android solo admite una
     * operacion GATT a la vez y, si se solapan dos escrituras (o una escritura
     * con el descubrimiento de servicios), la segunda falla y el reloj deja de
     * responder. Esa era una causa directa de "no sincroniza".
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        data: ByteArray
    ): Boolean {
        if (characteristic == null) return false
        val puedeEscribir =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!puedeEscribir) return false

        val ok = gattOp(timeoutMs = 1_500) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val writeType = if ((characteristic.properties and
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    g.writeCharacteristic(characteristic, data, writeType) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(characteristic)
                }
            }
        // Margen para que el reloj procese antes de la siguiente operacion.
        delay(90)
        return ok
    }

    /** Lectura de una caracteristica a traves de la cola GATT. */
    @SuppressLint("MissingPermission")
    private suspend fun readCharacteristicQueued(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false
        }
        return gattOp { g.readCharacteristic(characteristic) }
    }

    /** Candidatos escribibles excluyendo los servicios estandar de apoyo. */
    @SuppressLint("MissingPermission")
    private fun writableCandidates(g: BluetoothGatt): List<BluetoothGattCharacteristic> {
        // Nunca se usa un canal del estandar (GAP/GATT/info/pulso/bateria) para
        // enviar comandos propietarios: ahi jamas esta el canal de comandos.
        val excluidos = setOf(
            BleProtocol.GENERIC_ACCESS,
            BleProtocol.GENERIC_ATTRIBUTE,
            BleProtocol.DEVICE_INFORMATION,
            BleProtocol.HEART_RATE_SERVICE,
            BleProtocol.BATTERY_SERVICE,
            BleProtocol.PLX_SERVICE
        )
        return g.services
            ?.filterNot { excluidos.contains(it.uuid) }
            ?.flatMap { it.characteristics }
            ?.filter {
                (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            ?: emptyList()
    }

    /**
     * VOLCADO DE DIAGNOSTICO: lista en Logcat todos los servicios y
     * caracteristicas del dispositivo conectado. Sirve para adaptar Panda Fit a
     * un reloj con firmware distinto. Filtro Logcat: `PandaFitBle` + `BLE_DUMP`.
     */
    @SuppressLint("MissingPermission")
    fun dumpGattServices(g: BluetoothGatt): String = buildString {
        appendLine("=== DISPOSITIVO: ${connectedName} ===")
        g.services?.forEach { service ->
            appendLine("SERVICIO ${service.uuid}  [${BleProtocol.nombreCorto(service.uuid)}]")
            service.characteristics?.forEach { ch ->
                val props = buildString {
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) append("R")
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) append("W")
                    if ((ch.properties and
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ) append("w")
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) append("N")
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) append("I")
                }
                appendLine("   CARACT ${ch.uuid}  [$props]  ${BleProtocol.nombreCorto(ch.uuid)}")
            }
        }
        appendLine("=== FIN DEL VOLCADO ===")
    }.also { Log.i(TAG, "BLE_DUMP\n$it") }

    /**
     * Escribe una trama cruda en el primer canal disponible. Se mantiene publica
     * porque la usan las pruebas manuales y el volcado de diagnostico.
     */
    @SuppressLint("MissingPermission")
    suspend fun writeToFirstAvailable(g: BluetoothGatt, preferred: UUID, data: ByteArray): Boolean {
        val characteristic = findCharacteristic(g, preferred)
            ?: writableCandidates(g).firstOrNull()
            ?: return false
        return writeCharacteristic(g, characteristic, data)
    }


    @SuppressLint("MissingPermission")
    suspend fun readIfPresent(g: BluetoothGatt, uuid: UUID): Boolean {
        val ch = findCharacteristic(g, uuid) ?: return false
        return readCharacteristicQueued(g, ch)
    }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services?.firstNotNullOfOrNull { service ->
            service.characteristics.firstOrNull { it.uuid == uuid }
        }

    // ========================================================================
    //  PROCESADO DE DATOS
    // ========================================================================
    private fun handleCharacteristic(uuid: UUID, data: ByteArray) {
        lastPacketAt = System.currentTimeMillis()
        when (uuid) {
            BleProtocol.HEART_RATE_MEASUREMENT -> {
                BleProtocol.parseHeartRate(data)?.let { hr ->
                    _vitals.value = _vitals.value.copy(
                        heartRate = hr,
                        measuredAt = System.currentTimeMillis(),
                        deviceName = connectedName
                    )
                }
            }
            BleProtocol.PLX_CONTINUOUS, BleProtocol.PLX_SPOT_CHECK -> {
                BleProtocol.parseSpo2(data)?.let { (spo2, pulse) ->
                    val current = _vitals.value
                    _vitals.value = current.copy(
                        spo2 = spo2,
                        heartRate = if (pulse in 30..220) pulse else current.heartRate,
                        measuredAt = System.currentTimeMillis(),
                        deviceName = connectedName
                    )
                }
            }
            BleProtocol.BATTERY_LEVEL -> {
                BleProtocol.parseBattery(data)?.let { battery ->
                    _vitals.value = _vitals.value.copy(batteryPercent = battery)
                }
            }
            BleProtocol.GENERIC_DEVICE_NAME -> {
                val name = BleProtocol.parseDeviceName(data)
                if (name.isNotBlank()) {
                    scope.launch { _log.emit("Dispositivo: $name") }
                }
            }
            BleProtocol.MANUFACTURER_NAME, BleProtocol.MODEL_NUMBER, BleProtocol.FIRMWARE_REVISION -> {
                val texto = BleProtocol.parseDeviceName(data)
                if (texto.isNotBlank()) {
                    val clave = when (uuid) {
                        BleProtocol.MANUFACTURER_NAME -> "Fabricante"
                        BleProtocol.MODEL_NUMBER -> "Modelo"
                        else -> "Firmware"
                    }
                    infoDispositivo[clave] = texto
                    scope.launch { _log.emit("$clave: $texto") }
                }
            }
            BleProtocol.K56_NOTIFY -> {
                // 0xFEA1 = podometro WeChat: {flags, pasos u24 LE, ...}. Solo
                // si llega una trama DaFit (FE EA) se trata como tal.
                val esTramaDaFit = data.size >= 2 &&
                    (data[0].toInt() and 0xFF) == 0xFE && (data[1].toInt() and 0xFF) == 0xEA
                if (esTramaDaFit) {
                    parseVendorFrame(uuid, data)
                } else {
                    BleProtocol.leerPasosWeChat(data)?.let { pasos ->
                        if (pasos > 0) {
                            _stepsFromBand.value = maxOf(pasos, _stepsFromBand.value ?: 0)
                        }
                    }
                }
            }
            BleProtocol.VENDOR_CHAR_A_NOTIFY,
            BleProtocol.VENDOR_CHAR_B_NOTIFY,
            BleProtocol.MOYOUNG_CHAR_NOTIFY,
            BleProtocol.MOYOUNG_CHAR_STEPS -> {
                // Tramas del reloj (Moyoung / K56 Pro) o contador de pasos.
                parseVendorFrame(uuid, data)
            }
            else -> {
                // Canal propio de un reloj de otra marca: antes se descartaba
                // sin mirarlo. Se intenta leer como trama DaFit/Moyoung (muchos
                // clones usan el mismo formato con otros UUID); si no lo es, no
                // se adivina nada (ver handleFrame).
                parseVendorFrame(uuid, data)
            }
        }
    }

    /**
     * Interpreta las notificaciones del reloj.
     *
     * ORDEN DE INTENTOS:
     *   1. Trama Moyoung (`0xFE 0xEA modo len cmd`): se lee segun el comando,
     *      que es lo que responde el K56 Pro a las peticiones de 0xFEC9.
     *   2. Canal de pasos 0xFEE1: tres enteros de 24 bits.
     *   3. Respaldo defensivo: si no encaja nada, no se publica nada (Panda Fit
     *      jamas inventa datos de salud).
     */
    private fun parseVendorFrame(uuid: UUID, data: ByteArray) {
        if (data.isEmpty()) return

        // ---- 0. REENSAMBLADO DE TRAMAS -------------------------------------
        // Causa real de "no sincroniza": el K56 Pro publica tramas MAS LARGAS que
        // el MTU de notificacion (20 bytes), asi que la respuesta del historico
        // llega partida en varias notificaciones. Interpretar cada trozo por
        // separado hacia que el parser descartara la respuesta entera.
        // La extraccion vive en BleProtocol.extraerTrama() para poder PROBARLA
        // sin dispositivo (ver BleFramesTest).
        reassemblyBuffer = if (reassemblyBuffer.isEmpty()) {
            data.copyOf()
        } else {
            reassemblyBuffer + data
        }
        if (reassemblyBuffer.size > BleProtocol.MAX_TRAMA) {
            _log.tryEmit("Trama demasiado grande; se descarta el buffer")
            reassemblyBuffer = ByteArray(0)
            return
        }

        // Se extraen TODAS las tramas completas del buffer: una sola
        // notificacion puede traer varias respuestas del reloj pegadas.
        var procesados = 0
        while (procesados < 20) {
            val extraccion = BleProtocol.extraerTrama(reassemblyBuffer)
            reassemblyBuffer = extraccion.restante
            val trama = extraccion.trama ?: break
            handleFrame(uuid, trama)
            procesados++
        }

        // Si no habia ninguna trama Moyoung valida, se intenta el parseo directo
        // (canales estandar y respuestas cortas de una sola notificacion).
        if (procesados == 0) {
            handleFrame(uuid, data)
        }
    }


    /** Interpreta UNA trama ya completa (Moyoung o de canal estandar). */
    private fun handleFrame(uuid: UUID, data: ByteArray) {
        if (data.isEmpty()) return

        // ---- 1. Protocolo Moyoung (incluye el K56 Pro) ----------------------
        BleProtocol.parsear(data)?.let { paquete ->
            applyMoyoungPacket(paquete)
            return
        }

        // ---- 2. Canal dedicado de pasos (0xFEE1) ---------------------------
        if (uuid == BleProtocol.MOYOUNG_CHAR_STEPS) {
            BleProtocol.leerPasosCanal(data)?.let { (pasos, _, _) ->
                if (pasos > 0) {
                    _stepsFromBand.value = maxOf(pasos, _stepsFromBand.value ?: 0)
                    scope.launch { _log.emit("Pasos del reloj: $pasos") }
                }
            }
            return
        }

        // ---- 3. Respaldo: bytes sueltos plausibles -------------------------
        // SOLO en los canales de datos conocidos de la familia DaFit. En un
        // canal desconocido, cualquier byte entre 30 y 220 se tomaria por
        // pulso y la app inventaria valores.
        // (0xFEA1 ya no entra: es el podometro WeChat y sus bytes de pasos se
        // confundian con pulso.)
        val canalConocido = uuid == BleProtocol.MOYOUNG_CHAR_NOTIFY ||
            uuid == BleProtocol.VENDOR_CHAR_A_NOTIFY ||
            uuid == BleProtocol.VENDOR_CHAR_B_NOTIFY
        if (!canalConocido) return
        val current = _vitals.value
        var heartRate = current.heartRate
        var spo2 = current.spo2
        if (data.size <= 4) {
            data.forEach { byte ->
                val valor = byte.toInt() and 0xFF
                if (valor in 30..220) heartRate = valor
                else if (valor in 90..100) spo2 = valor
            }
        }
        if (heartRate != current.heartRate || spo2 != current.spo2) {
            _vitals.value = current.copy(
                heartRate = heartRate,
                spo2 = spo2,
                measuredAt = System.currentTimeMillis(),
                deviceName = connectedName
            )
        }
    }

    private fun formatoSueno(s: BleProtocol.SuenoBanda): String =
        "${s.dormidoMin / 60} h ${s.dormidoMin % 60} min " +
            "(profundo ${s.profundoMin} min, ligero ${s.ligeroMin} min)"

    /** Aplica un paquete Moyoung ya validado a los flujos publicos. */
    private fun applyMoyoungPacket(paquete: BleProtocol.Paquete) {
        val current = _vitals.value
        var heartRate = current.heartRate
        var spo2 = current.spo2
        var cambio = false

        // Registro: la primera respuesta a cada comando confirma que el reloj
        // entiende lo que se le pide (clave para diagnosticar desde el movil).
        if (comandosRespondidos.add(paquete.comando)) {
            _log.tryEmit(
                "Respuesta del reloj: cmd 0x${paquete.comando.toString(16).uppercase()} " +
                    "(${paquete.payload.size} bytes)"
            )
        }

        when (paquete.comando) {
            // Pulso en vivo (0x6D)
            BleProtocol.Cmd.PULSOS_VIVO -> {
                val bpm = BleProtocol.leerBpm(paquete.payload)
                if (bpm > 0) { heartRate = bpm; cambio = true }
            }
            // Historico de pulso (0x35): se usa el ultimo valor valido
            BleProtocol.Cmd.PULSOS_HISTORICOS, BleProtocol.Cmd.PULSOS_HOY -> {
                BleProtocol.leerPulsosHistoricos(paquete.payload).lastOrNull()?.let {
                    heartRate = it; cambio = true
                }
            }
            // Oxigeno en sangre (0x6B)
            BleProtocol.Cmd.OXIGENO -> {
                val valor = BleProtocol.leerSpo2(paquete.payload)
                if (valor > 0) { spo2 = valor; cambio = true }
            }
            // Historico (0x33) de DIAS ANTERIORES: se pide con argumento 1
            // (pasos de AYER). Antes se sumaba a los pasos de HOY con maxOf, asi
            // que por la manana la app ensenaba los pasos de ayer como si fueran
            // de hoy. Ahora solo se registra; los pasos de hoy llegan por 0xFEE1.
            BleProtocol.Cmd.HISTORICO -> {
                val dia = paquete.payload.firstOrNull()?.toInt()?.and(0xFF)
                if (dia == BleProtocol.Arg.SUENO_AYER || dia == BleProtocol.Arg.SUENO_ANTEAYER) {
                    BleProtocol.leerSueno(paquete.payload)?.let { s ->
                        scope.launch { _log.emit("Sueño de noches anteriores: ${formatoSueno(s)}") }
                    }
                } else {
                    BleProtocol.leerPasosCanal(
                        if (paquete.payload.size % 3 == 1) paquete.payload.copyOfRange(1, paquete.payload.size)
                        else paquete.payload
                    )?.let { (pasos, _, _) ->
                        if (pasos > 0) scope.launch { _log.emit("Pasos de ayer segun el reloj: $pasos") }
                    }
                }
            }
            // Sueno de la ultima noche (0x32): tramos despierto/ligero/profundo.
            BleProtocol.Cmd.SUENO_HOY -> {
                val sueno = BleProtocol.leerSueno(paquete.payload)
                if (sueno != null) {
                    _sleepFromBand.value = sueno
                    guardarSueno(sueno)
                    scope.launch { _log.emit("Sueño del reloj guardado en Panda Fit: ${formatoSueno(sueno)}") }
                } else if (paquete.payload.size > 1) {
                    scope.launch {
                        _log.emit("Sueño recibido pero no se pudo leer (${paquete.payload.size} bytes)")
                    }
                }
            }
            // Presion arterial (0x69): se registra en el log (no se muestra aun)
            BleProtocol.Cmd.PRESION -> {
                BleProtocol.leerPresion(paquete.payload)?.let { (sis, dia) ->
                    _presion.value = sis to dia
                    scope.launch { _log.emit("Presion: $sis/$dia mmHg") }
                }
            }
        }

        // Fin de transaccion: {0x00}. Sirve para saber que el reloj ha terminado.
        if (BleProtocol.esFinDeTransaccion(paquete)) {
            scope.launch { _log.emit("Reloj: fin de transaccion (cmd 0x${paquete.comando.toString(16)})") }
        }

        if (cambio) {
            _vitals.value = current.copy(
                heartRate = heartRate,
                spo2 = spo2,
                measuredAt = System.currentTimeMillis(),
                deviceName = connectedName
            )
        }
    }

    // ========================================================================
    //  RECONEXION + WATCHDOG
    // ========================================================================
    /**
     * Reintenta la conexion con espera creciente (1, 2, 4, 8, 16 s; tope 30 s).
     *
     * A partir del TERCER intento se usa `autoConnect = true`: el propio sistema
     * mantiene viva la busqueda y conecta en cuanto el reloj este disponible.
     * Es lo que salva los casos en los que el K56 Pro rechaza varias conexiones
     * directas seguidas (pasa cuando esta emparejado con DaFit o justo salio de
     * modo ahorro).
     */
    private fun scheduleReconnect(motivo: String? = null) {
        if (!autoReconnect) return
        val address = targetAddress ?: return
        reconnectAttempts++
        val esperaMs = (1_000L * (1 shl (reconnectAttempts - 1).coerceAtMost(5)))
            .coerceAtMost(30_000L)
        val usarAutoConnect = reconnectAttempts >= 3
        scope.launch {
            val detalle = if (motivo != null) " ($motivo)" else ""
            _log.emit(
                "Reintento $reconnectAttempts en ${esperaMs / 1000}s$detalle" +
                    if (usarAutoConnect) " [autoConnect]" else " [directo]"
            )
            delay(esperaMs)
            if (autoReconnect && _state.value != BleState.CONNECTED) {
                _state.value = BleState.RECONNECTING
                connect(address, autoReconnect = true, autoConnect = usarAutoConnect)
            }
        }
    }

    /**
     * Vigilante del enlace. Un reloj en reposo puede pasar MINUTOS sin enviar
     * nada y el enlace sigue sano: antes se cortaba a los 25 s de silencio y la
     * pulsera entraba en un bucle conectar/desconectar. Ahora, tras 30 s de
     * silencio se hace un "ping" (lectura de bateria o nombre); solo si el ping
     * falla Y Android confirma que no hay enlace, se reconecta.
     */
    @SuppressLint("MissingPermission")
    private fun startWatchdog(g: BluetoothGatt) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var fallos = 0
            while (_state.value == BleState.CONNECTED && gatt === g) {
                delay(10_000)
                val quietFor = System.currentTimeMillis() - lastPacketAt
                if (quietFor < 30_000) continue

                val vivo = readIfPresent(g, BleProtocol.BATTERY_LEVEL) ||
                    readIfPresent(g, BleProtocol.GENERIC_DEVICE_NAME)
                val enlazado = runCatching {
                    bluetoothManager?.getConnectionState(g.device, BluetoothProfile.GATT) ==
                        BluetoothProfile.STATE_CONNECTED
                }.getOrDefault(true)

                if (vivo || enlazado) {
                    fallos = 0
                    lastPacketAt = System.currentTimeMillis()
                    continue
                }
                fallos++
                if (fallos >= 2 && gatt === g) {
                    _log.emit("El reloj no responde: reconectando")
                    closeGatt()
                    _state.value = BleState.RECONNECTING
                    scheduleReconnect("sin respuesta")
                    break
                }
            }
        }
    }

    /**
     * Fuerza una medicion inmediata (boton "Medir ahora" del inicio).
     * Se adapta al firmware: en K56 Pro las peticiones van por 0xFEC9.
     */
    fun requestImmediateMeasurement() {
        val g = gatt ?: return
        scope.launch {
            val esK56 = usarCanalK56(g)
            if (familia?.aceptaComandosMoyoung != true) {
                // Perfil estandar: el pulso llega solo por notificacion.
                _log.emit("Este reloj envia el pulso por su cuenta; mira la pantalla del reloj")
                readIfPresent(g, BleProtocol.BATTERY_LEVEL)
                return@launch
            }
            if (esK56) {
                requestK56(g, BleProtocol.Cmd.PULSOS_VIVO, BleProtocol.payloadPulsosEnVivo(true))
                delay(600)
                requestK56(g, BleProtocol.Cmd.OXIGENO, BleProtocol.payloadOxigeno(true))
            } else {
                writeCommand(g, BleProtocol.Cmd.PULSOS_VIVO, BleProtocol.payloadPulsosEnVivo(true))
                delay(600)
                writeCommand(g, BleProtocol.Cmd.OXIGENO, BleProtocol.payloadOxigeno(true))
            }
            // Pide tambien el contador de pasos del dia si el reloj lo expone.
            readIfPresent(g, BleProtocol.MOYOUNG_CHAR_STEPS)
        }
    }

    /** Ultima orden manual enviada (evita repetirla por dobles toques). */
    @Volatile
    private var ultimaOrdenManual = 0L

    /**
     * Envia UNA orden de medicion pedida por el usuario. El reloj solo mide una
     * cosa a la vez, asi que cada boton lanza solo su medicion. Se ignoran los
     * toques repetidos en menos de 3 s.
     */
    private fun ordenManual(cmd: Int, payload: ByteArray, aviso: String) {
        val g = gatt ?: run {
            _log.tryEmit("Conecta la pulsera primero")
            return
        }
        val ahora = System.currentTimeMillis()
        if (ahora - ultimaOrdenManual < 3_000) return
        ultimaOrdenManual = ahora
        scope.launch {
            if (familia?.aceptaComandosMoyoung != true) {
                _log.emit("Este reloj no admite esta medicion desde la app")
                return@launch
            }
            _log.emit(aviso)
            if (usarCanalK56(g)) requestK56(g, cmd, payload) else writeCommand(g, cmd, payload)
        }
    }

    /** Medir el pulso ahora (~20-30 s). */
    fun requestHeartRate() = ordenManual(
        BleProtocol.Cmd.PULSOS_VIVO, BleProtocol.payloadPulsosEnVivo(true),
        "Midiendo pulso: no muevas la muneca ~30 s"
    )

    /** Medir el oxigeno en sangre ahora (~30-40 s). */
    fun requestSpo2() = ordenManual(
        BleProtocol.Cmd.OXIGENO, BleProtocol.payloadOxigeno(true),
        "Midiendo oxigeno: no muevas la muneca ~40 s"
    )

    /** Presion arterial (0x69): el resultado llega en ~40-60 s. */
    fun requestBloodPressure() = ordenManual(
        BleProtocol.Cmd.PRESION, BleProtocol.payloadPresion(true),
        "Midiendo presion: deja el reloj quieto en la muneca ~1 min"
    )

    /**
     * DESACTIVADO (v9): pedir el sueno con 0x32 dejo el sueno del K56 Pro real a
     * cero. Hasta confirmar una orden de lectura que NO borre, no se envia nada.
     * Si el reloj publica el sueno por su cuenta, se sigue recogiendo.
     */
    fun requestSleep() {
        _log.tryEmit("Lectura de sueño desactivada: podia borrar el sueño del reloj")
    }

    /**
     * Pide al reloj una relectura COMPLETA (pasos, pulso, sueno y medicion en
     * vivo). Es lo que ejecuta el boton "Sincronizar banda" del inicio.
     */
    fun requestFullSync() {
        val g = gatt ?: return
        scope.launch {
            if (familia?.aceptaComandosMoyoung != true) {
                readIfPresent(g, BleProtocol.BATTERY_LEVEL)
                _log.emit("Reloj en modo solo escucha: no admite sincronizacion completa")
                return@launch
            }
            // v9: "Sincronizar" solo LEE canales pasivos. Ya no escribe 0x33,
            // 0x35 ni 0x32 (con el K56 Pro real el sueno del reloj quedo a cero).
            _log.emit("Leyendo datos del reloj (solo lectura)\u2026")
            // "Sincronizar" solo LEE datos; ya no pone al reloj a medir el pulso.
            readIfPresent(g, BleProtocol.MOYOUNG_CHAR_STEPS)
            readIfPresent(g, BleProtocol.BATTERY_LEVEL)
            _log.emit("Peticion enviada; esperando respuesta del reloj")
        }
    }

    /**
     * Vuelca por Logcat todos los servicios y caracteristicas del dispositivo
     * conectado. Util para confirmar que firmware tiene tu reloj y ampliar los
     * UUIDs de [BleProtocol] si hiciera falta.
     */
    /**
     * DIAGNOSTICO completo que funciona incluso SIN conexion activa: estado,
     * dispositivo objetivo, dispositivos vistos y servicios descubiertos (si
     * los hay). Es lo que se muestra en pantalla desde la tarjeta de conexion.
     */
    fun diagnostico(): String = buildString {
        appendLine("ESTADO: ${_state.value}")
        appendLine("Bluetooth encendido: $isBluetoothOn")
        appendLine("Permisos BLE: ${if (hasBlePermissions()) "concedidos" else "FALTAN"}")
        appendLine("Dispositivo objetivo: ${targetAddress ?: "ninguno"}")
        appendLine("Nombre conectado: ${connectedName.ifBlank { "ninguno" }}")
        appendLine("Familia de reloj: ${familia?.etiqueta ?: "sin detectar"}")
        infoDispositivo.forEach { (clave, valor) -> appendLine("$clave: $valor") }
        appendLine("Intentos de reconexion: $reconnectAttempts")
        if (_devices.value.isNotEmpty()) {
            appendLine()
            appendLine("DISPOSITIVOS VISTOS (${_devices.value.size}):")
            _devices.value.take(8).forEach { d ->
                appendLine("  ${d.name}  ${d.address}  ${d.rssi} dBm  ${d.signalBars}/4")
            }
        }
        val g = gatt
        if (g != null) {
            appendLine()
            append(dumpGattServices(g))
        } else {
            appendLine()
            appendLine("SIN CONEXION ABIERTA: no hay servicios que volcar.")
            appendLine("Toca el dispositivo en la lista para conectar.")
        }
    }

    fun dumpServices(): String {
        val g = gatt ?: return "Sin dispositivo conectado"
        return dumpGattServices(g)
    }

    /** Libera recursos (se llama desde Application.onTerminate/onLowMemory). */
    fun release() {
        autoReconnect = false
        watchdogJob?.cancel()
        closeGatt()
    }

    companion object {
        private const val TAG = "PandaFitBle"

        /**
         * Tamano minimo de trama para considerarla valida. Util para los tests.
         */
        const val MIN_FRAME = 4
    }
}
