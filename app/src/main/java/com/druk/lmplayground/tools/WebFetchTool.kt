package com.druk.lmplayground.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val description = "Fetch a web page and return its text content"
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch"},"max_length":{"type":"integer","description":"Maximum content length in characters (default 5000)"}},"required":["url"]}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val url = args.getString("url").let {
                if (!it.startsWith("http")) "https://$it" else it
            }
            val maxLength = args.optInt("max_length", 5000).coerceIn(50, 20000)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val contentType = response.header("Content-Type") ?: ""
            val body = response.body?.string() ?: return errorJson("Empty response")

            val result = JSONObject()
            result.put("url", url)

            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                val doc = Jsoup.parse(body)
                // Remove script, style, nav, footer elements for cleaner text
                doc.select("script, style, nav, footer, header, aside, .ad, .ads, .advertisement").remove()
                val title = doc.title()
                val text = doc.body()?.text() ?: ""
                val truncated = if (text.length > maxLength) text.substring(0, maxLength) + "..." else text

                result.put("title", title)
                result.put("content", truncated)
                result.put("length", truncated.length)
            } else {
                // Non-HTML content — return raw text truncated
                val truncated = if (body.length > maxLength) body.substring(0, maxLength) + "..." else body
                result.put("title", "")
                result.put("content", truncated)
                result.put("length", truncated.length)
            }

            result.toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "Fetch failed")
        }
    }

    private fun errorJson(message: String): String {
        return """{"error":"${message.replace("\"", "'")}"}"""
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
