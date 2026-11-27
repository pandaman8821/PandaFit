// ============================================================================
//  Panda Fit - FitnessEngine.kt
//  MOTOR LOCAL de Panda Fit: analisis, planes y sugerencias SIN INTERNET.
//
//  Para que sirve:
//    1. El asistente sigue respondiendo si no hay red o el usuario no ha puesto
//       su API Key (modo local: reglas expertas sobre los datos de la app).
//    2. Los "Insights" del panel se calculan aqui (rapidos y privados).
//    3. Traduce a objeto el PLAN que devuelve Gemini (TrainingPlanParser) para
//       poder mostrarlo con tarjetas bonitas y guardarlo en Firestore.
// ============================================================================
package com.pandafit.app.ai

import com.pandafit.app.data.model.DailyMetrics
import com.pandafit.app.data.model.ExerciseItem
import com.pandafit.app.data.model.HealthReport
import com.pandafit.app.data.model.Insight
import com.pandafit.app.data.model.MetricSeries
import com.pandafit.app.data.model.TrainingDay
import com.pandafit.app.data.model.TrainingPlan
import java.util.UUID

object FitnessEngine {

    // ========================================================================
    //  1. ANALISIS LOCAL (Insights)
    // ========================================================================
    /**
     * Genera hallazgos accionables a partir de los datos del dia + la semana.
     * Se usa en el panel de Inicio y como contexto para el asistente.
     */
    fun analyze(
        today: DailyMetrics,
        history: MetricSeries,
        sleepGoalHours: Float = 8f
    ): HealthReport {
        val insights = mutableListOf<Insight>()

        // ---- Sueno -----------------------------------------------------------
        when {
            today.sleepHours <= 0.0 && history.days.isEmpty() -> insights += Insight(
                title = "Sin datos de sueño",
                detail = "Aun no hay ninguna noche registrada.",
                severity = 0,
                recommendation = "Conecta la banda o autoriza Health Connect para empezar a medir."
            )
            today.sleepHours < 6.0 -> insights += Insight(
                title = "Sueño bajo",
                detail = "Dormiste ${fmt(today.sleepHours)} h, por debajo de las ${fmt(sleepGoalHours)} h recomendadas.",
                severity = 2,
                recommendation = "Hoy toca entrenamiento suave y un plan de relajacion antes de dormir."
            )
            today.sleepHours < sleepGoalHours - 0.5 -> insights += Insight(
                title = "Puedes dormir un poco mas",
                detail = "Dormiste ${fmt(today.sleepHours)} h (objetivo ${fmt(sleepGoalHours)} h).",
                severity = 1,
                recommendation = "Adelanta 30 minutos la hora de acostarte durante 3 noches."
            )
            else -> insights += Insight(
                title = "Descanso correcto",
                detail = "Dormiste ${fmt(today.sleepHours)} h con calidad ${today.sleepQuality}/100.",
                severity = 0,
                recommendation = "Aprovecha para un entrenamiento de intensidad media o alta."
            )
        }

        // ---- Actividad -------------------------------------------------------
        val goal = today.stepGoal.coerceAtLeast(1)
        val ratio = today.steps.toFloat() / goal
        when {
            ratio >= 1f -> insights += Insight(
                title = "Meta de pasos completada",
                detail = "${today.steps} de $goal pasos. Vas por encima del objetivo.",
                severity = 0,
                recommendation = "Suma 10 minutos de movilidad para que el cuerpo recupere."
            )
            ratio >= 0.6f -> insights += Insight(
                title = "Buen ritmo de movimiento",
                detail = "${(ratio * 100).toInt()}% de tu meta de pasos.",
                severity = 0,
                recommendation = "Un paseo de ${minutesForRemaining(today)} minutos cierra la meta."
            )
            else -> insights += Insight(
                title = "Poco movimiento",
                detail = "Llevas ${today.steps} pasos (${(ratio * 100).toInt()}% de la meta).",
                severity = 1,
                recommendation = "Ponte una alarma cada 90 minutos y camina 5 minutos."
            )
        }

        // ---- Pulso en reposo -------------------------------------------------
        if (today.restingHeartRate > 0) {
            val avg = history.days.map { it.restingHeartRate }.filter { it > 0 }.average()
            val elevated = today.restingHeartRate > 78 || (avg > 0 && today.restingHeartRate > avg + 8)
            insights += if (elevated) Insight(
                title = "Pulso en reposo elevado",
                detail = "${today.restingHeartRate} ppm (media de la semana ${if (avg > 0) avg.toInt() else 0} ppm).",
                severity = 1,
                recommendation = "Hidratate, evita cafeina por la tarde y descansa hoy."
            ) else Insight(
                title = "Pulso en reposo en rango",
                detail = "${today.restingHeartRate} ppm.",
                severity = 0,
                recommendation = "Rango saludable para tu perfil."
            )
        }

        // ---- Oxigeno en sangre ----------------------------------------------
        if (today.spo2 in 1..100) {
            insights += if (today.spo2 < 94) Insight(
                title = "Oxigeno en sangre bajo",
                detail = "SpO2 ${today.spo2}%. Si se repite, conviene una revision profesional.",
                severity = 2,
                recommendation = "Repite la medicion sentado y en reposo; si sigue bajo, consulta a un profesional de salud."
            ) else Insight(
                title = "Oxigeno en sangre normal",
                detail = "SpO2 ${today.spo2}%.",
                severity = 0,
                recommendation = "Sin cambios necesarios."
            )
        }

        // ---- Tendencia semanal ----------------------------------------------
        if (history.days.size >= 3) {
            val weekSteps = history.averageSteps
            val weekSleep = history.averageSleep
            insights += Insight(
                title = "Tu semana en un vistazo",
                detail = "Media de $weekSteps pasos/dia y ${fmt(weekSleep)} h de sueño.",
                severity = 0,
                recommendation = if (weekSleep < 7.0)
                    "Prioriza el sueño: es el mejor potenciador del rendimiento."
                else "Mantener esta constancia te hara progresar rapido."
            )
        }

        // ---- Puntuacion global (0-100) ---------------------------------------
        val stepScore = (ratio.coerceAtMost(1.2f) / 1.2f) * 45
        val safeSleepGoal = sleepGoalHours.toDouble().coerceAtLeast(4.0)
        val sleepScore = (today.sleepHours / safeSleepGoal).coerceAtMost(1.0) * 35
        val spo2Score = if (today.spo2 >= 95) 20.0 else if (today.spo2 >= 92) 12.0 else 4.0
        val score = (stepScore + sleepScore + spo2Score).toInt().coerceIn(0, 100)

        return HealthReport(
            dateKey = today.dateKey,
            score = score,
            insights = insights,
            summary = buildString {
                append("Puntuacion Panda Fit: $score/100. ")
                append(insights.maxByOrNull { it.severity }?.title ?: "Todo en orden.")
            }
        )
    }

    // ========================================================================
    //  2. PLAN LOCAL (fallback sin internet)
    // ========================================================================
    /**
     * Plan personalizado por reglas: se ajusta a los pasos, sueno y pulso.
     * Es el mismo esquema que el de Gemini, asi la interfaz no cambia.
     */
    fun localPlan(
        today: DailyMetrics,
        history: MetricSeries,
        wantsMuscle: Boolean = false
    ): TrainingPlan {
        val avgSteps = if (history.days.isNotEmpty()) history.averageSteps else today.steps
        val avgSleep = if (history.days.isNotEmpty()) history.averageSleep else today.sleepHours
        val lowSleep = avgSleep > 0 && avgSleep < 6.5
        val veryActive = avgSteps >= 12_000

        val daysPerWeek = when {
            lowSleep -> 3
            veryActive -> 5
            avgSteps >= 8_000 -> 4
            else -> 3
        }
        val minutesPerSession = when {
            dayProgress(today) < 0.4 -> 25
            veryActive -> 50
            else -> 35
        }
        val level = when {
            avgSteps >= 11_000 -> "Avanzado"
            avgSteps >= 7_000 -> "Intermedio"
            else -> "Principiante"
        }

        val strength = TrainingDay(
            day = "LUNES",
            focus = "Fuerza cuerpo completo",
            totalMinutes = minutesPerSession,
            intensity = if (lowSleep) "Baja" else "Media",
            exercises = listOf(
                ExerciseItem("Sentadillas al aire", 3, "12", 0, 45, "Rodillas alineadas con los pies"),
                ExerciseItem("Flexiones (rodillas si hace falta)", 3, "8-12", 0, 45),
                ExerciseItem("Puente de glúteo", 3, "15", 0, 40),
                ExerciseItem("Plancha frontal", 3, "", 0, 45, "Mantén 30-40 segundos")
            )
        )
        val cardio = TrainingDay(
            day = "MI�?RCOLES",
            focus = if (veryActive) "Cardio intervalos suaves" else "Cardio continuo",
            totalMinutes = minutesPerSession,
            intensity = if (lowSleep) "Baja" else "Media",
            exercises = listOf(
                ExerciseItem("Caminata rápida", durationMinutes = minutesPerSession - 10, notes = "Ritmo en el que puedas hablar"),
                ExerciseItem("Intervalos de 1 min rápido / 2 min suave", durationMinutes = 10, notes = "Repite 4 veces")
            )
        )
        val mobility = TrainingDay(
            day = "VIERNES",
            focus = "Movilidad + core",
            totalMinutes = (minutesPerSession - 10).coerceAtLeast(15),
            intensity = "Baja",
            exercises = listOf(
                ExerciseItem("Gato-camello", 2, "10", 0, 30),
                ExerciseItem("Zancada con giro", 2, "8 por lado", 0, 40),
                ExerciseItem("Elevación de piernas tumbado", 3, "12", 0, 40),
                ExerciseItem("Estiramiento de cadena posterior", 1, "", 3, 0)
            )
        )

        val allDays = mutableListOf<TrainingDay>()
        allDays += strength.copy(day = "LUNES")
        allDays += TrainingDay("MARTES", "Descanso activo", 20, "Baja", listOf(ExerciseItem("Paseo tranquilo", durationMinutes = 20)))
        allDays += cardio.copy(day = "MI�?RCOLES")
        allDays += if (daysPerWeek >= 4) TrainingDay(
            "JUEVES", "Fuerza tren inferior", minutesPerSession, "Media",
            listOf(
                ExerciseItem("Zancadas alternas", 3, "10 por pierna", 0, 45),
                ExerciseItem("Peso muerto a una pierna", 3, "10 por lado", 0, 45),
                ExerciseItem("Elevación de talones", 3, "15", 0, 30)
            )
        ) else TrainingDay("JUEVES", "Descanso", 0, "Baja", emptyList())
        allDays += mobility.copy(day = "VIERNES")
        allDays += if (daysPerWeek >= 5 || wantsMuscle) TrainingDay(
            "SÁBADO", "Fuerza tren superior", minutesPerSession, "Media",
            listOf(
                ExerciseItem("Flexiones declinadas", 3, "8-10", 0, 60),
                ExerciseItem("Remo con mochila o banda", 3, "12", 0, 60),
                ExerciseItem("Fondos en silla", 3, "10", 0, 45),
                ExerciseItem("Superman", 3, "12", 0, 30)
            )
        ) else TrainingDay("SÁBADO", "Descanso activo", 25, "Baja", listOf(ExerciseItem("Caminata larga", durationMinutes = 25)))
        allDays += TrainingDay(
            "DOMINGO", if (lowSleep) "Descanso total + relajación" else "Descanso y estiramientos",
            15, "Baja", listOf(ExerciseItem("Estiramientos suaves + respiración 4-7-8", durationMinutes = 15))
        )

        val rationale = buildString {
            append("Plan ajustado a una media de $avgSteps pasos/día")
            if (avgSleep > 0) append(" y ${fmt(avgSleep)} h de sueño")
            append(". ")
            append(
                if (lowSleep) "Como tu descanso está bajo, se reduce volumen y se prioriza la recuperación."
                else "Como tu descanso es correcto, se puede subir intensidad de forma progresiva."
            )
        }

        return TrainingPlan(
            id = UUID.randomUUID().toString(),
            title = if (wantsMuscle) "Panda Fuerza ${daysPerWeek} días" else "Panda Activo ${daysPerWeek} días",
            goal = if (wantsMuscle) "Ganar fuerza y masa muscular" else "Moverse más y dormir mejor",
            daysPerWeek = daysPerWeek,
            weeklyMinutes = allDays.sumOf { it.totalMinutes },
            level = level,
            rationale = rationale,
            days = allDays,
            source = "local"
        )
    }

    // ========================================================================
    //  3. RESPUESTAS LOCALES DEL CHAT (texto markdown sencillo)
    // ========================================================================
    /** Respuesta del motor local cuando no hay Gemini disponible. */
    fun localAnswer(
        intent: AiIntent?,
        message: String,
        report: HealthReport,
        today: DailyMetrics
    ): String = when (intent) {
        AiIntent.PROGRESS, AiIntent.HEART -> localReportText(report, today)
        AiIntent.SLEEP -> localSleepText(today)
        AiIntent.NUTRITION -> localNutritionText(today)
        AiIntent.MOTIVATION -> localMotivationText(report, today)
        AiIntent.TRAINING_PLAN -> "Pulsa en \"Plan\" y te genero el plan con tus datos."
        null -> charlaLocal(message, today, java.time.LocalTime.now().hour)
            ?: if (message.contains("plan", ignoreCase = true)) {
                "Claro, pulsa en \"Plan\" y te lo armo con tus datos de esta semana."
            } else {
                localReportText(report, today)
            }
    }

    /**
     * Respuestas de charla del motor local (sin IA): saludos, gracias, "como
     * estas", despedidas o desanimo. Devuelve null si el mensaje no es charla.
     * Antes un simple "hola" devolvia el informe entero de datos.
     */
    fun charlaLocal(message: String, today: DailyMetrics, hora: Int): String? {
        val t = java.text.Normalizer.normalize(message.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        fun tiene(vararg p: String) = p.any { Regex("\\b$it\\b").containsMatchIn(t) }
        val corto = t.split(" ").size <= 6
        val faltan = (today.stepGoal - today.steps).coerceAtLeast(0)
        val saludoHora = when (hora) {
            in 5..11 -> "Buenos dias"
            in 12..19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        val gancho = when {
            today.steps <= 0 -> "¿Que te apetece hacer hoy?"
            faltan == 0 -> "Por cierto, ya cumpliste tu meta de pasos hoy. ¡Bien hecho! ¿En que te ayudo?"
            hora >= 20 -> "Llevas ${today.steps} pasos hoy. ¿Que tal el dia?"
            else -> "Llevas ${today.steps} pasos; te faltan unos $faltan para la meta. ¿Te animas a un paseo?"
        }
        return when {
            // "Buenas noches" a partir de las 21 h es despedida, no saludo.
            corto && hora >= 21 && tiene("buenas noches") ->
                "Buenas noches 🌙 Que descanses; mañana seguimos."
            corto && tiene("hola", "buenas", "buenos dias", "buenas tardes", "buenas noches", "hey", "que tal", "saludos") ->
                "$saludoHora 🐼 $gancho"
            tiene("como estas", "como andas", "como te va", "que haces") ->
                "Aqui, con ganas de verte moverte un poco. 😄 ¿Y tu, como te sientes hoy?"
            corto && tiene("gracias", "muchas gracias", "te lo agradezco", "genial gracias") ->
                "¡A ti! Para eso estoy. Si necesitas algo mas, aqui me tienes."
            corto && tiene("adios", "chao", "hasta luego", "nos vemos", "buenas noches panda") ->
                if (hora >= 20) "Que descanses. Mañana seguimos. 🌙" else "¡Hasta luego! Sigue sumando pasos."
            tiene("cansado", "cansada", "agotado", "agotada", "sin ganas", "desanimado", "desanimada", "triste", "estresado", "estresada") ->
                "Te entiendo, hay dias asi y no pasa nada. Hoy no hace falta darlo todo: " +
                    "un paseo tranquilo de 10 minutos o unos estiramientos ya cuentan. " +
                    "Y si puedes, acuestate un poco antes esta noche. ¿Que crees que te ha dejado asi?"
            corto && tiene("ok", "vale", "perfecto", "listo", "entendido", "dale") ->
                "Perfecto. Cuando quieras seguimos."
            else -> null
        }
    }

    private fun localReportText(report: HealthReport, today: DailyMetrics): String = buildString {
        appendLine("Te cuento como vas hoy:")
        appendLine()
        appendLine("- Puntuacion: **${report.score}/100**")
        appendLine("- Pasos: **${today.steps}** de ${today.stepGoal}")
        appendLine("- Sueño: **${fmt(today.sleepHours)} h** (calidad ${today.sleepQuality}/100)")
        if (today.avgHeartRate > 0) appendLine("- Pulso medio: **${today.avgHeartRate} ppm**")
        if (today.spo2 > 0) appendLine("- Oxigeno: **${today.spo2}%**")
        appendLine()
        appendLine("Lo que yo miraria:")
        report.insights.take(3).forEach { insight ->
            appendLine("- **${insight.title}**: ${insight.detail}")
            if (insight.recommendation.isNotBlank()) appendLine("  - ${insight.recommendation}")
        }
        appendLine()
        appendLine("¿Quieres que profundicemos en alguno de estos puntos?")
    }

    private fun localSleepText(today: DailyMetrics): String = buildString {
        appendLine("**PLAN DE RELAJACION PARA HOY** (motor local)")
        appendLine("- 2 h antes de dormir: baja luces y evita cafeina.")
        appendLine("- 1 h antes: deja el movil fuera de la cama.")
        appendLine("- 30 min antes: respiracion 4-7-8 durante 5 minutos (inhala 4 s, reten 7 s, suelta 8 s).")
        appendLine("- 15 min antes: estiramientos suaves de espalda y piernas.")
        appendLine("- Hora objetivo: acostarte siempre a la misma hora, incluso el fin de semana.")
        if (today.sleepHours > 0) {
            appendLine()
            appendLine("Dormiste **${fmt(today.sleepHours)} h**. Con pequeños ajustes de rutina se gana 30-45 minutos por noche.")
        }
    }

    private fun localNutritionText(today: DailyMetrics): String = buildString {
        appendLine("**IDEAS SENCILLAS SEGUN TU ACTIVIDAD DE HOY** (${today.caloriesKcal} kcal activas)")
        appendLine("- Desayuno: avena con fruta y yogur natural.")
        appendLine("- Comida: proteina (pollo, huevo, legumbre) + verdura + arroz o patata.")
        appendLine("- Snack: fruta + un punado de frutos secos.")
        appendLine("- Cena: ligera, tipo tortilla con ensalada.")
        appendLine()
        appendLine("Bebe agua a intervalos regulares: es la mejora mas barata de rendimiento.")
    }

    private fun localMotivationText(report: HealthReport, today: DailyMetrics): String = buildString {
        val missing = (today.stepGoal - today.steps).coerceAtLeast(0)
        appendLine("**RETO DE HOY**")
        appendLine(
            if (missing > 0) "Te quedan **$missing pasos** para tu meta. Con 2 paseos de 10 minutos la cierras."
            else "Ya superaste tu meta de **${today.stepGoal} pasos**. Ahora suma 10 minutos de movilidad."
        )
        appendLine()
        appendLine("Puntuacion de hoy: **${report.score}/100**. Mañana lo importante no es empezar de cero, es no parar.")
    }

    // ========================================================================
    //  UTILIDADES
    // ========================================================================
    private fun fmt(value: Double): String = ((value * 10).toInt() / 10.0).toString()

    private fun fmt(value: Float): String = ((value * 10).toInt() / 10.0).toString()

    private fun minutesForRemaining(today: DailyMetrics): Int {
        val remaining = (today.stepGoal - today.steps).coerceAtLeast(0)
        return (remaining / 110).coerceIn(5, 45)   // ~110 pasos por minuto
    }

    private fun dayProgress(today: DailyMetrics): Float =
        if (today.stepGoal <= 0) 0f else (today.steps.toFloat() / today.stepGoal).coerceIn(0f, 1f)
}

// ============================================================================
//  PARSER DEL PLAN DE GEMINI -> TrainingPlan
// ============================================================================
/**
 * Convierte el texto markdown que devuelve Gemini (formato de PromptBuilder) en
 * un [TrainingPlan] mostrable. Si algo falta, se rellena con valores sensatos;
 * si no se reconoce ningun dia, se devuelve null y se usa el plan local.
 */
object TrainingPlanParser {

    private val WEEK_DAYS = listOf(
        "LUNES", "MARTES", "MIERCOLES", "MI�?RCOLES", "JUEVES", "VIERNES", "SABADO", "SÁBADO", "DOMINGO"
    )

    fun parse(text: String): TrainingPlan? {
        val clean = text.replace("*", "").replace("```", "")
        val days = WEEK_DAYS.mapNotNull { day -> parseDay(clean, day) }
        if (days.isEmpty()) return null

        val title = Regex("PLAN\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.take(80)
            ?: "Plan de entrenamiento Panda Fit"
        val goal = Regex("OBJETIVO\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim() ?: "Mejorar forma fisica"
        val level = Regex("NIVEL\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.split(" ")?.firstOrNull() ?: "Intermedio"
        val daysPerWeek = Regex("DIAS_SEMANA\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: days.count { !it.focus.contains("descanso", true) }.coerceAtLeast(1)
        val weeklyMinutes = Regex("MINUTOS_SEMANA\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: days.sumOf { it.totalMinutes }
        val rationale = Regex("MOTIVO\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()
            ?: "Generado por el Asistente IA Fitness con tus datos agregados."

        return TrainingPlan(
            id = UUID.randomUUID().toString(),
            title = title,
            goal = goal,
            daysPerWeek = daysPerWeek.coerceIn(1, 7),
            weeklyMinutes = weeklyMinutes,
            level = level,
            rationale = rationale,
            days = days,
            source = "gemini"
        )
    }

    private fun parseDay(text: String, day: String): TrainingDay? {
        // Se normalizan los acentos del nombre del dia (MIERCOLES / MIÉRCOLES).
        val normalized = day.replace("Í", "I").replace("Á", "A")
        // El separador puede ser ":" o "|" y admite espacios antes.
        val regex = Regex(
            "^\\s*$normalized\\s*[:|]\\s*(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val line = regex.find(text)?.groupValues?.get(1)?.trim() ?: return null

        val parts = line.split("|").map { it.trim() }
        val focus = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Entrenamiento"
        val minutes = parts.getOrNull(1)?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val exercisesRaw = parts.drop(2).joinToString("|")

        val exercises = exercisesRaw
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseExercise(it) }

        val isRest = focus.contains("descanso", true) && exercises.isEmpty()
        return TrainingDay(
            day = day,
            focus = focus,
            totalMinutes = if (isRest) 0 else minutes.coerceIn(0, 180),
            intensity = when {
                isRest -> "Baja"
                focus.contains("alta", true) || focus.contains("hiit", true) -> "Alta"
                focus.contains("suave", true) || focus.contains("movilidad", true) -> "Baja"
                else -> "Media"
            },
            exercises = exercises
        )
    }

    /**
     * "Sentadillas: 3x12"  ->  sets=3, reps="12"
     * "Caminata: 20 minutos" -> durationMinutes=20
     */
    private fun parseExercise(raw: String): ExerciseItem {
        val name = raw.substringBefore(":").trim().ifBlank { raw.take(40) }
        val detail = raw.substringAfter(":", "").trim()

        Regex("(\\d+)\\s*[xX�-]\\s*(\\d+[\\-\\d]*)").find(detail)?.let { match ->
            return ExerciseItem(
                name = name,
                sets = match.groupValues[1].toIntOrNull() ?: 0,
                reps = match.groupValues[2],
                durationMinutes = 0,
                restSeconds = 45
            )
        }
        Regex("(\\d+)\\s*(?:min|minutos)", RegexOption.IGNORE_CASE).find(detail)?.let { match ->
            return ExerciseItem(
                name = name,
                durationMinutes = match.groupValues[1].toIntOrNull() ?: 0,
                notes = if (detail.length > match.value.length) detail else ""
            )
        }
        Regex("(\\d+)\\s*(?:seg|segundos)", RegexOption.IGNORE_CASE).find(detail)?.let { match ->
            return ExerciseItem(
                name = name,
                sets = 1,
                durationMinutes = 0,
                notes = "${match.groupValues[1]} segundos"
            )
        }
        return ExerciseItem(name = name, notes = detail)
    }
}
