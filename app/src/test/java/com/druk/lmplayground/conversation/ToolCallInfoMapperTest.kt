package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolCallInfoMapperTest {

    @Test
    fun mapsCallsToResultsById() {
        val calls = """[
            {"id": "1", "name": "web_search", "arguments": "{\"query\":\"a\"}"},
            {"id": "2", "name": "fetch_page", "arguments": "{\"url\":\"b\"}"}
        ]"""
        val results = """[
            {"id": "2", "content": "page body"},
            {"id": "1", "content": "search hits"}
        ]"""
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, 100)
        assertEquals(2, infos.size)
        assertEquals("web_search", infos[0].name)
        assertEquals("search hits", infos[0].result)
        assertEquals("fetch_page", infos[1].name)
        assertEquals("page body", infos[1].result)
    }

    @Test
    fun missingResultYieldsEmptyString() {
        val calls = """[{"id": "1", "name": "web_search", "arguments": "{}"}]"""
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, "[]", 50)
        assertEquals(1, infos.size)
        assertEquals("", infos[0].result)
    }

    @Test
    fun emptyCallsYieldEmptyListWithoutDividingByZero() {
        val infos = ToolCallInfoMapper.buildToolCallInfoList("[]", "[]", 1000)
        assertTrue(infos.isEmpty())
    }

    @Test
    fun splitsDurationEvenlyAcrossCalls() {
        val calls = """[
            {"id": "1", "name": "a", "arguments": "{}"},
            {"id": "2", "name": "b", "arguments": "{}"}
        ]"""
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, "[]", 1001)
        assertEquals(500, infos[0].durationMs)
        assertEquals(500, infos[1].durationMs)
    }
}
