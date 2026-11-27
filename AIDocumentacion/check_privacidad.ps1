# ============================================================================
#  Panda Fit - check_privacidad.ps1
#  Comprobacion estatica de la POLITICA DE PRIVACIDAD del proyecto.
#
#  Uso:
#     pwsh -File AIDocumentacion/check_privacidad.ps1
#
#  Sale con codigo 1 si encuentra un incumplimiento. Se puede ejecutar en CI.
#
#  Que verifica:
#    1. Que ningun Log.* imprima la API Key (busca "apiKey" dentro de un Log).
#    2. Que el API Key se guarde siempre a traves del cifrado (no en claro).
#    3. Que las reglas de backup excluyan el archivo de preferencias real.
#    4. Que todo texto libre hacia Gemini pase por el scrubber de PII.
#    5. Que ningun permiso de salud/ubicacion se use sin declararlo en el manifiesto.
# ============================================================================

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root 'app\src\main\AndroidManifest.xml'))) {
    $root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$src = Join-Path $root 'app\src\main'

$fallos = New-Object System.Collections.Generic.List[string]
$oks = New-Object System.Collections.Generic.List[string]

function Leer([string]$ruta) {
    if (-not (Test-Path $ruta)) { return '' }
    return [System.IO.File]::ReadAllText($ruta, [System.Text.Encoding]::UTF8)
}

# --- 1. La API Key nunca en logs -------------------------------------------
$fuentesKt = Get-ChildItem -Recurse (Join-Path $src 'java') -Filter *.kt
$logConClave = @()
foreach ($f in $fuentesKt) {
    $lineas = (Leer $f.FullName) -split "`r?`n"
    for ($i = 0; $i -lt $lineas.Count; $i++) {
        $l = $lineas[$i]
        if ($l -match '(?i)\b(Log\.[dviwe]|println)\s*\(' -and
            $l -match '(?i)(apiKey|api_key|\$key\b|getGeminiApiKey)') {
            $logConClave += "$($f.Name):$($i + 1)"
        }
    }
}
if ($logConClave.Count -gt 0) {
    $fallos.Add("La API Key podria imprimirse en logs -> $($logConClave -join ', ')")
} else {
    $oks.Add('Ninguna llamada a Log.* imprime la API Key')
}

# --- 2. Guardado cifrado ----------------------------------------------------
$prefs = Leer (Join-Path $src 'java\com\pandafit\app\data\SharedPreferencesHelper.kt')
if ($prefs -match 'saveGeminiApiKey' -and $prefs -match 'crypto\.encrypt\(') {
    $oks.Add('La API Key se guarda a traves del cifrado (crypto.encrypt)')
} else {
    $fallos.Add('saveGeminiApiKey no usa cifrado')
}
if ($prefs -match 'AES/GCM/NoPadding' -and $prefs -match 'AndroidKeyStore') {
    $oks.Add('Cifrado AES-256-GCM con Android Keystore')
} else {
    $fallos.Add('Falta AES/GCM/NoPadding o AndroidKeyStore en el cifrado')
}

# --- 3. Reglas de backup ----------------------------------------------------
$prefsName = 'panda_fit_prefs'
foreach ($xml in @('res\xml\panda_backup_rules.xml', 'res\xml\panda_data_extraction_rules.xml')) {
    $c = Leer (Join-Path $src $xml)
    if ($c -match [regex]::Escape("$prefsName.xml")) {
        $oks.Add("$xml excluye $prefsName.xml")
    } else {
        $fallos.Add("$xml NO excluye $prefsName.xml (la clave se respaldaria)")
    }
}

# --- 4. Scrubber de PII en el chat libre ------------------------------------
$prompt = Leer (Join-Path $src 'java\com\pandafit\app\ai\PromptBuilder.kt')
$vm = Leer (Join-Path $src 'java\com\pandafit\app\ai\AIViewModel.kt')
if ($prompt -match 'object PiiScrubber' -and $prompt -match 'PiiScrubber\.scrub\(userMessage\)') {
    $oks.Add('PromptBuilder enmascara el texto libre del usuario')
} else {
    $fallos.Add('PromptBuilder no aplica PiiScrubber al mensaje del usuario')
}
if ($vm -match 'PiiScrubber\.scrub\(userText\)') {
    $oks.Add('AIViewModel aplica el scrubber antes de guardar/enviar')
} else {
    $fallos.Add('AIViewModel no aplica el scrubber al texto del usuario')
}

# --- 5. Permisos declarados -------------------------------------------------
$manifest = Leer (Join-Path $src 'AndroidManifest.xml')
$checks = @(
    @('BLUETOOTH_SCAN', 'Bluetooth'),
    @('BLUETOOTH_CONNECT', 'Bluetooth'),
    @('ACCESS_FINE_LOCATION', 'Ubicacion'),
    @('ACTIVITY_RECOGNITION', 'Actividad'),
    @('health.READ_STEPS', 'Health Connect'),
    @('health.WRITE_STEPS', 'Health Connect'),
    @('FOREGROUND_SERVICE_LOCATION', 'Servicio'),
    @('RECEIVE_BOOT_COMPLETED', 'Arranque')
)
$faltan = @()
foreach ($c in $checks) {
    if ($manifest -notmatch [regex]::Escape($c[0])) { $faltan += $c[0] }
}
if ($faltan.Count -gt 0) {
    $fallos.Add("Permisos no declarados en el manifiesto -> $($faltan -join ', ')")
} else {
    $oks.Add('Todos los permisos esperados estan declarados')
}

# --- Informe ---------------------------------------------------------------
Write-Output '=========================================================='
Write-Output ' Panda Fit - comprobacion de privacidad y permisos'
Write-Output " Proyecto: $root"
Write-Output '=========================================================='
foreach ($o in $oks)     { Write-Output "  [OK]    $o" }
foreach ($f in $fallos)  { Write-Output "  [FALLO] $f" }
Write-Output '----------------------------------------------------------'
if ($fallos.Count -gt 0) {
    Write-Output " RESULTADO: $($fallos.Count) incumplimiento(s) de $($oks.Count + $fallos.Count) comprobaciones"
    exit 1
}
Write-Output " RESULTADO: OK - $($oks.Count) comprobaciones superadas"
exit 0
