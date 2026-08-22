package com.druk.lmplayground.api

/**
 * Where a request's output goes.
 *
 * This is the seam that keeps [ChatCompletionHandler] transport-agnostic. The
 * binder implementation ([BinderResponseSink]) forwards to an
 * `IChatCompletionCallback`; a loopback HTTP server would implement the same
 * three methods and write `data: <json>\n\n` instead. Because the payloads are
 * already OpenAI-shaped JSON strings, adding that transport needs no changes to
 * the engine, policy, or codec layers.
 *
 * Contract: zero or more [chunk] calls, then exactly one of [complete] or
 * [error]. Implementations must tolerate a terminal call arriving after they
 * have already been torn down.
 */
interface ResponseSink {

    /**
     * A streamed delta, as *semantics* rather than serialized JSON.
     *
     * This is the method the handler calls, and it exists so a transport can
     * batch. The binder implementation must: `oneway` transactions share a
     * ~1 MB per-process async buffer, and one call per token at 40 tok/s
     * overflows it. An HTTP/SSE transport would not need to, and can keep this
     * default.
     */
    fun delta(model: String, reasoning: String?, content: String?) {
        if (reasoning == null && content == null) return
        chunk(com.druk.lmplayground.api.json.ResponseCodec.encodeChunk(
            com.druk.lmplayground.api.model.ChatCompletionChunk(
                id = requestId, model = model,
                contentDelta = content, reasoningDelta = reasoning,
            )
        ))
    }

    /** Id of the request this sink belongs to, stamped onto every chunk. */
    val requestId: String

    /** One `chat.completion.chunk` object, already serialized. */
    fun chunk(json: String)

    /** Terminal success: a `chat.completion` object, already serialized. */
    fun complete(json: String)

    /** Terminal failure: an error envelope, already serialized. */
    fun error(json: String)
}
