// ============================================================================
//  Panda Fit - FrasesCoach.kt
//  0.1.7: frases del ENTRENADOR POR VOZ en los 6 idiomas de la app (es, en,
//  pt, fr, it, de). Funciona fuera de la interfaz (servicio en segundo plano),
//  por eso van aqui y no en strings.xml. Funcion pura: se prueba sin telefono.
// ============================================================================
package com.pandafit.app.data

class FrasesCoach(val idioma: String) {

    private class F(
        val hora: String, val horas: String, val minuto: String, val minutos: String,
        val segundo: String, val segundos: String,
        val km1: String, val kmN: String, val tiempo: String, val ritmo: String, val pulso: String,
        val llevas: String, val sigue: String, val pausaAuto: String, val seguimos: String, val pausa: String,
        val empezamos: String, val terminadoKm: String, val terminadoTiempo: String
    )

    private val f: F = when (idioma) {
        "en" -> F("1 hour", "%d hours", "1 minute", "%d minutes", "1 second", "%d seconds",
            "Kilometer 1.", "%d kilometers.", "Time: %s.", "Last kilometer pace: %d minutes %d per kilometer.",
            "Heart rate: %d.", "You've done %s.", "Keep it up.", "Auto pause.", "Let's go on.", "Paused.",
            "Starting: %s. Let's go!", "Workout finished. %s kilometers in %s. Great job!",
            "Workout finished. Duration: %s. Great job!")
        "pt" -> F("1 hora", "%d horas", "1 minuto", "%d minutos", "1 segundo", "%d segundos",
            "Quilômetro 1.", "%d quilômetros.", "Tempo: %s.", "Ritmo do último quilômetro: %d minutos %d por quilômetro.",
            "Frequência: %d.", "Você já fez %s.", "Continue assim.", "Pausa automática.", "Vamos continuar.", "Pausa.",
            "Começando: %s. Vamos!", "Treino concluído. %s quilômetros em %s. Bom trabalho!",
            "Treino concluído. Duração: %s. Bom trabalho!")
        "fr" -> F("1 heure", "%d heures", "1 minute", "%d minutes", "1 seconde", "%d secondes",
            "Kilomètre 1.", "%d kilomètres.", "Temps : %s.", "Allure du dernier kilomètre : %d minutes %d au kilomètre.",
            "Fréquence cardiaque : %d.", "Tu as fait %s.", "Continue comme ça.", "Pause automatique.", "On repart.", "Pause.",
            "C'est parti : %s. Allez !", "Entraînement terminé. %s kilomètres en %s. Bravo !",
            "Entraînement terminé. Durée : %s. Bravo !")
        "it" -> F("1 ora", "%d ore", "1 minuto", "%d minuti", "1 secondo", "%d secondi",
            "Chilometro 1.", "%d chilometri.", "Tempo: %s.", "Ritmo dell'ultimo chilometro: %d minuti %d al chilometro.",
            "Battito: %d.", "Hai fatto %s.", "Continua così.", "Pausa automatica.", "Si riparte.", "Pausa.",
            "Iniziamo: %s. Forza!", "Allenamento finito. %s chilometri in %s. Ottimo lavoro!",
            "Allenamento finito. Durata: %s. Ottimo lavoro!")
        "de" -> F("1 Stunde", "%d Stunden", "1 Minute", "%d Minuten", "1 Sekunde", "%d Sekunden",
            "Kilometer 1.", "%d Kilometer.", "Zeit: %s.", "Tempo des letzten Kilometers: %d Minuten %d pro Kilometer.",
            "Puls: %d.", "Du hast %s geschafft.", "Weiter so.", "Automatische Pause.", "Weiter geht's.", "Pause.",
            "Los geht's: %s. Auf geht's!", "Training beendet. %s Kilometer in %s. Gut gemacht!",
            "Training beendet. Dauer: %s. Gut gemacht!")
        else -> F("1 hora", "%d horas", "1 minuto", "%d minutos", "1 segundo", "%d segundos",
            "Kilómetro 1.", "%d kilómetros.", "Tiempo: %s.", "Ritmo del último kilómetro: %d minutos %d por kilómetro.",
            "Pulso: %d.", "Llevas %s.", "Sigue así.", "Pausa automática.", "Seguimos.", "Pausa.",
            "Empezamos: %s. ¡Vamos!", "Entrenamiento terminado. %s kilómetros en %s. ¡Buen trabajo!",
            "Entrenamiento terminado. Duración: %s. ¡Buen trabajo!")
    }

    fun duracion(seg: Int): String {
        val h = seg / 3600; val m = (seg % 3600) / 60; val s = seg % 60
        val partes = mutableListOf<String>()
        if (h > 0) partes += if (h == 1) f.hora else f.horas.format(h)
        if (m > 0) partes += if (m == 1) f.minuto else f.minutos.format(m)
        if (s > 0 && h == 0) partes += if (s == 1) f.segundo else f.segundos.format(s)
        return partes.joinToString(" ").ifEmpty { f.segundos.format(0) }
    }

    fun km(km: Int, totalSeg: Int, ritmoSeg: Int, pulso: Int): String = buildString {
        append(if (km == 1) f.km1 else f.kmN.format(km)); append(' ')
        append(f.tiempo.format(duracion(totalSeg))); append(' ')
        append(f.ritmo.format(ritmoSeg / 60, ritmoSeg % 60))
        if (pulso > 0) { append(' '); append(f.pulso.format(pulso)) }
    }

    fun tiempo(totalSeg: Int, pulso: Int): String = buildString {
        append(f.llevas.format(duracion(totalSeg)))
        if (pulso > 0) { append(' '); append(f.pulso.format(pulso)) }
        append(' '); append(f.sigue)
    }

    val pausaAuto get() = f.pausaAuto
    val seguimos get() = f.seguimos
    val pausa get() = f.pausa
    fun empezamos(actividad: String) = f.empezamos.format(actividad)
    fun terminado(km: Double, seg: Int): String =
        if (km >= 0.1) f.terminadoKm.format(String.format(java.util.Locale.getDefault(), "%.2f", km), duracion(seg))
        else f.terminadoTiempo.format(duracion(seg))
}
