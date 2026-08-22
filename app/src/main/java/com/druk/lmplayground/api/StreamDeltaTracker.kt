package com.druk.lmplayground.api

import com.druk.lmplayground.conversation.ResponseProcessor
import com.druk.lmplayground.conversation.splitThinking

/**
 * Turns the engine's *accumulated-string* callback into the *incremental*
 * deltas the OpenAI streaming protocol wants, split into a reasoning channel
 * and a visible-content channel.
 *
 * `LlamaGenerationCallback.onFullResponse` hands us the whole response so far
 * on every token, so the naive delta is `full.substring(previous.length)`. Two
 * things break that:
 *
 * 1. **The text is post-processed, and post-processing rewrites.**
 *    [ResponseProcessor.removeThinkingSeparator] replaces a leading run of
 *    `[-—_]{2,}` after `</think>` with `"\n\n"`. As tokens arrive the visible
 *    content therefore goes `"-"` → `"\n\n"` → `"\n\nHello"` — the second step
 *    is not an append, and a client that concatenated `"-"` already has a
 *    character that no longer exists in the final text.
 *
 * 2. **`splitThinking` trims.** Trimming a growing string is append-only in the
 *    normal case, but combined with (1) the first few characters of the content
 *    channel are genuinely unstable.
 *
 * The fix is to hold the first [UNSTABLE_PREFIX_LEN] characters of the content
 * channel back until enough has arrived for the rewrite to have settled — the
 * separator regex only ever fires at the very start of the content, and once it
 * has fired the result (`"\n\n"`) is stable no matter how many more separator
 * characters the model emits. [flush] releases whatever is still held when
 * generation ends, and the authoritative full text always goes out in
 * `onComplete` regardless.
 *
 * Pure — no Android, no engine. The whole state machine is a plain unit test.
 */
class StreamDeltaTracker {

    private var emittedReasoning = ""
    private var emittedContent = ""
    private var latestReasoning = ""
    private var latestContent = ""

    /** Non-empty deltas produced by one [update]. Either may be null. */
    data class Delta(val reasoning: String?, val content: String?) {
        val isEmpty: Boolean get() = reasoning == null && content == null
    }

    /** Everything seen so far, post-processed — what `onComplete` reports. */
    val fullReasoning: String get() = latestReasoning
    val fullContent: String get() = latestContent

    /** Approximate: counts characters, since the engine doesn't expose tokens. */
    var tokenCount: Int = 0
        private set

    /**
     * Feed one raw accumulated response from the engine.
     *
     * @param raw the string handed to `LlamaGenerationCallback.onFullResponse`
     *        (pre-[ResponseProcessor]).
     */
    fun update(raw: String): Delta {
        tokenCount++
        val split = splitThinking(ResponseProcessor.process(raw))
        latestReasoning = split.thinkingContent
        latestContent = split.responseContent

        // Reasoning is append-only: removeThinkingSeparator only touches text
        // *after* </think>, so nothing rewrites inside the think block.
        val reasoningDelta = appendOnlyDelta(emittedReasoning, latestReasoning)
        if (reasoningDelta != null) emittedReasoning += reasoningDelta

        // Content is held back until the separator rewrite can no longer fire.
        val stableContent = if (latestContent.length >= UNSTABLE_PREFIX_LEN) {
            latestContent
        } else {
            ""
        }
        val contentDelta = appendOnlyDelta(emittedContent, stableContent)
        if (contentDelta != null) emittedContent += contentDelta

        return Delta(reasoningDelta, contentDelta)
    }

    /**
     * Release anything still held back. Call once when generation ends, before
     * building the terminal completion, so a very short response still streams.
     */
    fun flush(): Delta {
        val reasoningDelta = appendOnlyDelta(emittedReasoning, latestReasoning)
        if (reasoningDelta != null) emittedReasoning += reasoningDelta
        val contentDelta = appendOnlyDelta(emittedContent, latestContent)
        if (contentDelta != null) emittedContent += contentDelta
        return Delta(reasoningDelta, contentDelta)
    }

    /**
     * The delta from [emitted] to [latest], or null if there is nothing new.
     *
     * When [latest] is not an extension of [emitted] the text was rewritten
     * behind us. We cannot un-send what the client already has, so we emit only
     * the part beyond what was already sent and let `onComplete` carry the
     * authoritative text. In practice this is unreachable once the hold-back
     * above is in play; it exists so a future post-processing change degrades
     * to a cosmetic artifact rather than a crash or a duplicated tail.
     */
    private fun appendOnlyDelta(emitted: String, latest: String): String? {
        if (latest.length <= emitted.length) return null
        if (latest.startsWith(emitted)) {
            return latest.substring(emitted.length).takeIf { it.isNotEmpty() }
        }
        return latest.substring(emitted.length).takeIf { it.isNotEmpty() }
    }

    private companion object {
        /**
         * Characters of visible content held back before the first emission.
         * `"\n\n"` (the rewritten separator) is 2 characters, so 3 guarantees
         * we never emit a prefix that the rewrite could still invalidate.
         */
        const val UNSTABLE_PREFIX_LEN = 3
    }
}
