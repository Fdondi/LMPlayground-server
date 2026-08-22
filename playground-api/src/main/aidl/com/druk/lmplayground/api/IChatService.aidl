package com.druk.lmplayground.api;

import com.druk.lmplayground.api.IChatCompletionCallback;
import android.os.ParcelFileDescriptor;

/**
 * LM Playground public inference API.
 *
 * ┌─ WIRE CONTRACT — READ BEFORE EDITING ────────────────────────────────┐
 * │ Binder assigns transaction codes by DECLARATION ORDER               │
 * │ (FIRST_CALL_TRANSACTION + index). Third-party clients are compiled  │
 * │ against a snapshot of this file and are NOT recompiled when LM      │
 * │ Playground updates. Therefore:                                      │
 * │                                                                     │
 * │   - NEVER reorder, remove, or change the signature of a method.     │
 * │   - ONLY append new methods at the END.                             │
 * │   - getApiVersion() is transaction 0 forever. It is the only method │
 * │     a client may call without first feature-detecting.              │
 * │                                                                     │
 * │ ApiTransactionOrderTest pins this ordering so an accidental reorder │
 * │ fails CI rather than silently mis-dispatching for older clients.    │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * All payloads are JSON strings shaped like the OpenAI chat-completions API,
 * so a client migrating off an HTTP server changes its transport and keeps its
 * data model. See PROTOCOL.md for the schemas.
 *
 * Every string crossing this boundary must stay under
 * ApiLimits.MAX_REQUEST_BYTES (700 KB, measured as length * 2 because
 * Parcel.writeString is UTF-16).
 */
interface IChatService {

    /** Wire-protocol version. 1 = initial release. Monotonic. */
    int getApiVersion();

    /**
     * JSON: { "api_version": 1, "app_version_name": "...",
     *         "features": ["chat.stream", "chat.tools", "chat.vision",
     *                      "models.list", "blobs"],
     *         "limits": { "max_request_bytes": 716800,
     *                     "max_blob_bytes": 20971520 } }
     *
     * Cheap — never blocks on the engine. Call this right after binding to
     * feature-detect before using anything below.
     */
    String getServiceInfo();

    /**
     * OpenAI-shaped `GET /v1/models` response body, extended with a per-model
     * "lmp" object carrying capabilities (vision / tools / thinking), whether
     * the model is downloaded, and whether it is the one currently loaded.
     */
    String listModels();

    /**
     * Start a chat completion. Returns immediately — generation never runs on
     * the binder thread.
     *
     * Returns an opaque requestId, or "" if the request was rejected
     * synchronously. On synchronous rejection `callback.onError` is ALSO
     * invoked with the error envelope, so clients only need one error path.
     *
     * `requestJson` is an OpenAI chat-completions body plus the "lmp"
     * extension object. Streaming is controlled by "stream": true|false.
     */
    String createChatCompletion(String requestJson, IChatCompletionCallback callback);

    /** Idempotent and best-effort. Safe to call after the request completed. */
    oneway void cancel(String requestId);

    /**
     * Stage a binary out-of-band, bypassing the binder payload cap — file
     * descriptors are not counted against transaction size.
     *
     * Returns an opaque handle "lmp-blob:<uuid>" usable as an image_url in a
     * subsequent createChatCompletion, or "" on rejection. The blob is deleted
     * when the request that consumes it completes, or after 10 minutes,
     * whichever comes first.
     */
    String putBlob(in ParcelFileDescriptor pfd, String mimeType, long sizeBytes);

    // ── APPEND NEW METHODS BELOW THIS LINE ONLY ──
}
