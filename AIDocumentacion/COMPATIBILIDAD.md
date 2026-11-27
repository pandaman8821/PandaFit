# 🐼 Panda Fit — Compatibilidad con relojes y bandas (K56 Pro y familia)

## Respuesta corta

**Sí, el K56 Pro Smart Watch es compatible con Panda Fit, y está comprobado con un reloj real.**
El 17-nov-2026 se conectó un K56 Pro (fabricante `MOYOUNG-V2`). Dio pasos (7.058, iguales a los
de la app DaFit) y presión arterial (113/70 mmHg).

El volcado de ese reloj mostró que publica **dos** servicios propios:

| Servicio | Canales | Qué es en realidad |
|---|---|---|
| `0xFEEA` | `0xFEE2` (escritura) · `0xFEE3` (respuestas) · `0xFEE1` (pasos) | **Protocolo DaFit/Moyoung**: aquí van TODAS las órdenes |
| `0xFEE7` | `0xFEA1` (notifica) · `0xFEC9` (**solo lectura**) | Podómetro del perfil WeChat: solo publica pasos |

> Corrección importante respecto a versiones anteriores de esta guía: antes se decía que el
> K56 Pro recibía las órdenes por `0xFEE7`/`0xFEC9`. **Es falso**: `0xFEC9` es de solo lectura,
> así que las órdenes de pulso, SpO2 y sueño nunca llegaban al reloj. Desde la v5 se envían por
> `0xFEE2`, y `0xFEC9` solo se usa si un reloj no tiene `0xFEE2`.

---

## 1. Qué detecta Panda Fit automáticamente (familia del reloj)

Al descubrir los servicios, Panda Fit clasifica el reloj en una **familia**, que aparece en el
registro ("Reloj detectado: …") y en el Diagnóstico BLE:

| Familia | Se detecta por | Qué hace la app |
|---|---|---|
| **DaFit / Moyoung** (incluye el K56 Pro real) | servicio `0xFEEA` | Órdenes por `0xFEE2`, respuestas por `0xFEE3`, pasos por `0xFEE1` y `0xFEA1` |
| **K56 / perfil FEE7** | `0xFEE7` **sin** `0xFEEA` | Órdenes por `0xFEC9` (escritura y, si falla, lectura) |
| **Reloj genérico** | `0xFFF0` / `0xFF00` | Órdenes DaFit por el canal escribible de esos servicios |
| **Perfil estándar** | `0x180D` / `0x1822` | **Solo escucha** pulso, SpO2 y batería; nunca escribe órdenes |
| **Desconocida** | nada de lo anterior | **Solo escucha**; nunca escribe órdenes |

En las familias "estándar" y "desconocida" la app no manda órdenes DaFit. Antes las escribía en
el primer canal escribible, lo que podía confundir o bloquear a relojes de otras marcas.

## 2. Qué datos se obtienen

| Dato | Comando / canal | Cómo se interpreta |
|---|---|---|
| Pulso en vivo | `0x6D` | Solo al pulsar **Medir pulso**. Payload `{0x00}` iniciar / `{0xFF}` parar; `bpm` en el primer byte |
| Oxígeno en sangre | `0x6B` | Solo al pulsar **Medir oxígeno**. Primer byte plausible (tolera byte de estado: `00 62 00` = 98 %) |
| Presión arterial | `0x69` | Al pulsar **Medir presión**, o cuando el reloj la mide por su cuenta. `{sinUsar, sistólica, diastólica}` |
| Pasos de **hoy** | canal `0xFEE1` y podómetro `0xFEA1` | `0xFEE1`: tres enteros de 24 bits LE. `0xFEA1`: `{flags, pasos u24 LE…}` |
| Pasos de **ayer** | `0x33` argumento 1 | Solo se anotan en el registro. Antes se sumaban por error a los de hoy |
| Sueño de anoche | `0x32` | Tramos `{tipo, hora, minuto}` (0 despierto, 1 ligero, 2 profundo): horas, fases e hipnograma |
| Pulso histórico | `0x35` | Lista de valores plausibles |
| Batería | `0x180F`/`0x2A19` | Estándar |
| Fabricante / modelo / firmware | `0x180A` | Se muestran en el Diagnóstico BLE |

Todos los valores se validan contra rangos fisiológicos antes de publicarse: **Panda Fit nunca
inventa ni muestra un dato imposible**.

**Al conectar o sincronizar, la app solo LEE datos guardados** (pasos, sueño, historial).
Las mediciones (pulso, SpO2, presión) solo se lanzan desde sus botones. Antes se lanzaban en cada
conexión y el reloj se ponía a medir "solo".

## 3. Cómo confirmarlo con TU reloj (2 minutos, sin ordenador)

1. Cierra la app DaFit (si tiene el reloj ocupado, muchos K56 no aceptan una segunda app).
2. Abre Panda Fit → **Inicio** → tarjeta de la pulsera → **Buscar** → toca tu reloj en la lista.
   Los relojes ya emparejados o conectados a otra app también aparecen en la lista.
3. Pulsa **Diagnóstico BLE** → **Copiar** o **Compartir**. Con un K56 Pro deberías ver:

```
Familia de reloj: DaFit / Moyoung (FEEA)
Fabricante: MOYOUNG-V2
...
- Conectado: K56Pro
- MTU negociado: 247 (status 0)
- Reloj detectado: DaFit / Moyoung (FEEA)
- Canal de comandos: FEE2 (DaFit)
- Suscrito a 5 canal(es) de datos
- Pasos del reloj: 7058
- Respuesta del reloj: cmd 0x32 (...)
```

4. Pulsa **Medir pulso** en la tarjeta de Ritmo cardiaco y deja el reloj quieto ~30 s.
   En el registro debe aparecer `Respuesta del reloj: cmd 0x6D`.
5. Si tu reloj expone UUIDs distintos, el mismo informe trae el volcado completo de servicios
   para añadirlos a `BleProtocol.kt`.

## 4. ⚠️ Advertencia crítica sobre el comando de hora (0x31)

**Panda Fit NO envía nunca el comando 0x31 al K56 Pro.** Motivo, documentado en el proyecto
anterior con evidencia de campo:

> El firmware Moyoung del K56 Pro **no** espera un timestamp Unix en `0x31`: espera la hora
> **local** en formato `{año-2000, mes, día, hora, minuto, segundo}`. Enviar el formato Unix
> (5 bytes con `0x08` final) hace que el reloj pase a **2106**, muestre el mes **204** y
> **BORRE los contadores diarios** (pasos y sueño a cero), aunque ni siquiera confirma el comando.

Por eso la hora del reloj se ajusta **con la app oficial DaFit** o desde el propio reloj.
Panda Fit solo envía comandos de lectura/medición.

## 5. Otros relojes

| Modelo / familia | Compatibilidad | Protocolo |
|---|---|---|
| **K56 Pro** (`MOYOUNG-V2`) | ✅ Comprobado con reloj real | `0xFEEA` + `0xFEE2` (el `0xFEE7` es solo el podómetro WeChat) |
| K56 / K56S y clones Moyoung | ✅ Misma familia | `0xFEEA` + `0xFEE2` |
| H Band, ID1xx, Y68, Colmi, P8, Kospet, DaFit-like | ✅ Muy probable | Moyoung o vendor `0xFFF0`/`0xFF00` |
| Amazfit, Xiaomi/Mi Band, Huawei/Honor, Galaxy, Garmin, Polar, Coros… | ⚠️ Solo pulso (y SpO2/batería si los publican), con la opción "compartir frecuencia cardiaca" activada en el reloj | `0x180D`, `0x1822`, `0x180F` — modo solo escucha |
| Relojes con cifrado propietario (Apple Watch, Fitbit, datos completos de Xiaomi/Huawei/Garmin) | ❌ Pasos y sueño no accesibles sin su app | — |

## 6. Qué se ha verificado en este proyecto

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

**68 pruebas, 0 fallos** (v8). Entre ellas:

- Tramas `0xFE 0xEA` (modo V2 `0x2x` y legado `0x1x`), reensamblado de respuestas partidas y fin de transacción.
- Payloads del fabricante: pulso `{00}/{FF}`, oxígeno `{00}/{FF}`, presión `{0,0,0}`/`{FF,FF,FF}`.
- SpO2 tolerante al byte de estado (`00 62 00` → 98 %) y presión validada (120/80 sí; 5/2 no).
- Pasos de `0xFEE1` y del podómetro WeChat `0xFEA1` (que nunca se confunden con pulso).
- Sueño: fases, noche que cruza la medianoche, hora de dormirse/despertar y datos incoherentes rechazados.
- Detección de familia: el K56 Pro real (`0xFEEA` + `0xFEE7`) se clasifica como DaFit/Moyoung.

Comprobado con el reloj real (17-nov-2026): conexión estable, pasos y presión. Pendiente de
confirmar en campo: las respuestas a pulso, SpO2 y sueño por `0xFEE2` (v5 en adelante).
