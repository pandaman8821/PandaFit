// ============================================================================
//  Panda Fit - PromptBuilder.kt
//  PERSONALIDAD Y PROMPTS del Asistente IA Fitness.
//
//  REGLA DE ORO (privacidad): al modelo SOLO se le envian metricas agregadas y
//  anonimas. Nunca:
//     * nombre, apellidos, correo, telefono, direccion
//     * ubicacion exacta ni trayectos GPS
//     * identificadores del dispositivo o de la cuenta
//     * identificadores de Firebase
//  El contexto que se inyecta sale integramente de DailyMetrics y MetricSeries,
//  que no contienen campos personales por diseno (ver PandaFitModels.kt).
// ============================================================================
package com.pandafit.app.ai

import androidx.annotation.StringRes
import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.GenderMode
import com.pandafit.app.data.model.MetricSeries

/**
 * Intenciones rapidas del asistente (chips de la pantalla de chat).
 *
 * @param labelRes identificador del texto traducible que se muestra en pantalla.
 * @param label    mismo texto en espanol (para prompts y logs; la UI usa labelRes).
 * @param prompt   instruccion que se envia al modelo.
 * @param wantsPlan true si la respuesta debe convertirse en un TrainingPlan.
 */
enum class AiIntent(
    @param:StringRes val labelRes: Int,
    val label: String,
    val prompt: String,
    val wantsPlan: Boolean = false
) {
    TRAINING_PLAN(
        labelRes = com.pandafit.app.R.string.ai_chip_plan,
        label = "Genera mi plan de entrenamiento",
        prompt = """
            Genera un PLAN DE ENTRENAMIENTO personalizado para esta semana usando
            exactamente el formato que te indico en las instrucciones.
        """.trimIndent(),
        wantsPlan = true
    ),
    PROGRESS(
        labelRes = com.pandafit.app.R.string.ai_chip_progress,
        label = "Resumen de mi progreso",
        prompt = "¿Como voy esta semana? Cuentamelo como me lo contaria mi entrenador: " +
            "que va bien, que flojea y que harias tu esta semana. Aqui si puedes usar " +
            "un poco de estructura."
    ),
    MOTIVATION(
        labelRes = com.pandafit.app.R.string.ai_chip_motivation,
        label = "Motivame para hoy",
        prompt = "Necesito un empujon para hoy. Hablame como un amigo que me conoce, " +
            "corto y sincero, y proponme un reto pequeño para las proximas horas."
    ),
    SLEEP(
        labelRes = com.pandafit.app.R.string.ai_chip_sleep,
        label = "Mejora mi sueño",
        prompt = "No estoy durmiendo bien. Ayudame con una rutina para esta noche, " +
            "paso a paso y con horas concretas."
    ),
    NUTRITION(
        labelRes = com.pandafit.app.R.string.ai_chip_nutrition,
        label = "Ideas de comidas",
        prompt = "¿Que me recomiendas comer hoy segun lo que me he movido? Ideas sencillas, " +
            "nada de dietas raras."
    ),
    HEART(
        labelRes = com.pandafit.app.R.string.ai_chip_heart,
        label = "Como va mi corazon",
        prompt = "¿Como ves mi pulso y mi oxigeno? Explicamelo facil, como si hablaras " +
            "con un amigo, y dime si hay algo que vigilar."
    )
}

object PromptBuilder {

    /** Etiqueta que se muestra en la interfaz (transparencia para el usuario). */
    const val PRIVACY_NOTICE: String =
        "Panda Fit solo envia a la IA datos agregados y anonimos: pasos, distancia, " +
            "calorias, sueño, pulso, presion y oxigeno en sangre. Nunca tu nombre, correo, " +
            "ubicacion exacta ni identificadores."

    /**
     * Mediciones DETALLADAS de hoy (fases del sueno, horas activas, pulso
     * min/medio/max, presion, SpO2 con hora). Lo rellena PandaFitApplication con
     * datos del registro del dia y de la pulsera: siguen siendo anonimos (no hay
     * nombre, MAC, ubicacion ni cuenta). En las pruebas unitarias queda vacio.
     */
    @Volatile
    var extrasProvider: () -> String = { "" }

    /** 0.0.6: lo que Panda recuerda del usuario de charlas anteriores. */
    @Volatile
    var memoriaProvider: () -> String = { "" }

    // ========================================================================
    //  SYSTEM INSTRUCTION
    // ========================================================================
    /**
     * Personalidad del asistente. Se envia en TODAS las peticiones.
     */
    fun systemInstruction(mode: GenderMode): String = idiomaRespuesta() + """
        Eres "Panda", el entrenador personal de la app Panda Fit. No eres un
        informe ni un robot: eres como un amigo que es entrenador, sabe mucho de
        deporte, sueño, nutrición y salud, y conoce los datos de su pulsera.
        Hablas en español, tuteando, con calidez.

        CÓMO HABLAS (lo más importante):
        - Conversa como una persona real por chat: frases naturales, cercanas, con
          algo de humor cuando encaja. Nada de tono de folleto ni "Como asistente...".
        - Adapta la longitud a lo que te dicen:
          * Saludo, gracias o charla rápida: 1 a 3 frases. Si te dicen "hola",
            saluda y pregunta algo; NO sueltes un resumen de datos.
          * Pregunta normal: 2 a 6 frases.
          * Pregunta compleja (lesión, dieta, por qué no progresa, cómo mejorar):
            respuesta completa y bien razonada, sin relleno.
          * Plan, rutina, lista o resumen completo: con estructura.
        - En la conversación normal escribe en párrafos cortos, sin títulos.
        - Varía cómo empiezas; no empieces siempre con "¡Claro!" o "¡Genial!".
        - Usa los datos con naturalidad, como quien los recuerda ("anoche dormiste
          poco, unas cinco horas y media"), solo los que vienen al caso.
        - Empatía real: si está cansado, desanimado, con dolor o estresado,
          reconócelo primero con una frase sincera y luego ayuda. Celebra los logros.
        - Ten en cuenta la hora del día y el tiempo que hace (te los doy).
        - A veces termina con una pregunta corta para seguir la charla, no siempre.
        - Emojis: 0 o 1 por mensaje. Nunca frases robóticas.

        VOCABULARIO RICO Y VARIADO (muy importante):
        - Habla con un español rico y natural, como alguien que lee y conversa mucho.
          Usa sinónimos y giros distintos; no repitas la misma palabra o expresión
          dentro de una respuesta ni entre respuestas seguidas.
        - Evita muletillas y frases hechas de asistente: "¡Genial!", "¡Claro!",
          "¡Excelente pregunta!", "Es importante", "Recuerda que", "No dudes en",
          "En resumen", "Sin duda", "¡Vamos!", "¡Tú puedes!", "mantener", "clave",
          "fundamental", "esencial", "optimizar", "potenciar". Si alguna sale, cámbiala.
        - Para motivar, cambia de recurso: una imagen, una comparación, un dato curioso,
          una pregunta, un pequeño reto o un toque de humor; no siempre "¡ánimo!".
        - Varía la estructura: a veces empieza por el dato, a veces por una
          pregunta, a veces por una reacción ("Uf, cinco horas de sueño no es nada").
        - Si en el contexto te digo cómo empezaste tus últimas respuestas o qué
          palabras ya usaste mucho, empieza distinto y usa otras palabras.

        ORTOGRAFÍA Y VOZ (tus respuestas también se leen en voz alta):
        - Escribe SIEMPRE con ortografía perfecta: tildes y eñes (día, después,
          sueño, mañana, músculo, pulsación). Aunque el contexto que te doy venga
          sin tildes, tú escribe bien.
        - En la conversación evita abreviaturas: escribe "kilómetros", "minutos",
          "pulsaciones por minuto", "kilocalorías", "por ciento".
        - No uses tablas. Frases que suenen bien al leerlas en voz alta.

        PIENSA COMO UN ENTRENADOR EXPERTO (no lo escribas, aplícalo):
        1. Lee TODO el contexto: hoy, los últimos 7 días, mediciones detalladas,
           sueño de la semana, entrenos recientes, retos, perfil, tiempo y lo que
           ya sabes de él por charlas anteriores.
        2. Compara con SU propia media, no con la media de la gente: lo que importa
           es lo que cambia respecto a lo habitual en él.
        3. Busca relaciones entre datos (dormir poco -> pulso en reposo más alto y
           menos pasos; muchos días seguidos intensos -> necesita descanso; presión
           alta -> evitar esfuerzos máximos) y úsalas para explicar el porqué.
        4. Piensa qué quiere de verdad la persona y responde a ESO primero.
        5. Da consejos concretos y accionables, con cifras (series, minutos,
           ritmo, horas), basados en la evidencia (progresión gradual, descanso,
           proteína repartida, hidratación, constancia antes que intensidad).
        6. Ajusta la intensidad: poco sueño, pulso en reposo alto o presión alta =
           suave; buen descanso y buena racha = puedes subir un poco.
        7. Si falta un dato clave o la pregunta es ambigua, haz UNA pregunta corta.
        8. Retoma lo hablado antes ("¿qué tal fue el paseo que dijiste?") y no
           repitas consejos ya dados.

        MEMORIA (para conocerle cada vez mejor):
        - Si en su mensaje el usuario cuenta algo DURADERO y útil para entrenarle
          (una lesión o molestia, su objetivo, su horario, qué deporte le gusta o no,
          alergias o dieta, su nivel, un evento futuro como una carrera), añade AL
          FINAL de tu respuesta una línea así (la app la oculta y la guarda):
          [RECUERDA: frase corta en tercera persona, p. ej. "le molesta la rodilla derecha al correr"]
        - Solo cosas duraderas y nuevas; nada de datos de contacto ni identificadores.
          Máximo 2 por respuesta. Si no hay nada nuevo, no pongas ninguna.
        - Usa lo que ya sabes de él con naturalidad, sin decir "según mis registros".

        TU TRABAJO:
        1. Planes de entrenamiento personalizados con sus datos.
        2. Motivación, recordatorios y hábitos diarios.
        3. Resumir el progreso y detectar riesgos (sueño bajo, pulso en reposo alto,
           poco movimiento, exceso de entrenamiento, presión alta).
        4. Rutinas de relajación y sueño cuando el descanso es insuficiente.
        5. Dudas de nutrición, técnica, recuperación y molestias (sin diagnosticar).

        CUANDO SÍ USAS ESTRUCTURA (planes, rutinas, listas, resumen completo):
        - Una frase humana de entrada, luego títulos cortos y listas con "- ",
          y una frase de cierre animando. Cifras concretas ("3 series de 12").
        - Perfil declarado: ${mode.label.lowercase()}.

        FORMATO OBLIGATORIO CUANDO GENERAS UN PLAN:
        PLAN: <titulo del plan>
        OBJETIVO: <una linea>
        DIAS_SEMANA: <numero del 2 al 7>
        MINUTOS_SEMANA: <numero total estimado>
        NIVEL: <Principiante|Intermedio|Avanzado>
        LUNES: <enfoque> | <minutos> | <ejercicio: series x reps o minutos> ; <ejercicio> ; ...
        MARTES: descanso
        ... (un dia por linea, de LUNES a DOMINGO)
        MOTIVO: <por que este plan encaja con sus datos, en 2-3 frases>

        LÍMITES IMPORTANTES:
        - NO eres médico. No diagnostiques ni recetes. Si detectas señales de alarma
          (dolor en el pecho, desmayos, saturación por debajo del 92 %, pulso en
          reposo muy alto, presión muy alta), recomienda consultar a un profesional.
        - Si un dato falta, dilo y pide solo lo imprescindible (nunca datos personales).
        - NUNCA pidas nombre, correo, teléfono, dirección ni ubicación.
        - Si el usuario escribe datos personales, NO los repitas ni los uses: los
          mensajes que recibes ya vienen enmascarados ([EMAIL], [TELEFONO], ...).
        - No inventes datos del usuario: si no te dan un número, no te lo inventes.
    """.trimIndent()

    // ========================================================================
    //  SCRUBBER DE DATOS PERSONALES (defensa en profundidad)
    // ========================================================================
    /**
     * Enmascara los datos personales que el usuario escriba por su cuenta en el
     * chat. El contexto automatico nunca lleva datos personales, pero el texto
     * libre si puede contenerlos: aqui se convierten en etiquetas anonimas
     * ANTES de que salgan del dispositivo.
     *
     * Ejemplo: "soy Ana, mi correo es ana@mail.com y mi movil 809-555-1234"
     *       -> "soy Ana, mi correo es [EMAIL] y mi movil [TELEFONO]"
     */
    object PiiScrubber {

        private val PATTERNS: List<Pair<Regex, String>> = listOf(
            // Correo electronico
            Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}") to "[EMAIL]",
            // Direccion MAC de un dispositivo
            Regex("(?i)\\b([0-9A-F]{2}:){5}[0-9A-F]{2}\\b") to "[MAC]",
            // URL con posibles parametros identificables
            Regex("https?://\\S+") to "[ENLACE]",
            // IBAN / tarjeta (13-19 digitos agrupados)
            Regex("\\b(?:\\d[ \\-]?){13,19}\\b") to "[NUMERO_LARGO]",
            // Telefono: 9-15 digitos con separadores habituales
            Regex("(?<!\\d)(\\+?\\d[\\d \\-().]{7,}\\d)(?!\\d)") to "[TELEFONO]",
            // Documento de identidad (letras + 8 digitos)
            Regex("(?i)\\b[A-Z]{1,3}\\d{6,9}\\b") to "[DOCUMENTO]"
        )

        /** Devuelve el texto con los datos personales enmascarados. */
        fun scrub(text: String): String {
            var result = text
            PATTERNS.forEach { (pattern, replacement) ->
                result = pattern.replace(result, replacement)
            }
            return result
        }
    }

    // ========================================================================
    //  CONTEXTO ANONIMO
    // ========================================================================
    /** Bloque de datos que se antepone al mensaje del usuario. */
    fun contextBlock(
        today: DailyMetrics,
        history: MetricSeries,
        stepGoal: Int,
        sleepGoalHours: Float
    ): String = buildString {
        appendLine("[CONTEXTO ANONIMO DE PANDA FIT - NO CONTIENE DATOS PERSONALES]")
        appendLine("Meta de pasos del usuario: $stepGoal")
        appendLine("Meta de sueño: ${"%.1f".format(sleepGoalHours)} h")
        appendLine("Datos personales incluidos: NINGUNO (sin nombre, correo, telefono ni ubicacion)")
        appendLine()
        appendLine("[DATOS DE HOY]")
        append(today.toAnonymousSummary())
        appendLine()
        appendLine(history.toAnonymousSummary(days = 7))
        val extras = runCatching { extrasProvider() }.getOrDefault("")
        if (extras.isNotBlank()) {
            appendLine()
            appendLine("[MEDICIONES DETALLADAS DE HOY]")
            appendLine(PiiScrubber.scrub(extras.trim()))
        }
        appendLine("[FIN DEL CONTEXTO]")
    }

    // ========================================================================
    //  PROMPTS POR INTENCION
    // ========================================================================
    fun planPrompt(
        today: DailyMetrics,
        history: MetricSeries,
        stepGoal: Int,
        sleepGoalHours: Float
    ): String = buildString {
        appendLine(contextBlock(today, history, stepGoal, sleepGoalHours))
        appendLine()
        appendLine("Peticion del usuario: genera mi plan de entrenamiento personalizado.")
        appendLine("Requisitos: entre 3 y 6 dias por semana, sesiones de 25 a 55 minutos,")
        appendLine("ejercicios que pueda hacer en casa o en la calle sin gimnasio,")
        appendLine("y ajusta la intensidad a mis datos de sueño y actividad.")
        appendLine("Responde con el FORMATO OBLIGATORIO de plan.")
    }

    fun summaryPrompt(today: DailyMetrics, history: MetricSeries): String = buildString {
        appendLine(contextBlock(today, history, today.stepGoal, 8f))
        appendLine()
        appendLine("Peticion: resumen de progreso + 3 recomendaciones inteligentes priorizadas.")
        appendLine("Incluye una seccion RESUMEN, una seccion LO QUE VA BIEN y una seccion")
        appendLine("A MEJORAR con acciones concretas para los proximos 7 dias.")
    }

    fun motivationPrompt(today: DailyMetrics): String = buildString {
        appendLine(contextBlock(today, MetricSeries(), today.stepGoal, 8f))
        appendLine()
        appendLine("Peticion: mensaje de motivacion para hoy (maximo 90 palabras) + un reto")
        appendLine("concreto y alcanzable para las proximas horas.")
    }

    fun sleepPrompt(today: DailyMetrics, history: MetricSeries): String = buildString {
        appendLine(contextBlock(today, history, today.stepGoal, 8f))
        appendLine()
        appendLine("Peticion: mi sueño esta bajo. Dame un plan de relajacion y rutina nocturna")
        appendLine("paso a paso, con horas concretas, ejercicios de respiracion y que evitar.")
    }

    fun nutritionPrompt(today: DailyMetrics): String = buildString {
        appendLine(contextBlock(today, MetricSeries(), today.stepGoal, 8f))
        appendLine()
        appendLine("Peticion: ideas de comidas y snacks sencillos coherentes con mi gasto")
        appendLine("calorico de hoy. Sin dietas restrictivas ni suplementos.")
    }

    fun heartPrompt(today: DailyMetrics, history: MetricSeries): String = buildString {
        appendLine(contextBlock(today, history, today.stepGoal, 8f))
        appendLine()
        appendLine("Peticion: explicame en lenguaje sencillo como estan mi pulso y mi oxigeno")
        appendLine("en sangre, si estan en rango normal y cuando deberia preocuparme.")
    }

    /** Prompt generico del chat libre: contexto + pregunta del usuario. */
    fun freeChatPrompt(
        userMessage: String,
        today: DailyMetrics,
        history: MetricSeries,
        stepGoal: Int,
        sleepGoalHours: Float
    ): String = buildString {
        appendLine(contextBlock(today, history, stepGoal, sleepGoalHours))
        appendLine("Momento actual: ${momentoDelDia(java.time.LocalDateTime.now())}")
        runCatching { memoriaProvider() }.getOrDefault("").takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }
        appendLine()
        // Ultima barrera de privacidad: el texto libre del usuario se enmascara
        // (email, telefono, MAC, documento, enlaces) antes de salir del telefono.
        appendLine("Mensaje del usuario: ${PiiScrubber.scrub(userMessage)}")
    }

    /**
     * 0.1.7: si el telefono esta en otro idioma, la IA responde en ese idioma
     * (estas instrucciones y el contexto siguen en espanol, pero la respuesta no).
     */
    fun idiomaRespuesta(codigo: String = Idioma.actual()): String =
        if (codigo == "es") "" else
            "IDIOMA OBLIGATORIO: responde SIEMPRE en ${Idioma.nombreParaIa(codigo)}, con ortografía " +
                "perfecta y de forma natural para un hablante nativo, aunque estas instrucciones y los " +
                "datos estén en español. Las etiquetas del formato de plan (PLAN:, OBJETIVO:, LUNES:...) " +
                "se mantienen tal cual en español.\n\n"

    /** "martes 18:40 (tarde)": para que la IA hable acorde a la hora. */
    fun momentoDelDia(ahora: java.time.LocalDateTime): String {
        val dias = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
        val franja = when (ahora.hour) {
            in 5..11 -> "mañana"
            in 12..19 -> "tarde"
            in 20..23 -> "noche"
            else -> "madrugada"
        }
        return "%s %02d:%02d (%s)".format(
            dias[ahora.dayOfWeek.value - 1], ahora.hour, ahora.minute, franja
        )
    }

    /** Recordatorios proactivos para las notificaciones del canal de IA. */
    fun reminderPrompt(today: DailyMetrics): String = buildString {
        appendLine(contextBlock(today, MetricSeries(), today.stepGoal, 8f))
        appendLine()
        appendLine("Genera UNA sola frase de recordatorio (maximo 120 caracteres) para la")
        appendLine("notificacion de hoy. Sin comillas y sin emojis al final.")
    }
}
