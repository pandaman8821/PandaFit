# 🐼 Panda Fit — Guía de pruebas

Cómo probar, paso a paso, las tres piezas que más importan: **conexión BLE**, **efecto Liquid Glass**
y **Asistente IA (Gemini + voz)**. Incluye resultados esperados y qué hacer si algo falla.

---

## 0. Pruebas automáticas (empieza por aquí)

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\<tu-usuario>\AppData\Local\Android\Sdk'
cd C:\ruta\a\PandaFit

.\gradlew.bat :app:testDebugUnitTest     # 68 pruebas: BLE/K56 Pro (reensamblado, familias, sueño), podómetro, panel Hoy, 4 proveedores de IA, parser, motor y PII
.\gradlew.bat :app:assembleDebug         # APK debug
.\gradlew.bat :app:assembleRelease       # APK release (R8)
.\gradlew.bat :app:lintDebug             # analisis estatico (informe HTML)

# Verificador de privacidad y permisos (8 comprobaciones, sale con codigo 1 si falla)
powershell -ExecutionPolicy Bypass -File AIDocumentacion\check_privacidad.ps1
```

Informe de pruebas: `app/build/reports/tests/testDebugUnitTest/index.html`
Informe de lint: `app/build/reports/lint-results-debug.html`

Qué cubren las 21 pruebas (`app/src/test/java/com/pandafit/app/PandaFitLogicTest.kt`):

| Bloque | Pruebas |
|---|---|
| Protocolo BLE | pulso 8/16 bits, tramas incompletas → `null`, SpO2 SFLOAT, batería acotada, pasos little-endian, comando Moyoung |
| Parser de planes | conversión de texto Gemini → `TrainingPlan`, series/reps/minutos/segundos, texto sin plan → `null` |
| Motor local | día malo genera avisos, día perfecto ≥ 85/100, plan adaptado a sueño bajo, **el resumen anónimo no contiene datos personales** |
| Simulador | determinismo por día, progreso horario coherente, rangos fisiológicos válidos |
| **Privacidad (PII)** | el scrubber enmascara correos/teléfonos/MAC/documentos, el prompt libre no lleva datos personales, el texto limpio no se altera |
| **Multi-proveedor IA** (`AiProvidersTest`) | los 4 proveedores estan en el catalogo, cada uno con modelo y consola; formato de peticion correcto para OpenAI/DeepSeek (`messages` con `system`) y Claude (`system` aparte); parsers de respuesta y de streaming de cada API |

### Verificador de privacidad (`check_privacidad.ps1`)

Comprueba automáticamente las 5 políticas del proyecto y responde OK/FALLO:

1. Ninguna llamada a `Log.*` imprime la API Key.
2. La API Key solo se guarda a través del cifrado (`crypto.encrypt`) con AES-GCM + Android Keystore.
3. Los dos XML de backup excluyen `panda_fit_prefs.xml` (donde vive el criptograma).
4. El texto libre del usuario pasa por `PiiScrubber` en `PromptBuilder` **y** en `AIViewModel`.
5. Todos los permisos esperados (BLE, ubicación, actividad, Health Connect, foreground, arranque) están declarados en el manifiesto.

Resultado esperado: `RESULTADO: OK - 8 comprobaciones superadas` con `exit 0`.

---

## 1. Instalar en un dispositivo

```powershell
# Con el móvil conectado por USB y depuración USB activada:
.\gradlew.bat :app:installDebug
```

O desde Android Studio: selecciona el dispositivo y pulsa **Run ▶**.

**Recomendado**: móvil físico con BLE y GPS reales (Android 12+ para el efecto de cristal completo).

---

## 2. Probar la conexión BLE

### 2.1 Con una banda/reloj real (K56 Pro incluido)

> El **K56 Pro Smart Watch está comprobado con un reloj real** (`MOYOUNG-V2`): las órdenes van
> por `0xFEE2` (servicio `0xFEEA`) y las respuestas por `0xFEE3`; el servicio `0xFEE7` es solo
> su podómetro WeChat (`0xFEC9` es de solo lectura). Detalles y advertencia sobre el comando de
> hora en **[COMPATIBILIDAD.md](COMPATIBILIDAD.md)**.

1. Empareja la banda en **Ajustes de Android → Bluetooth** (según el modelo puede no ser necesario).
2. Abre Panda Fit → **Inicio** → tarjeta **"Sin banda conectada"** → **Conectar**.
3. Acepta los permisos de *Dispositivos cercanos* (Android 12+) o *Ubicación* (Android ≤ 11).
4. La app escanea en **modo agresivo** (20 s) y lista las bandas ordenadas por señal (`4/4`, `3/4`…).
5. Toca una → conecta. Deberías ver:
   - Estado **"Conectada"** + nombre del dispositivo.
   - Suscripción a canales: en la notificación del servicio aparece `Entrenando • N pasos • NN ppm • SpO2 NN%`.
   - Chip de batería si la banda publica `0x180F`.

Resultado esperado por la consola (Logcat, filtro `PandaFitBle`):

```
Encontrado: MiBanda (rssi -54 dBm)
Conectando a AA:BB:CC:DD:EE:FF ...
Conectado: MiBanda
Suscrito a N canal(es) de datos
Dispositivo: MiBanda
```

### 2.2 Sin hardware (simulador BLE)

Usa la app **nRF Connect for Mobile** (Nordic Semiconductor) como *peripheral*:

1. En nRF Connect: **Configure GATT server** → añade el servicio `0x180D` (Heart Rate) con la
   característica `0x2A37` marcada como **Notify**, y opcionalmente `0x180F`/`0x2A19` (batería, Read).
2. **Advertise** ese servidor con un nombre que contenga, por ejemplo, `PandaTest`.
3. En Panda Fit pulsa **Conectar** y elige `PandaTest`.
4. En nRF Connect modifica el valor de `0x2A37` a `00 4E` → Panda Fit debe mostrar **78 ppm** al
   instante (0x4E = 78). Prueba `01 22 01` → 290 ppm (formato de 16 bits).

### 2.2.b Diagnostico EN PANTALLA (nuevo, sin adb)

En la tarjeta de conexion tienes un boton **"Diagnostico BLE"** que muestra, en la propia app:

- estado de la conexion y si el Bluetooth esta encendido,
- si los permisos BLE estan concedidos,
- el dispositivo objetivo y los dispositivos vistos con su senal,
- la **familia del reloj** detectada y el fabricante/modelo/firmware,
- los **servicios y caracteristicas** del reloj conectado (con su nombre corto: `MOYOUNG_WRITE(FEE2)`...).

El informe se puede **Copiar** o **Compartir** (WhatsApp, correo...) e incluye los ultimos 30
mensajes de la banda ("Conectando...", "Reloj detectado: ...", "Canal de comandos: FEE2 (DaFit)",
"Respuesta del reloj: cmd 0x6D", "Reintento 2 en 2s [directo]"...).
Con eso ya puedes ver QUE esta pasando sin conectar el telefono al ordenador.

### 2.3 Reconexion automatica (lo que hace "estable" al BLE)

1. Con la banda conectada, **apaga el Bluetooth** del móvil y vuelve a encenderlo.
2. Panda Fit muestra **"Reintento 1 en 1s"**, luego 2 s, 4 s, 8 s… (backoff exponencial, máx. 30 s).
   Desde el tercer intento usa `autoConnect`: Android espera al reloj sin límite de tiempo.
3. Al volver la banda, se reconecta **sola**. Al reconectar solo **lee** datos (pasos, sueño,
   historial): ya no pone al reloj a medir pulso ni oxígeno por su cuenta.
4. Tiempo de espera de conexión: **15 s** (solo en intentos directos); un tiempo de espera de un
   intento anterior ya no puede cortar el intento nuevo.
5. Vigilante del enlace: un reloj en reposo puede pasar minutos sin enviar nada y el enlace sigue
   sano. Tras **30 s de silencio** la app hace un "ping" (lee la batería o el nombre) y solo
   reconecta si el ping falla dos veces **y** Android confirma que no hay enlace
   (`El reloj no responde: reconectando`). Antes cortaba a los 25 s de silencio y entraba en un
   bucle conectar/desconectar.
6. Todas las operaciones GATT pasan por una **cola** que espera la respuesta de cada una
   (MTU, servicios, suscripciones, órdenes): Android solo admite una a la vez.

### 2.4 Sin datos que perder (nube)

1. Con la banda conectada y **Perfil → Sincronización en la nube** activa, deja correr 1 minuto.
2. Pon el móvil en **modo avión** (corta red y Bluetooth).
3. Abre el perfil de Firebase → Firestore: verás los documentos de `usuarios/{id}/dias/{fecha}`.
   Los que se hayan generado sin red aparecen al volver la conexión (cola offline de Firestore).
4. Quita el modo avión: el chip del inicio pasa de **"Sin red"** a **"Nube lista"**.

---

## 3. Probar Liquid Glass (refracción, rim light y glow)

Todo el motor está en `app/src/main/java/com/pandafit/app/ui/theme/LiquidGlassModifier.kt`.
Se prueba a simple vista en tres pantallas:

| Qué mirar | Dónde | Qué debe verse |
|---|---|---|
| **Refracción real** | Tarjeta del anillo de pasos (Inicio) | El contenido detrás del cristal aparece **desenfocado** (`RenderEffect`) con una banda curva de lente en la parte superior. |
| **Rim light** | Bordes de cualquier tarjeta | Borde blanco luminoso arriba-izquierda y rebote azul frío abajo-derecha. |
| **Glow líquido que respira** | Botón "Asistente IA Fitness" | Halo violeta que late lentamente (~4,2 s por ciclo) y **se intensifica al pulsarlo**. |
| **Transición fluida** | Navegación entre pestañas | Deslizamiento + fundido; el cristal se mantiene nítido. |
| **Aura de fondo** | Cualquier pantalla | Tres manchas de color (azul, menta, violeta) detrás del cristal, que es lo que refracta. |
| **Modo claro/oscuro automático** | Ajustes del sistema | Cambia el tema solo; en **Perfil → Apariencia** puedes forzar Auto/Claro/Oscuro. |

### Cómo saber si estás viendo la refracción "de verdad"

- **Android 12+ (API 31+)**: `RenderEffect.createBlurEffect` activo → el fondo se ve difuminado dentro
  del panel.
- **Android 8-11 (API 26-30)**: sin `RenderEffect`; se ve un vidrio translúcido limpio (glassmorphism).
  Es el comportamiento previsto, no un fallo.

### Prueba de esfuerzo del motor visual

1. Abre Inicio y **desplaza rápido** la lista: no debe haber tirones (todas las sombras/gradientes se
   construyen una vez con `drawWithCache`).
2. Pulsa y mantén pulsado el botón grande: se **encoge un 2,5 %** y el halo sube de 0,4 a 0,85.
3. Cambia a **modo oscuro** y comprueba que el rim light sigue visible (tokens `DarkGlass`) y que el
   texto mantiene contraste.

### Ajustar el efecto (si quieres probar variantes)

En cualquier llamada a `liquidGlass(...)`:

```kotlin
Modifier.liquidGlass(
    cornerRadius = 28.dp,   // radio de la gota
    blurRadius = 16.dp,     // fuerza de la refracción (8-24 recomendado)
    refraction = 6.dp,      // desplazamiento de la banda de lente
    rimLight = 1f,          // 0 = sin borde luminoso
    glow = 0.4f,            // 0 = sin halo
    glowAccent = MaterialTheme.colorScheme.tertiary,
    breathing = true        // halo que respira
)
```

Prueba `blurRadius = 24.dp` + `refraction = 10.dp` para un cristal más "grueso", o
`glow = 0f` para el estilo minimalista de AgeFit.

---

## 4. Probar el Asistente IA Fitness

### 4.1 Modo local (sin API Key, sin red)

1. **Asistente IA Fitness** → el panel de clave aparece abierto.
2. Ciérralo con *cerrar* y prueba los chips:
   - **"Genera mi plan de entrenamiento"** → aparece una tarjeta de plan con LUNES…DOMINGO, series,
     repeticiones y el motivo.
   - **"Resumen de mi progreso"** → puntuación, pasos, sueño, pulso, SpO2 y 3 acciones.
   - **"Motivame para hoy"** → reto concreto con los pasos que te faltan.
   - **"Mejora mi sueño"** → rutina de relajación paso a paso.
3. Cada respuesta lleva el aviso: *"…usado el motor local de Panda Fit (sin enviar nada a internet)"*.

### 4.2 Modo Gemini (con API Key)

1. Consigue la clave en <https://aistudio.google.com/apikey>.
2. Pégala en el panel → **Guardar cifrada** → **Probar con Gemini**.
   - Mensaje esperado: *"Clave verificada. Modelo activo: gemini-…"*.
3. Pregunta lo que quieras: *"¿cómo voy esta semana y qué entreno mañana?"*.
   - La respuesta se **escribe progresivamente** (streaming SSE) con los puntos animados "escribiendo…".
4. **Ver contexto**: pulsa el chip del final de la fila de acciones; se muestra exactamente el bloque
   anónimo enviado (pasos, sueño, pulso, SpO2, fecha agregada). **Comprueba que no aparece ningún dato
   personal.**
5. **Plan con Gemini**: pulsa "Genera mi plan de entrenamiento". Debe aparecer la tarjeta del plan
   (LUNES…DOMINGO) y, si el modelo respeta el formato, la etiqueta *"Generado por Gemini con tus datos
   agregados"*; si no, la app cae al plan local sin dejar la pantalla vacía.

### 4.3 Modo voz (TextToSpeech)

1. Pulsa el icono 🔊 de la cabecera: se activa (**pulsa otra vez para desactivar**).
2. Escribe o pulsa un chip: la respuesta se lee en voz alta en español (velocidad 1.02×).
3. El texto leído va **sin markdown ni emojis** (`sanitizeForSpeech`).
4. También hay un altavoz pequeño en cada respuesta de la IA para leer solo esa respuesta.
5. Si el dispositivo no tiene voz en español, usa la del sistema (no falla).

### 4.4 Errores y degradación (lo que hay que comprobar)

| Escenario | Comportamiento esperado |
|---|---|
| Sin API Key | Mensaje: *"Añade tu API Key de Google Gemini en Ajustes…"* + respuesta del motor local. |
| Clave revocada / inválida | *"Tu API Key de Gemini no es válida. Compruébala en Ajustes."* |
| Sin red | *"No hay conexión. Te respondo con el motor local de Panda Fit."* |
| Gemini saturado (429) | *"Gemini está muy solicitado. Espera unos segundos y reintenta."* |
| Respuesta en blanco | *"Gemini no devolvió respuesta. Prueba a reformular la pregunta."* |
| Borrar la clave | Perfil → **Borrar clave**: el asistente vuelve al modo local y la clave desaparece del almacenamiento cifrado. |

### 4.5 Verificación de seguridad (importante)

1. **Perfil → API Key de Gemini**: debe mostrar solo `AIzaSy…7f2a` (enmascarada).
2. Inspecciona el almacenamiento en el dispositivo (Android Studio → *Device Explorer* →
   `/data/data/com.pandafit.app/shared_prefs/panda_fit_prefs.xml`): el valor de
   `gemini_api_key_cifrada` debe empezar por `v1:` y ser **texto cifrado ilegible** (Base64).
3. La clave **no** aparece en Logcat (búsqueda de `AIza` en Logcat → 0 resultados) ni en las copias de
   seguridad (`res/xml/panda_backup_rules.xml` y `panda_data_extraction_rules.xml` la excluyen).

---

## 5. Probar Health Connect y el entrenamiento GPS

### Health Connect
1. **Salud → Conectar Health Connect** → concede permisos.
2. El chip del inicio cambia a **"Datos reales"** y las cifras dejan de ser simuladas.
3. Para tener datos de prueba: en la app Health Connect de Android puedes añadir pasos/sueño a mano, o
   usa Google Fit / Samsung Health como fuente durante un día.
4. **Escritura de vuelta**: en la sesión de entrenamiento, al terminar, Panda Fit inserta pasos, pulso
   y SpO2 con `Metadata.manualEntry()`.

### GPS
1. **Entrenar** → elige tipo (Caminata/Carrera/…) → **Iniciar**.
2. Sal al exterior: *"Esperando señal GPS…"* → *"GPS preciso activo"* con latitud, longitud, velocidad
   y precisión en metros (objetivo < 10 m).
3. Bloquea la pantalla y camina 200 m: la notificación debe seguir actualizándose y el cronómetro en la
   app mostrar el tiempo real (`PandaFitBackgroundService` es un foreground service).
4. Pulsa **Terminar**: aparece la tarjeta **ÚLTIMA SESIÓN** con duración, km (haversine), kcal y nº de
   puntos GPS; además se sube a Firestore.

---

## 6. Comprobaciones rápidas de calidad

```powershell
# 1. Todo compila y pasan las pruebas
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease

# 2. Lint (avisos, no bloquea el build)
.\gradlew.bat :app:lintDebug
#    Informe: app/build/reports/lint-results-debug.html

# 3. Tamaño de los APK
Get-ChildItem -Recurse app\build\outputs\apk -Filter *.apk |
    Select-Object FullName, @{n='MB';e={[math]::Round($_.Length/1MB,2)}}
```

---

## 7. Chuleta de logs para depurar

| Filtro Logcat | Qué muestra |
|---|---|
| `PandaFitBle` | Escaneo, conexión, servicios, familia del reloj, reconexión y vigilante del enlace. |
| `PandaFitService` | Ciclos de persistencia, guardado de entrenamientos y GPS. |
| `PandaFitCloud` | Sincronización con Firestore (y fallos que quedan encolados). |
| `PandaFitGemini` | Modelo detectado por `ListModels` y errores de la API. |
| `PandaFitAI` | Texto a voz y errores del asistente. |
| `PandaFitHealthConnect` | Lecturas y escrituras en Health Connect. |
| `PandaFitPrefs` | Cifrado/descifrado del API Key (nunca el valor). |
