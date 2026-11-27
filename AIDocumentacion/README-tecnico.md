# 🐼 Panda Fit

App de fitness **Android 15 (API 35)** con **Kotlin + Jetpack Compose (Material 3)**, acabado
**Liquid Glass** (refracción, rim light y glow líquido), **BLE estable**, **GPS de alta precisión**,
**sincronización en la nube con Firebase Firestore**, **Health Connect** para datos reales de salud y
un **Asistente IA Fitness** con **Google Gemini** (chat, planes personalizados, sugerencias y modo voz).

> Estado: **compila y pasa las 68 pruebas unitarias** (v8) con `AGP 9.4.1 / Gradle 9.6.0 / JDK 25`
> (`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`).
> Además incluye un **verificador de privacidad** ejecutable:
> `powershell -ExecutionPolicy Bypass -File AIDocumentacion/check_privacidad.ps1` → **8/8 OK**.
>
> | Comprobación | Resultado |
> |---|---|
> | `:app:compileDebugKotlin` | OK — 0 errores, 0 avisos de deprecación |
> | `:app:testDebugUnitTest` | OK — **68 pruebas, 0 fallos** (BLE/K56, reensamblado de tramas, sueño, podómetro, panel Hoy, 4 proveedores de IA, motor y PII) |
> | `:app:assembleDebug` | OK — `app-debug.apk` (24,9 MB) |
> | `:app:assembleRelease` | OK — `app-release.apk` (4,21 MB, R8 + shrinkResources) |
> | `:app:lintDebug` | 23 avisos informativos (todos `UseKtx`), 0 errores |
> | `check_privacidad.ps1` | OK — 8/8 comprobaciones de privacidad y permisos |

---

## 1. Qué hace Panda Fit

### Panel principal (estilo DaFit / AgeFit: botones grandes, pantalla simple)
- Anillo gigante de **pasos** con progreso animado, distancia, calorías y minutos activos.
- **Sueño** (horas + calidad 0-100), **pulso** (medio, reposo, máximo) y **oxígeno en sangre (SpO2)**.
- Chip de estado en vivo: banda BLE, datos reales o demo, y nube (online/offline).
- Puntuación **Panda Fit 0-100** y 3 consejos inteligentes calculados **en el teléfono**.

### 🆕 Botón "Asistente IA Fitness" (nuevo, obligatorio)
- Chat en **tiempo real con streaming** (la respuesta se escribe palabra a palabra).
- **Genera planes de entrenamiento personalizados**: días/semana, ejercicios, series, repeticiones,
  minutos y motivo, en función de los **pasos, calorías, sueño, pulso y SpO2** del usuario.
- **Sugerencias diarias**: motivación, recordatorios y consejos ("hoy tu meta es 15.000 pasos porque
  ayer dormiste bien").
- **Resumen de progreso + recomendaciones** ("tu sueño está bajo, aquí tienes un plan de relajación").
- **Modo voz** con `TextToSpeech` (lee las respuestas en español).
- **Motor local de respaldo**: si no hay red o no hay API Key, responde igual con reglas expertas
  (`FitnessEngine`), sin enviar nada a internet.
- **Privacidad**: al modelo solo viajan **datos agregados y anónimos**. Hay un botón
  "Ver contexto" que muestra exactamente qué se envía.

### Conectividad (mejorada al máximo)
| Área | Implementación |
|---|---|
| BLE | **K56 Pro comprobado con reloj real** (`MOYOUNG-V2`): órdenes por `0xFEE2`, respuestas por `0xFEE3` (servicio `0xFEEA`); `0xFEE7`/`0xFEA1` es su podómetro WeChat. Detección de **familia** del reloj (DaFit/Moyoung, FEE7, genérico FFF0, estándar, desconocido): a las marcas no DaFit solo se las escucha. Escaneo agresivo (`LOW_LATENCY`) que también lista relojes ya emparejados; **cola GATT** que espera cada respuesta; reconexión con backoff exponencial y `autoConnect` desde el 3er intento; **vigilante** que hace ping (batería) tras 30 s de silencio antes de reconectar; MTU 517; mediciones solo bajo demanda (botones); parsers **estándar** (0x180D, 0x1822, 0x180F) y **propietarios** (pasos, sueño por fases, presión, SpO2). |
| GPS | `FusedLocationProviderClient` con `PRIORITY_HIGH_ACCURACY`, 1-2 s y 1,5 m de distancia mínima; trayecto con distancia **haversine**. |
| Segundo plano | `PandaFitBackgroundService` (foreground service `location|connectedDevice`) que sigue midiendo con la pantalla apagada. |
| Nube | **Firebase Firestore** con caché offline persistente: si se pierde el Bluetooth **o** la red, nada se pierde; al volver la conexión se sube solo. Además hay una **cola local propia** (`PendingSyncStore`) que encola en disco entrenamientos, planes y resumen de chat antes de intentar subirlos, y los reintenta al recuperar la red o al reiniciar el teléfono. |
| Auth | Sesión **anónima** de Firebase (sin correo, sin teléfono, sin identificadores personales). |
| Health Connect | Pasos, distancia, calorías totales/activas, pulso, SpO2 y sueño; además **escribe** de vuelta lo medido por la banda. |

### Datos y personalización
- **Modo hombre / mujer**: cambia zancada, gasto calórico y metas.
- **Datos simulados (demo)** deterministas por día, o **datos reales** vía Health Connect.
- Metas de pasos y sueño ajustables; tema **automático claro/oscuro** + color dinámico (Android 12+).
- **Interfaz 100% localizada**: todos los textos viven en `res/values/strings.xml` (español, idioma por
  defecto) con traducción completa en `res/values-en/strings.xml` (inglés) y plurales reales
  (`<plurals>`). Añadir otro idioma es crear `res/values-<código>/strings.xml`.

### Seguridad del Asistente IA
- El usuario introduce **su propia API Key de Google Gemini**.
- Se guarda **cifrada con AES-256-GCM**; la clave de cifrado vive en el **Android Keystore** y nunca
  sale del dispositivo. La app **no almacena claves de otros proveedores**.
- La clave se muestra **enmascarada** (`AIzaSy…7f2a`), no se registra en logs y el archivo de
  preferencias está **excluido de las copias de seguridad** (`panda_backup_rules.xml` y
  `panda_data_extraction_rules.xml`), así que el criptograma no se respalda en Drive ni en la
  transferencia entre dispositivos.
- **Defensa en profundidad contra PII**: además de que el contexto automático solo lleva cifras
  agregadas, todo mensaje libre del usuario pasa por `PromptBuilder.PiiScrubber`, que **enmascara
  correos, teléfonos, MAC, documentos y enlaces** (`[EMAIL]`, `[TELEFONO]`, `[MAC]`…) antes de salir
  del teléfono. El sistema también instruye al modelo a no repetir datos personales.
- El usuario es responsable de su cuenta de Gemini (Google gestiona el servicio).

---

## 2. Estructura del proyecto

```
PandaFit/
├── settings.gradle.kts              # Repositorios + módulo :app
├── build.gradle.kts                 # Plugins (AGP 9 no necesita kotlin-android)
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml           # Catálogo de versiones (octubre 2026)
│   └── wrapper/                      # Gradle 9.6.0
├── AIDocumentacion/
│   ├── INSTALACION.md               # Cómo abrir/compilar en Android Studio
│   ├── PRUEBAS.md                   # Cómo probar BLE, Liquid Glass y el Asistente IA
│   ├── COMPATIBILIDAD.md            # Qué relojes funcionan (K56 Pro, Moyoung, estándar)
│   └── check_privacidad.ps1         # Verificador automático de privacidad y permisos
└── app/
    ├── build.gradle.kts             # compileSdk 36 / targetSdk 35 / minSdk 26
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/pandafit/app/
        │   │   ├── PandaFitApplication.kt        # Application: preferencias, BLE, nube, Gemini
        │   │   ├── MainActivity.kt               # Edge-to-edge + permisos + tema reactivo
        │   │   ├── ai/
        │   │   │   ├── AIAssistantScreen.kt      # 🆕 Chat + planes + voz + API Key
        │   │   │   ├── AIViewModel.kt            # 🆕 Estado del chat, streaming, TTS
        │   │   │   ├── GeminiAIClient.kt         # 🆕 Cliente unificado de Gemini (REST v1beta)
        │   │   │   ├── FitnessEngine.kt          # Motor local + parser de planes
        │   │   │   └── PromptBuilder.kt          # Prompts + contexto anónimo
        │   │   ├── ble/
        │   │   │   ├── BleManager.kt             # Escaneo, cola GATT, familia, reconexión, vigilante
        │   │   │   ├── BleProtocol.kt            # UUIDs y parsers de tramas
        │   │   │   └── PandaFitBackgroundService.kt  # BLE + GPS + nube en 2º plano
        │   │   ├── data/
        │   │   │   ├── SharedPreferencesHelper.kt # 🆕 API Key cifrada + ajustes
        │   │   │   ├── PandaFitRepository.kt      # Firestore + Auth anónimo
        │   │   │   ├── MetricsSimulator.kt        # Datos demo realistas
        │   │   │   ├── SyncAlarmReceiver.kt       # Arranque + conectividad
        │   │   │   └── model/PandaFitModels.kt    # Modelos serializables
        │   │   ├── health/
        │   │   │   ├── HealthConnectHelper.kt     # Permisos, lectura y escritura
        │   │   │   └── PandaFitHealthConnectService.kt
        │   │   ├── ui/
        │   │   │   ├── theme/
        │   │   │   │   ├── PandaFitTheme.kt       # Material 3 + tokens Liquid Glass
        │   │   │   │   ├── LiquidGlassModifier.kt  # Motor de cristal (refraction/rim/glow)
        │   │   │   │   ├── PandaShapes.kt
        │   │   │   │   └── ThemeController.kt
        │   │   │   ├── components/PandaGlassComponents.kt
        │   │   │   ├── navigation/PandaFitNavigation.kt
        │   │   │   └── screens/
        │   │   │       ├── SplashScreen.kt
        │   │   │       ├── HomeScreen.kt
        │   │   │       ├── TrainingScreen.kt
        │   │   │       ├── HealthScreen.kt
        │   │   │       └── ProfileScreen.kt
        │   │   └── viewmodel/FirebaseHealthViewModel.kt  # Fuente única de verdad
        │   └── res/                        # strings (es + en), colors, themes por API, iconos vectoriales
        └── test/java/com/pandafit/app/
            └── PandaFitLogicTest.kt        # 18 pruebas: BLE, parser, motor, simulador
```

---

## 2.b Notas de la version 1.0.0 (APK entregada)

- **Cristal mas solido y nitido**: el motor `liquidGlass` ya NO aplica `RenderEffect` a la capa
  de la tarjeta (difuminaba el texto: toda la interfaz se veia borrosa). Ademas el vidrio es ahora
  **casi opaco** (alpha 0,72-0,88 en vez de 0,42): se sigue viendo el aura de fondo pero el texto
  tiene contraste de sobra. El brillo se consigue con rim light, especular y glow dibujados fuera
  del contenido.
- **Textos que ya no se cortan**: los botones grandes van **apilados a ancho completo** (antes
  "Entrenar" y "Sincronizar" se cortaban como "Entr"/"Sinc") y la barra de navegacion reparte el
  ancho a partes iguales con etiqueta de 11 sp en una sola linea (antes "Asistente IA" se solapaba
  con "Entrenar"). El destino de IA se abrevia a "IA" en la barra.
- **Multi-proveedor de IA**: ademas de Gemini ahora se puede usar **OpenAI (ChatGPT), DeepSeek y
  Anthropic Claude**. Cada proveedor tiene su propia clave (cifrada por separado) y su propio
  modelo, con selector en Ajustes. Si el proveedor falla, responde el motor local.
- **BLE del K56 Pro mas fiable**: REENSAMBLADO DE TRAMAS (el reloj parte sus respuestas en varios paquetes BLE: antes se descartaban), operaciones GATT serializadas con mutex (Android solo admite una a la vez), fallback a `autoConnect` a partir del tercer intento, peticion de MTU con plan B por si el firmware no responde, prioridad de conexion alta, watchdog que ya no se acumula entre conexiones, y **diagnostico en pantalla** (servicios + registro BLE) sin necesidad de adb: timeout de conexion de 12 s con reintento automatico (antes se
  quedaba en "Conectando..." para siempre si el reloj ya estaba emparejado con DaFit), boton
  **"Sincronizar banda"** que pide de nuevo pasos, pulso y sueno, y volcado de servicios para
  diagnosticar.
  _(Actualizado en v2-v8: el mutex se sustituyo por una cola GATT que espera la respuesta de
  cada operacion, el timeout paso a 15 s por intento, el watchdog ahora hace ping antes de
  cortar, y "Sincronizar banda" ya no lanza mediciones. Ver
  [COMPATIBILIDAD.md](AIDocumentacion/COMPATIBILIDAD.md).)_
- **Datos reales + generados juntos**: los datos de Health Connect y del reloj **ya no se
  sustituyen**: los reales mandan y lo generado solo rellena los campos que siguen a cero
  (origen "mixto"). Antes se perdia el dato real al refrescar.
- **Sin etiquetas de "demo"** (app de uso personal) y **icono nuevo** del panda con circuito,
  generado en todas las densidades con `herramientas/generar_iconos.py`.

## 3. Archivos destacados

| Archivo | Por qué importa |
|---|---|
| `ui/theme/LiquidGlassModifier.kt` | El motor visual: `Modifier.liquidGlass()` y `Modifier.liquidAura()`. Vidrio casi opaco con velo, rim light, especular, banda de lente y halo que respira; el contenido se dibuja al final, siempre nitido. |
| `ai/AiClientFactory.kt` | **Un cliente por proveedor** (Gemini, OpenAI, DeepSeek, Claude) sobre una interfaz comun, con streaming SSE y autodeteccion de modelo. |
| `ai/AiProvider.kt` | Catalogo de proveedores, contrato `AiChatClient` y utilidades JSON/HTTP compartidas. |
| `ai/AIViewModel.kt` | Chat con streaming, cambio de proveedor, parser de planes, modo voz y **fallback** al motor local. |
| `ble/BleProtocol.kt` | UUIDs y protocolo DaFit/Moyoung (`0xFEEA`/`0xFEE2`/`0xFEE3`, el del K56 Pro real) y del podómetro `0xFEE7`/`0xFEA1`; tramas `0xFE 0xEA`, parsers validados (sueño por fases, presión, SpO2, pasos) y detección de familia del reloj. |
| `ble/BleManager.kt` | Cola GATT que espera cada respuesta, timeout de 15 s por intento, reconexion con backoff y `autoConnect`, vigilante con ping de bateria, mediciones bajo demanda y diagnostico copiable. |
| `data/SharedPreferencesHelper.kt` | Cifrado AES-256-GCM + Keystore: **una clave por proveedor de IA**, aisladas entre si. |
| `data/PendingSyncStore.kt` | Cola local en disco: entrenamientos, planes y chat se reintentan si falla la red. |
| `viewmodel/FirebaseHealthViewModel.kt` | Mezcla de fuentes (Health Connect + BLE + generado) en un unico estado. |
| `AIDocumentacion/check_privacidad.ps1` | Verificación automática de las políticas de privacidad (logs, cifrado, backups, PII, permisos). |
| `herramientas/generar_iconos.py` | Regenera el icono de la app (todas las densidades) desde una imagen PNG. |

---

## 4. Empezar

1. Abre la carpeta `PandaFit` en **Android Studio** (Gradle 9.6, JDK 25 del JBR).
2. Pulsa **Sync** (el `local.properties` ya apunta al SDK de este equipo).
3. Ejecuta en un dispositivo **Android 8.0+** (recomendado un móvil físico con BLE y GPS reales).

Instrucciones completas (incluido Firebase y Gemini): **[AIDocumentacion/INSTALACION.md](AIDocumentacion/INSTALACION.md)**
Pruebas de BLE, Liquid Glass y Asistente IA: **[AIDocumentacion/PRUEBAS.md](AIDocumentacion/PRUEBAS.md)**

---

## 5. Aviso importante

Panda Fit es una app de bienestar, **no un dispositivo médico**. No diagnostica ni trata enfermedades.
Si el pulso en reposo, el SpO2 o el sueño muestran valores anómalos de forma repetida, consulta a un
profesional de la salud. El asistente IA incluye esta limitación en sus instrucciones de sistema.
