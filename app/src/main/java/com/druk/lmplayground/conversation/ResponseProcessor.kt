package com.druk.lmplayground.conversation

/**
 * Processes raw model response text for display in the UI.
 */
object ResponseProcessor {

    /**
     * Process raw model response: strip anti-prompt suffixes and clean up
     * thinking/response separators.
     */
    fun process(raw: String, antiPrompt: Array<String>): String {
        var text = raw
        for (suffix in antiPrompt) {
            text = text.removeSuffix(suffix)
            text = text.removeSuffix(suffix + "\n")
        }
        text = removeThinkingSeparator(text)
        return text
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
