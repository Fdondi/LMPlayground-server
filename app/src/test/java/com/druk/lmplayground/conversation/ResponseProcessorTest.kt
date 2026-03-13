package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseProcessorTest {

    // --- removeThinkingSeparator ---

    @Test
    fun `no think tags - text unchanged`() {
        val input = "Hello! How can I help you?"
        assertEquals(input, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block without separator - preserved`() {
        val input = "<think>\nLet me think about this.\n</think>\nHello!"
        assertEquals(input, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with dashes separator - removed`() {
        val input = "<think>\nLet me think.\n</think>\n---\nHello!"
        val expected = "<think>\nLet me think.\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with long dashes separator - removed`() {
        val input = "<think>\nThinking...\n</think>\n------\nHello!"
        val expected = "<think>\nThinking...\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with em dash separator - removed`() {
        val input = "<think>\nThinking...\n</think>\n———\nHello!"
        val expected = "<think>\nThinking...\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with underscore separator - removed`() {
        val input = "<think>\nThinking...\n</think>\n___\nHello!"
        val expected = "<think>\nThinking...\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with separator and extra whitespace - removed`() {
        val input = "<think>\nThinking...\n</think>\n  ---  \nHello!"
        val expected = "<think>\nThinking...\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with double newlines and separator - removed`() {
        val input = "<think>\nOk.\n</think>\n\n----\n\nHello!"
        val expected = "<think>\nOk.\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `dashes in regular text not inside think - not removed`() {
        val input = "Step 1\n---\nStep 2"
        assertEquals(input, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `empty think block with separator - removed`() {
        val input = "<think>\n\n</think>\n---\nHello!"
        val expected = "<think>\n\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    @Test
    fun `think block with two dash separator - removed`() {
        val input = "<think>\nOk.\n</think>\n--\nHello!"
        val expected = "<think>\nOk.\n</think>\n\nHello!"
        assertEquals(expected, ResponseProcessor.removeThinkingSeparator(input))
    }

    // --- process (full pipeline) ---

    @Test
    fun `process strips anti-prompt suffix`() {
        val raw = "Hello!<|im_end|>"
        val result = ResponseProcessor.process(raw, arrayOf("<|im_end|>"))
        assertEquals("Hello!", result)
    }

    @Test
    fun `process strips anti-prompt suffix with newline`() {
        val raw = "Hello!<|im_end|>\n"
        val result = ResponseProcessor.process(raw, arrayOf("<|im_end|>"))
        assertEquals("Hello!", result)
    }

    @Test
    fun `process strips separator and anti-prompt`() {
        val raw = "<think>\nOk.\n</think>\n---\nHello!<|im_end|>"
        val result = ResponseProcessor.process(raw, arrayOf("<|im_end|>"))
        assertEquals("<think>\nOk.\n</think>\n\nHello!", result)
    }

    @Test
    fun `process with empty anti-prompt array`() {
        val raw = "<think>\nOk.\n</think>\n---\nHello!"
        val result = ResponseProcessor.process(raw, emptyArray())
        assertEquals("<think>\nOk.\n</think>\n\nHello!", result)
    }

    @Test
    fun `process with no think tags and no anti-prompt`() {
        val raw = "Just a plain response."
        val result = ResponseProcessor.process(raw, emptyArray())
        assertEquals("Just a plain response.", result)
    }
}
