// ============================================================================
//  Panda Fit - ModelosChat.kt
//  0.0.8: de la lista de modelos que devuelve cada proveedor, deja solo los
//  que sirven para CHATEAR (fuera voz, imagen, embeddings, transcripcion...),
//  ordenados con los mas utiles primero. Funcion pura (se prueba sin red).
// ============================================================================
package com.pandafit.app.ai

object ModelosChat {

    private val FUERA = listOf(
        "tts", "audio", "image", "imagen", "embedding", "embed", "vision-preview", "transcribe",
        "whisper", "dall-e", "realtime", "moderation", "search", "aqa", "veo", "live",
        "native-audio", "learnlm", "computer-use", "babbage", "davinci", "codex", "instruct",
        "cyber", "rosalind", "translate", "-vision-exp"
    )

    fun filtrar(proveedor: AiProvider, ids: List<String>): List<String> {
        val chat = ids.map { it.removePrefix("models/").trim() }.filter { it.isNotEmpty() }.distinct().filter { id ->
            val m = id.lowercase()
            // 0.0.10: los retirados no se ofrecen (aunque la cuenta aun los liste).
            id !in AiProvider.RETIRADOS && FUERA.none { m.contains(it) } && when (proveedor) {
                AiProvider.GEMINI -> m.startsWith("gemini") || m.startsWith("gemma")
                AiProvider.OPENAI -> m.startsWith("gpt-") || m.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(m)
                AiProvider.DEEPSEEK -> m.startsWith("deepseek")
                AiProvider.CLAUDE -> m.startsWith("claude")
            }
        }
        // Primero los sugeridos (en su orden), luego el resto: los mas nuevos arriba.
        val sugeridos = proveedor.suggestedModels.filter { it in chat }
        val resto = (chat - sugeridos.toSet()).sortedWith(compareByDescending<String> { version(it) }.thenBy { it })
        return sugeridos + resto
    }

    /** Numero de version aproximado ("gemini-2.5-pro" -> 2.5) para ordenar. */
    private fun version(id: String): Double =
        Regex("(\\d+(?:\\.\\d+)?)").find(id)?.value?.toDoubleOrNull() ?: 0.0

    /** Descripcion corta para el selector. */
    fun descripcion(id: String): String {
        val m = id.lowercase()
        AiProvider.RETIRADOS[id]?.let { return "Retirado: usa $it" }
        return when {
            // 0.0.10: nombres oficiales actuales.
            m == "gemini-3.8-flash" -> "Rápido y listo (recomendado)"
            m.startsWith("gemini-4") -> "Nueva generación Gemini 4"
            m.contains("astra") -> "El más capaz de OpenAI, más lento"
            m.contains("-sol") -> "Equilibrado, el de diario (recomendado)"
            m.contains("terra") -> "Rápido y eficiente"
            m.contains("luna") -> "Muy rápido y económico"
            m == "deepseek-flash" -> "Rápido y listo (recomendado)"
            m == "deepseek-v4-pro" -> "Más inteligente, algo más lento"
            m.contains("fable") -> "Nueva generación Claude"
            // "mini" como palabra suelta: "gemini" tambien contiene "mini".
            m.contains("lite") || Regex("(^|-)mini(-|$)").containsMatchIn(m) || m.contains("haiku") || m.contains("nano") -> "Rápido y económico"
            m.contains("flash") -> "Rápido y listo (recomendado)"
            m.contains("pro") || m.contains("opus") || m.contains("reasoner") || Regex("^o\\d").containsMatchIn(m) -> "Más inteligente, algo más lento"
            m.contains("sonnet") || m.startsWith("gpt-4o") || m.startsWith("gpt-4.1") || m.startsWith("gpt-5") -> "Muy completo"
            m.contains("deepseek-chat") -> "Equilibrado"
            m.contains("gemma") -> "Abierto y ligero"
            else -> ""
        }
    }
}
