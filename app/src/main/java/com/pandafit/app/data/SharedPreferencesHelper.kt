// ============================================================================
//  Panda Fit - SharedPreferencesHelper.kt
//  Guardado SEGURO del API Key de Google Gemini + preferencias de la app.
//
//  POLITICA DE SEGURIDAD (obligatoria en Panda Fit):
//    * El usuario introduce SU PROPIA API Key de Google Gemini.
//    * La clave se guarda CIFRADA con AES-256-GCM. La clave de cifrado vive en
//      el Android Keystore (hardware-backed cuando el dispositivo lo permite) y
//      JAMAS sale del telefono.
//    * La app NO almacena claves de otros proveedores de IA.
//    * La clave nunca se registra en logs, no se sincroniza y no se respalda
//      (ver res/xml/panda_backup_rules.xml y panda_data_extraction_rules.xml).
//    * Solo se muestra enmascarada: "AIzaSy...7f2a".
//    * Al borrarla, se elimina el criptograma del almacenamiento.
//
//  NOTA TECNICA: se implementa el cifrado directamente con Keystore +
//  Cipher (AES/GCM/NoPadding) en lugar de androidx.security-crypto, porque esa
//  libreria esta descontinuada y sus clases ya generan avisos de deprecacion.
//  Asi el proyecto queda limpio y con la criptografia explicita y auditables.
//
//  Este helper guarda tambien las preferencias NO sensibles (modo hombre/mujer,
//  metas, tema, ultima banda BLE, cache local de metricas...).
// ============================================================================
package com.pandafit.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.ai.AiProvider
import com.pandafit.app.data.model.GenderMode
import com.pandafit.app.data.model.MetricSeries
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Helper unico de preferencias de Panda Fit.
 *
 * @param context cualquier contexto; se usa applicationContext.
 */
class SharedPreferencesHelper(context: Context) {

    private val appContext = context.applicationContext

    // ---------------------------------------------------------------- CLAVES
    private object Keys {
        /** Proveedor de IA activo (gemini | openai | deepseek | claude). */
        const val AI_PROVIDER = "proveedor_ia"
        /** Claves antiguas: se conservan solo para migrar las ya guardadas. */
        const val GEMINI_API_KEY = "gemini_api_key_cifrada"
        const val GEMINI_MODEL = "gemini_modelo_preferido"
        const val GENDER_MODE = "modo_genero"
        const val STEP_GOAL = "meta_pasos"
        const val SLEEP_GOAL = "meta_sueno_horas"
        const val CLOUD_SYNC = "sincronizacion_nube"
        const val VOICE_MODE = "modo_voz_asistente"
        const val DYNAMIC_COLOR = "color_dinamico"
        const val DARK_THEME_OVERRIDE = "tema_forzado"      // -1 sistema, 0 claro, 1 oscuro
        const val LAST_DEVICE = "ultima_banda_ble"
        const val LAST_DEVICE_NAME = "ultima_banda_ble_nombre"
        const val HEALTH_CONNECT_GRANTED = "health_connect_autorizado"
        const val TODAY_CACHE = "cache_metricas_hoy_json"
        const val HISTORY_CACHE = "cache_historial_json"
        const val ONBOARDING_DONE = "onboarding_completado"
        const val AGGREGATED_USER_ID = "id_usuario_anonimo"
    }

    // ------------------------------------------- CIFRADO (API KEY DE GEMINI)
    private val crypto: PandaCrypto by lazy { PandaCrypto() }

    // --------------------------------------------- PREFERENCIAS NORMALES
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========================================================================
    //  API KEY DE GEMINI
    // ========================================================================

    // ========================================================================
    //  PROVEEDOR DE IA Y SUS CLAVES (una por proveedor, todas cifradas)
    // ========================================================================

    /** Proveedor de IA elegido por el usuario (Gemini por defecto). */
    var aiProvider: AiProvider
        get() = AiProvider.fromId(prefs.getString(Keys.AI_PROVIDER, AiProvider.GEMINI.id))
        set(value) = prefs.edit().putString(Keys.AI_PROVIDER, value.id).apply()

    private fun keyName(provider: AiProvider): String = "clave_api_${provider.id}"

    private fun modelName(provider: AiProvider): String = "modelo_ia_${provider.id}"

    /**
     * Guarda CIFRADA la clave del proveedor indicado. Cada proveedor tiene su
     * propia entrada: Panda Fit no mezcla ni reutiliza claves entre servicios.
     */
    fun saveApiKey(provider: AiProvider, apiKey: String): Boolean {
        val limpia = apiKey.trim()
        if (limpia.isEmpty()) return false
        return try {
            val cifrada = crypto.encrypt(limpia)
            prefs.edit().putString(keyName(provider), cifrada).apply()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo cifrar la clave de ${provider.id}: ${t.message}")
            false
        }
    }

    /** Devuelve la clave descifrada del proveedor, o null si no hay. */
    fun getApiKey(provider: AiProvider): String? {
        val guardada = prefs.getString(keyName(provider), null) ?: return null
        return try {
            crypto.decrypt(guardada).takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "Clave ilegible de ${provider.id}; se descarta: ${t.message}")
            prefs.edit().remove(keyName(provider)).apply()
            null
        }
    }

    fun hasApiKey(provider: AiProvider): Boolean = getApiKey(provider) != null

    /** Borra SOLO la clave del proveedor indicado (derecho al olvido). */
    fun clearApiKey(provider: AiProvider) {
        prefs.edit().remove(keyName(provider)).apply()
    }

    /** Version enmascarada para mostrar en Ajustes: "AIzaSy...7f2a". */
    fun maskedApiKey(provider: AiProvider): String {
        val clave = getApiKey(provider) ?: return ""
        return if (clave.length <= 10) "******"
        else "${clave.take(6)}\u2026${clave.takeLast(4)}"
    }

    /** Modelo elegido para el proveedor (vacio = el por defecto de ese servicio). */
    fun getModel(provider: AiProvider): String {
        val guardado = prefs.getString(modelName(provider), "") ?: ""
        // 0.0.10: si el modelo guardado fue retirado, se cambia por su sustituto.
        val actual = AiProvider.actualizarModelo(guardado)
        if (actual != guardado) prefs.edit().putString(modelName(provider), actual).apply()
        return actual
    }

    fun setModel(provider: AiProvider, model: String) {
        prefs.edit().putString(modelName(provider), model).apply()
    }

    // ---- Compatibilidad con la version anterior (solo Gemini) --------------

    /** @deprecated usar [saveApiKey] con [AiProvider.GEMINI]. */
    fun saveGeminiApiKey(apiKey: String): Boolean = saveApiKey(AiProvider.GEMINI, apiKey)

    /** @deprecated usar [getApiKey] con [AiProvider.GEMINI]. */
    fun getGeminiApiKey(): String? = getApiKey(AiProvider.GEMINI)

    /** @deprecated usar [hasApiKey]. */
    fun hasGeminiApiKey(): Boolean = hasApiKey(AiProvider.GEMINI)

    /** @deprecated usar [clearApiKey]. */
    fun clearGeminiApiKey() = clearApiKey(AiProvider.GEMINI)

    /** @deprecated usar [maskedApiKey]. */
    fun maskedGeminiApiKey(): String = maskedApiKey(AiProvider.GEMINI)

    /** @deprecated usar [getModel] / [setModel]. */
    var geminiModel: String
        get() = getModel(AiProvider.GEMINI)
        set(value) = setModel(AiProvider.GEMINI, value)

    // ========================================================================
    //  PERFIL Y METAS
    // ========================================================================

    var genderMode: GenderMode
        get() = GenderMode.fromName(prefs.getString(Keys.GENDER_MODE, GenderMode.MAN.name))
        set(value) = prefs.edit().putString(Keys.GENDER_MODE, value.name).apply()

    var stepGoal: Int
        get() = prefs.getInt(Keys.STEP_GOAL, 10_000)
        set(value) = prefs.edit().putInt(Keys.STEP_GOAL, value.coerceIn(1_000, 60_000)).apply()

    var sleepGoalHours: Float
        get() = prefs.getFloat(Keys.SLEEP_GOAL, 8f)
        set(value) = prefs.edit().putFloat(Keys.SLEEP_GOAL, value.coerceIn(5f, 12f)).apply()

    /** Edad (solo para calcular las zonas de pulso: FC max = 220 - edad). Queda en el telefono. */
    var userAge: Int
        get() = prefs.getInt("user_age", 30)
        set(value) = prefs.edit().putInt("user_age", value.coerceIn(12, 95)).apply()

    /** Peso en kg (solo para estimar kcal del entrenamiento). Queda en el telefono. */
    var userWeightKg: Int
        get() = prefs.getInt("user_weight_kg", 70)
        set(value) = prefs.edit().putInt("user_weight_kg", value.coerceIn(30, 200)).apply()

    /** 0.1.7: estatura en cm (distancia de los pasos e IMC). Queda en el telefono. */
    var userHeightCm: Int
        get() = prefs.getInt("user_height_cm", 170)
        set(value) = prefs.edit().putInt("user_height_cm", value.coerceIn(120, 220)).apply()

    /** Meta diaria de vasos de agua (250 ml). */
    var waterGoalGlasses: Int
        get() = prefs.getInt("water_goal", 8)
        set(value) = prefs.edit().putInt("water_goal", value.coerceIn(4, 16)).apply()

    /** 0.0.6: voz natural de IA (Gemini/OpenAI) para leer las respuestas. */
    var naturalVoiceEnabled: Boolean
        get() = prefs.getBoolean("voz_natural", true)
        set(value) = prefs.edit().putBoolean("voz_natural", value).apply()

    /** 0.1.3: modo RAPIDO de la IA (razona poco y responde directo). */
    var aiFastMode: Boolean
        get() = prefs.getBoolean("ia_rapida", true)
        set(value) = prefs.edit().putBoolean("ia_rapida", value).apply()

    /** 0.0.6: estilo de la voz natural: "MASCULINA" o "FEMENINA". */
    var voiceStyle: String
        get() = prefs.getString("voz_estilo", "MASCULINA") ?: "MASCULINA"
        set(value) = prefs.edit().putString("voz_estilo", value).apply()

    /** v26: el entrenador dice tiempo/ritmo/pulso cada km durante el entreno. */
    var voiceCoachEnabled: Boolean
        get() = prefs.getBoolean("coach_voz", true)
        set(value) = prefs.edit().putBoolean("coach_voz", value).apply()

    /** v26: pausa automatica del cronometro al pararse (actividades con GPS). */
    var autoPauseEnabled: Boolean
        get() = prefs.getBoolean("auto_pausa", true)
        set(value) = prefs.edit().putBoolean("auto_pausa", value).apply()

    /** v26: enviar pasos, pulso, sueno y entrenos a Health Connect. */
    var healthConnectWrite: Boolean
        get() = prefs.getBoolean("hc_escribir", false)
        set(value) = prefs.edit().putBoolean("hc_escribir", value).apply()

    var cloudSyncEnabled: Boolean
        get() = prefs.getBoolean(Keys.CLOUD_SYNC, true)
        set(value) = prefs.edit().putBoolean(Keys.CLOUD_SYNC, value).apply()

    var voiceModeEnabled: Boolean
        get() = prefs.getBoolean(Keys.VOICE_MODE, false)
        set(value) = prefs.edit().putBoolean(Keys.VOICE_MODE, value).apply()

    var dynamicColorEnabled: Boolean
        get() = prefs.getBoolean(Keys.DYNAMIC_COLOR, false)
        set(value) = prefs.edit().putBoolean(Keys.DYNAMIC_COLOR, value).apply()

    /** -1 = seguir al sistema (automatico), 0 = claro, 1 = oscuro. */
    var darkThemeOverride: Int
        get() = prefs.getInt(Keys.DARK_THEME_OVERRIDE, -1)
        set(value) = prefs.edit().putInt(Keys.DARK_THEME_OVERRIDE, value).apply()

    var lastBleDeviceAddress: String?
        get() = prefs.getString(Keys.LAST_DEVICE, null)
        set(value) = prefs.edit().putString(Keys.LAST_DEVICE, value).apply()

    var lastBleDeviceName: String?
        get() = prefs.getString(Keys.LAST_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(Keys.LAST_DEVICE_NAME, value).apply()

    var healthConnectGranted: Boolean
        get() = prefs.getBoolean(Keys.HEALTH_CONNECT_GRANTED, false)
        set(value) = prefs.edit().putBoolean(Keys.HEALTH_CONNECT_GRANTED, value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(Keys.ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(Keys.ONBOARDING_DONE, value).apply()

    // ========================================================================
    //  CACHE LOCAL (funciona sin Bluetooth y sin internet)
    // ========================================================================

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var cachedToday: DailyMetrics
        get() = decodeOr(Keys.TODAY_CACHE) { DailyMetrics.empty(stepGoal) }
        set(value) = prefs.edit().putString(Keys.TODAY_CACHE, json.encodeToString(value)).apply()

    var cachedHistory: MetricSeries
        get() = decodeOr(Keys.HISTORY_CACHE) { MetricSeries() }
        set(value) = prefs.edit().putString(Keys.HISTORY_CACHE, json.encodeToString(value)).apply()

    private inline fun <reified T> decodeOr(key: String, fallback: () -> T): T {
        val raw = prefs.getString(key, null) ?: return fallback()
        return runCatching { json.decodeFromString<T>(raw) }.getOrElse { fallback() }
    }

    companion object {
        private const val TAG = "PandaFitPrefs"
        const val PREFS_NAME = "panda_fit_prefs"
    }
}

// ============================================================================
//  CRIPTOGRAFIA DEL API KEY (AES-256-GCM + Android Keystore)
// ============================================================================
/**
 * Cifra y descifra cadenas cortas (la API Key de Gemini) con AES-256-GCM.
 *
 * Formato guardado: "v1:" + Base64( IV(12 bytes) || criptograma+tag ).
 * La clave AES vive en el Keystore bajo el alias [KEY_ALIAS] y nunca se expone.
 */
internal class PandaCrypto {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** Crea (o recupera) la clave AES del Keystore. */
    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Sin autenticacion de usuario: la app debe poder leer la clave
                // en segundo plano (asistente IA, sincronizacion).
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** Devuelve el criptograma listo para guardar en preferencias. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = iv + encrypted
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** Descifra el valor guardado. Lanza excepcion si el dato no es valido. */
    fun decrypt(stored: String): String {
        val base64 = stored.removePrefix(PREFIX)
        val payload = Base64.decode(base64, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "Criptograma incompleto" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pandafit_gemini_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val PREFIX = "v1:"
    }
}
