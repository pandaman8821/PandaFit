# 🐼 Panda Fit — Instalación y puesta en marcha

Guía exacta para abrir, compilar e instalar **Panda Fit** en Android Studio (con el entorno ya
integrado) y activar Firebase, Health Connect y el Asistente IA.

---

## 0. Requisitos

| Elemento | Versión usada y verificada |
|---|---|
| Android Studio | Versión instalada (JBR **OpenJDK 25.0.3**) |
| Android Gradle Plugin | **9.4.1** (incluye soporte de Kotlin: **no** se aplica `kotlin-android`) |
| Gradle | **9.6.0** (wrapper ya incluido) |
| Kotlin | **2.3.0** (plugins `kotlin-compose` y `kotlin-serialization`) |
| compileSdk | **36** (lo exigen Health Connect 1.1 y AndroidX Activity 1.11) |
| targetSdk | **35 = Android 15** ← requisito del proyecto |
| minSdk | **26** (Android 8.0) |
| JDK de compilación | 17 (source/target) ejecutándose sobre el JBR 25 |

El `local.properties` incluido ya apunta al SDK de este equipo:

```properties
sdk.dir=C\:\\Users\\<tu-usuario>\\AppData\\Local\\Android\\Sdk
```

> `compileSdk` es 36 porque las librerías actuales de Health Connect/AndroidX lo exigen **para
> compilar**. El **comportamiento en ejecución** lo fija `targetSdk = 35`, así que la app es una app
> de **Android 15** en todos los dispositivos.

---

## 1. Abrir el proyecto en Android Studio

1. **File → Open** y selecciona la carpeta `PandaFit` (la que contiene `settings.gradle.kts`).
2. Espera al **Gradle Sync**. La primera vez descarga dependencias (~3-5 minutos con buena red).
3. Comprueba en **Settings → Build, Execution, Deployment → Build Tools → Gradle**:
   - *Gradle JDK*: **jbr-25** (o *Embedded JDK*).
4. Si aparece el aviso *"Google Services plugin requires google-services.json"*: es normal, Firebase es
   **opcional** (ver paso 2). El plugin solo se activa si el archivo existe, así que **el proyecto
   compila sin él**.

### Compilar desde la terminal (lo que se verificó)

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\<tu-usuario>\AppData\Local\Android\Sdk'
cd C:\ruta\a\PandaFit

.\gradlew.bat :app:testDebugUnitTest    # 18 pruebas unitarias
.\gradlew.bat :app:assembleDebug        # APK de depuración
.\gradlew.bat :app:assembleRelease      # APK de release (R8 + recurso reducido)
```

APK generado: `app/build/outputs/apk/debug/app-debug.apk`

---

## 2. Firebase (nube: Firestore + Auth anónimo)

Panda Fit compila y funciona **sin** Firebase (modo local + datos simulados). Para activar la
sincronización en la nube:

1. Entra en <https://console.firebase.google.com> → **Agregar proyecto** → nombre `PandaFit`.
2. Dentro del proyecto: **Agregar app → Android**.
   - **Nombre del paquete**: `com.pandafit.app`
     (para el APK de debug el paquete real es `com.pandafit.app.debug`, así que añade **también** esa
     app Android si vas a probar en debug; es lo más cómodo).
   - Registro de la app: opcional.
3. Descarga **`google-services.json`** y colócalo en:

   ```
   PandaFit/app/google-services.json
   ```

4. Vuelve a **Sync**. Verás que ahora sí se aplica el plugin `com.google.gms.google-services`
   (la detección es automática: `PandaFit/build.gradle.kts` marca `pandaFirebaseConfigured`).
5. **Authentication → Comenzar → Anónimo → Habilitar** (Panda Fit crea una sesión anónima: sin correo
   ni datos personales).
6. **Firestore Database → Crear base de datos** (modo producción o prueba).
7. Reglas recomendadas para tus pruebas (solo permiten al usuario leer/escribir **su propia** rama,
   identificada por un id anónimo local):

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /usuarios/{userId}/{document=**} {
         allow read, write: if request.auth != null;
       }
     }
   }
   ```

8. Estructura de datos creada por la app (todo agregado, sin datos personales):

   ```
   usuarios/{id_anonimo}/dias/{yyyy-MM-dd}          → pasos, distancia, kcal, sueño, pulso, SpO2
   usuarios/{id_anonimo}/entrenamientos/{uuid}      → sesión: duración, km, kcal, nº de puntos GPS
   usuarios/{id_anónimo}/planes/{uuid}              → plan generado por el Asistente IA
   usuarios/{id_anonimo}                            → chat.mensajes, chat.ultimaIntencion
   ```

> La **caché offline persistente** de Firestore está activada en `PandaFitRepository`: si se cae el
> Bluetooth o la red, los datos se guardan en el teléfono y se suben al recuperar la conexión.

---

## 3. Proveedores de IA (Asistente IA Fitness)

Panda Fit soporta **cuatro proveedores**; el usuario elige el suyo y aporta SU clave:

| Proveedor | Clave en | Prefijo | Modelo por defecto |
|---|---|---|---|
| Google Gemini | https://aistudio.google.com/apikey | `AIza` | `gemini-2.5-flash` |
| OpenAI (ChatGPT) | https://platform.openai.com/api-keys | `sk-` | `gpt-4o-mini` |
| DeepSeek | https://platform.deepseek.com/api_keys | `sk-` | `deepseek-chat` |
| Anthropic Claude | https://console.anthropic.com/settings/keys | `sk-ant-` | `claude-sonnet-4-5` |

Cada proveedor guarda **su propia clave cifrada y su propio modelo**: cambiar de proveedor no
comparte ni mezcla credenciales. En el panel de Ajustes del asistente se elige con un toque y se
pega la clave; **Probar** verifica contra el servicio y autodetecta un modelo valido (si tu modelo
ya no existe, se cambia solo). Si un proveedor falla, responde el motor local.

### Detalles de Gemini

1. Entra en **Google AI Studio**: <https://aistudio.google.com/apikey> → **Create API key**.
2. En Panda Fit, abre **Asistente IA Fitness** (botón grande del inicio) → engranaje ⚙️ de la cabecera
   (o el panel que aparece solo si no hay clave).
3. Pega la clave y pulsa **Guardar cifrada**. Después pulsa **Probar con Gemini**:
   - verifica la clave contra Google,
   - detecta automáticamente el mejor modelo disponible y lo muestra ("Modelo activo: …").
4. La clave queda **cifrada con AES-256-GCM** (clave maestra en el Android Keystore) y se muestra
   enmascarada. Para borrarla: **Perfil → Borrar clave**.

### Cómo funciona el cliente (`GeminiAIClient.kt`)

- Endpoint REST oficial: `https://generativelanguage.googleapis.com/v1beta`.
- `GET /models` → **autodetección de modelo** (elige el mejor *flash*/​*pro* que admita `generateContent`).
  Así la app **no se queda obsoleta** cuando Google retira un modelo.
- Chat normal: `POST /models/{model}:generateContent`.
- Chat con streaming: `POST /models/{model}:streamGenerateContent?alt=sse` (eventos SSE).
- Modelo de respaldo si `ListModels` falla: `gemini-2.5-flash` (constante `FALLBACK_MODEL`).
- Puedes fijar un modelo concreto de dos formas: (a) desde Ajustes del asistente, (b) editando
  `prefs.geminiModel`.

### Privacidad (lo que sale del teléfono)

`PromptBuilder.contextBlock()` construye el único texto de contexto que se envía, con:

```
Fecha (agregada) · Pasos · Distancia · Calorías activas · Minutos activos ·
Sueño (h + calidad) · Pulso (reposo/medio/máx) · SpO2 · Estrés · Origen de los datos
```

**Nunca** se envían: nombre, correo, teléfono, dirección, ubicación exacta, MAC del dispositivo,
identificadores de Firebase ni el historial de chat completo (solo los últimos 10 turnos de texto).

Dos barreras adicionales:

1. **`PromptBuilder.PiiScrubber`**: todo mensaje libre del usuario se enmascara antes de salir
   (`[EMAIL]`, `[TELEFONO]`, `[MAC]`, `[DOCUMENTO]`, `[ENLACE]`, `[NUMERO_LARGO]`). Se aplica en
   `PromptBuilder.freeChatPrompt()` y otra vez en `AIViewModel.launchChat()`.
2. **Instrucciones de sistema**: el modelo tiene prohibido pedir o repetir datos personales.

El usuario puede auditar el envío con el botón **"Ver contexto"** del chat. Hay 3 pruebas unitarias
dedicadas a esto (`PandaFitLogicTest`: *"el scrubber enmascara…"*, *"el prompt libre enviado a Gemini
no contiene datos personales"*, *"el resumen anonimo no contiene datos personales"*).

### Copias de seguridad

`panda_backup_rules.xml` y `panda_data_extraction_rules.xml` **excluyen `panda_fit_prefs.xml`**, que es
donde vive el criptograma de la API Key. No se respalda ni en Google Drive ni en device-transfer: es una
credencial privada y el modelo de amenaza correcto es volver a pegarla en otro teléfono. Las métricas de
salud sí se recuperan en cualquier dispositivo porque viven en tu cuenta anónima de Firestore.

---

## 4. Health Connect (datos reales)

- **Android 14+**: Health Connect está integrado en el sistema.
- **Android 9-13**: instala/actualiza *Health Connect* desde Play Store.
- En Panda Fit: **Salud → Conectar Health Connect** o la tarjeta del inicio → concede los permisos.

Permisos de lectura declarados: pasos, distancia, calorías totales/activas, pulso, SpO2 y sueño.
Permisos de escritura: pasos, pulso y SpO2 (para devolver a Health Connect lo medido por la banda).
Si no concedes permisos, Panda Fit sigue funcionando con **datos simulados** (y lo indica en pantalla).

---

## 5. Permisos que pide la app y para qué

| Permiso | Uso |
|---|---|
| `POST_NOTIFICATIONS` | Notificación del servicio en segundo plano (opcional pero recomendable). |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+) | Escanear y conectar la banda. `neverForLocation`. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Escaneo BLE en Android ≤ 11 **y** GPS del entrenamiento. |
| `ACCESS_BACKGROUND_LOCATION` | Grabar el trayecto con la pantalla apagada. |
| `ACTIVITY_RECOGNITION` | Detección de actividad/pasos. |
| `BODY_SENSORS` | Pulso del sensor del teléfono. |
| `FOREGROUND_SERVICE` + `..._LOCATION` / `..._CONNECTED_DEVICE` | Servicio en segundo plano (banda + GPS + nube). |
| `android.permission.health.*` | Lectura/escritura en Health Connect. |

Todos se piden **de forma gradual**: denegar cualquiera degrada la experiencia, no rompe la app.

---

## 6. Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| `File google-services.json is missing` | El plugin se aplicó sin el archivo. Este proyecto lo aplica **solo si existe**; asegúrate de no haber puesto un archivo vacío. Si no quieres Firebase, borra `app/google-services.json`. |
| `Unresolved reference: buildConfig` | Debe estar `buildFeatures { buildConfig = true }` (ya está). |
| El asistente responde "motor local" | No hay API Key guardada o no hay red. Es el comportamiento previsto; añade la clave y pulsa *Probar con Gemini*. |
| "Tu API Key de Gemini no es valida" | La clave se revocó o no es de AI Studio. Genera otra en <https://aistudio.google.com/apikey>. |
| No aparece Health Connect | Android < 14 sin la app instalada. Instálala desde Play Store o usa datos simulados. |
| El cristal se ve "plano" | Tu dispositivo es API < 31: no hay `RenderEffect`, así que el efecto se degrada a translúcido (documentado en `LiquidGlassModifier.kt`). |
| Battería: quieres menos consumo | Perfil → desactiva *Sincronización en la nube*; el escaneo BLE se corta solo a los 20 s y pasa a modo equilibrado con la banda recordada. |

---

## 7. Notas técnicas de diseño

- **Un solo objeto de estado** (`PandaUiState`): los 8 flujos (día, historial, vitales, estado BLE,
  dispositivos, Health Connect, conectividad y género) se combinan en uno; la UI observa un solo
  `StateFlow` y recomppone menos.
- **Una sola conexión GATT** por app (`BleManager` vive en la `Application`), sobrevive a rotaciones.
- **Nada se pierde**: local primero (SharedPreferences) → Firestore después (con cola offline).
- **Cero dependencias descontinuadas**: el cifrado se hace con `Cipher`/`KeyStore` directamente en vez
  de `androidx.security-crypto` (descontinuada), y no se usa ningún SDK de IA que cambie de nombre
  cada pocos meses.
- **Seguridad por diseño**: los prompts no contienen datos personales y el sistema instruye al modelo
  a no pedirlos; además Panda Fit no es un dispositivo médico y el asistente lo declara.
