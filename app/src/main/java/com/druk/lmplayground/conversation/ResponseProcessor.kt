package com.druk.lmplayground.conversation

/**
 * Processes raw model response text for display in the UI.
 */
object ResponseProcessor {

    private val completePairRegex by lazy {
        Regex("""<think>(.*?)</think>\n?""", RegexOption.DOT_MATCHES_ALL)
    }

    /**
     * Process raw model response: clean up thinking/response separators.
     */
    fun process(raw: String): String {
        return removeThinkingSeparator(raw)
    }

    /**
     * When thinking is enabled, assume the model starts with thinking content.
     * Prepend <think> if missing so the UI shows the "Thinking..." indicator
     * immediately, not only after </think> arrives.
     */
    fun ensureThinkingTag(text: String): String {
        if (!text.startsWith("<think>")) {
            return "<think>$text"
        }
        return text
    }

    /**
     * When thinking is disabled, strip only complete <think>...</think> pairs.
     * Unclosed <think> tags are left as-is since they likely contain the
     * actual response from a model that ignores the disable instruction.
     */
    fun stripCompleteThinkBlocks(text: String): String {
        return completePairRegex.replace(text, "").trim()
    }

    /**
     * Remove separator lines (e.g. "---", "———") that some models generate
     * between the </think> block and the actual response.
     */
    fun removeThinkingSeparator(text: String): String {
        val closeIdx = text.indexOf("</think>")
        if (closeIdx == -1) return text
        val afterThink = closeIdx + "</think>".length
        val rest = text.substring(afterThink)
        val cleaned = rest.replaceFirst(Regex("""^\s*[-—_]{2,}\s*"""), "\n\n")
        return text.substring(0, afterThink) + cleaned
    }
}
