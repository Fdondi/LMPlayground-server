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
    fun `process strips separator`() {
        val raw = "<think>\nOk.\n</think>\n---\nHello!"
        val result = ResponseProcessor.process(raw)
        assertEquals("<think>\nOk.\n</think>\n\nHello!", result)
    }

    @Test
    fun `process with no think tags`() {
        val raw = "Just a plain response."
        val result = ResponseProcessor.process(raw)
        assertEquals("Just a plain response.", result)
    }

    // --- ensureThinkingTag ---

    @Test
    fun `ensureThinkingTag prepends when close tag present but no open`() {
        val input = "\nSome thinking\n</think>\n\nHello!"
        assertEquals("<think>\nSome thinking\n</think>\n\nHello!", ResponseProcessor.ensureThinkingTag(input))
    }

    @Test
    fun `ensureThinkingTag leaves alone when both tags present`() {
        val input = "<think>\nSome thinking\n</think>\n\nHello!"
        assertEquals(input, ResponseProcessor.ensureThinkingTag(input))
    }

    @Test
    fun `ensureThinkingTag prepends during streaming before close tag arrives`() {
        val input = "Okay let me think about this..."
        assertEquals("<think>Okay let me think about this...", ResponseProcessor.ensureThinkingTag(input))
    }

    // --- stripCompleteThinkBlocks ---

    @Test
    fun `stripCompleteThinkBlocks removes complete pair`() {
        val input = "<think>\nSome thinking\n</think>\n\nHello!"
        assertEquals("Hello!", ResponseProcessor.stripCompleteThinkBlocks(input))
    }

    @Test
    fun `stripCompleteThinkBlocks keeps unclosed think tag content`() {
        val input = "<think>Hello! How can I help?"
        assertEquals("<think>Hello! How can I help?", ResponseProcessor.stripCompleteThinkBlocks(input))
    }

    @Test
    fun `stripCompleteThinkBlocks with no tags returns input`() {
        val input = "Hello!"
        assertEquals("Hello!", ResponseProcessor.stripCompleteThinkBlocks(input))
    }
}
