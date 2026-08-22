package com.druk.lmplayground.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The accumulated-string → incremental-delta conversion.
 *
 * The property that matters: **concatenating every emitted delta must equal the
 * final text**. A client builds its bubble by appending, so any non-monotonic
 * emission leaves characters on screen that the model never produced.
 *
 * Robolectric because [StreamDeltaTracker] leans on `splitThinking`, which
 * lives alongside Compose/commonmark code in MessageFormatter.kt.
 */
@RunWith(RobolectricTestRunner::class)
class StreamDeltaTrackerTest {

    /** Feed a growing response and return (concatenated reasoning, content). */
    private fun stream(vararg accumulated: String): Pair<String, String> {
        val tracker = StreamDeltaTracker()
        val reasoning = StringBuilder()
        val content = StringBuilder()
        accumulated.forEach { raw ->
            tracker.update(raw).let {
                it.reasoning?.let(reasoning::append)
                it.content?.let(content::append)
            }
        }
        tracker.flush().let {
            it.reasoning?.let(reasoning::append)
            it.content?.let(content::append)
        }
        return reasoning.toString() to content.toString()
    }

    @Test
    fun plainTextStreamsAsPureAppends() {
        val (reasoning, content) = stream("He", "Hell", "Hello", "Hello wo", "Hello world")
        assertEquals("", reasoning)
        assertEquals("Hello world", content)
    }

    @Test
    fun thinkingIsSplitFromVisibleOutput() {
        val (reasoning, content) = stream(
            "<think>Let me",
            "<think>Let me check</think>",
            "<think>Let me check</think>The answer",
            "<think>Let me check</think>The answer is 42",
        )
        assertEquals("Let me check", reasoning)
        assertEquals("The answer is 42", content)
    }

    /**
     * The reason the hold-back exists.
     *
     * `ResponseProcessor.removeThinkingSeparator` rewrites a leading run of
     * `[-—_]{2,}` after `</think>` into `"\n\n"`. So the visible text goes
     * `"-"` → `"\n\n"` → `"\n\nAnswer"` — the middle step is not an append. A
     * naive `substring(previous.length)` would have already emitted `"-"`,
     * leaving a stray dash in the client's buffer forever.
     */
    @Test
    fun separatorRewriteNeverEmitsCharactersThatVanish() {
        val (_, content) = stream(
            "<think>Reasoning</think>",
            "<think>Reasoning</think>-",
            "<think>Reasoning</think>--",
            "<think>Reasoning</think>---",
            "<think>Reasoning</think>---\nAnswer",
        )
        // The final text after processing has the separator replaced and the
        // response trimStart-ed, so the concatenated stream must equal exactly
        // that — no leading dash.
        assertEquals("Answer", content)
    }

    @Test
    fun concatenatedDeltasAlwaysEqualTheFinalText() {
        val tracker = StreamDeltaTracker()
        val content = StringBuilder()
        val steps = listOf(
            "<think>a", "<think>ab</think>", "<think>ab</think>-", "<think>ab</think>--",
            "<think>ab</think>--x", "<think>ab</think>--xy", "<think>ab</think>--xyz",
        )
        steps.forEach { raw -> tracker.update(raw).content?.let(content::append) }
        tracker.flush().content?.let(content::append)
        assertEquals(tracker.fullContent, content.toString())
    }

    @Test
    fun shortResponseIsReleasedByFlush() {
        // Under the hold-back threshold, so nothing streams until flush —
        // which is exactly why flush is not optional.
        val tracker = StreamDeltaTracker()
        assertNull(tracker.update("ok").content)
        assertEquals("ok", tracker.flush().content)
    }

    @Test
    fun repeatedUpdatesWithNoNewTextEmitNothing() {
        val tracker = StreamDeltaTracker()
        tracker.update("Hello there")
        val second = tracker.update("Hello there")
        assertNull(second.content)
        assertNull(second.reasoning)
    }

    @Test
    fun reasoningAndContentAdvanceIndependently() {
        val tracker = StreamDeltaTracker()
        val first = tracker.update("<think>thinking hard")
        assertEquals("thinking hard", first.reasoning)
        assertNull(first.content)

        val second = tracker.update("<think>thinking hard</think>Final answer")
        assertNull(second.reasoning)
        assertEquals("Final answer", second.content)
    }
}
