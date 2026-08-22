package com.druk.lmplayground.api;

/**
 * Streaming sink, mirroring the shape of an OpenAI SSE stream so a client
 * migrating off an HTTP server changes only the transport.
 *
 * `oneway` on the INTERFACE (rather than per-method) is load-bearing: a
 * two-way callback would block LM Playground's generation coroutine on a round
 * trip to the client for EVERY token, and a client that ANRs would stall the
 * engine. The internal ILlamaGenerationCallback is oneway for the same reason.
 *
 * Ordering: zero or more onChunk, then exactly one of onComplete / onError.
 * oneway calls issued from a single thread to a single binder are delivered in
 * order, so the terminal callback never overtakes a chunk.
 *
 * Note that `oneway` transactions share a ~1 MB per-process async buffer. The
 * service coalesces chunks to stay well inside it; clients should return from
 * these methods promptly and do their own work elsewhere.
 */
oneway interface IChatCompletionCallback {

    /** One `chat.completion.chunk` object as JSON — no "data: " prefix, no "[DONE]". */
    void onChunk(String requestId, String chunkJson);

    /** Terminal success: the full `chat.completion` object as JSON. */
    void onComplete(String requestId, String completionJson);

    /** Terminal failure: an OpenAI-shaped error envelope as JSON. */
    void onError(String requestId, String errorJson);
}
