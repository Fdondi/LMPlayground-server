package com.druk.lmplayground.api

/**
 * Constants that define the public API surface. Stable across releases —
 * changing anything here is a breaking change for already-shipped clients.
 */
object LmPlaygroundApi {

    /**
     * The intent action clients bind to.
     *
     * Deliberately NOT `${applicationId}`-scoped: LM Playground's debug build
     * uses `applicationIdSuffix = ".debug"`, so a client that keys off the
     * package name silently fails against a debug install. Clients must
     * discover the service by resolving this action (see
     * [LmPlaygroundClient.discover]) rather than hardcoding a package.
     */
    const val ACTION_BIND = "com.druk.lmplayground.api.BIND_CHAT_SERVICE"

    /** Release package name — used only for the Play Store deep link. */
    const val PLAY_STORE_PACKAGE = "com.druk.lmplayground"

    /** The wire-protocol version this SDK implements. */
    const val API_VERSION = 1

    // ── Feature flags reported by getServiceInfo().features ──────────────

    const val FEATURE_CHAT_STREAM = "chat.stream"
    const val FEATURE_CHAT_TOOLS = "chat.tools"
    const val FEATURE_CHAT_VISION = "chat.vision"
    const val FEATURE_MODELS_LIST = "models.list"
    const val FEATURE_BLOBS = "blobs"

    /** Prefix of the opaque handle returned by `putBlob`. */
    const val BLOB_URL_PREFIX = "lmp-blob:"
}

/**
 * Payload ceilings, enforced on both sides of the boundary.
 *
 * [MAX_REQUEST_BYTES] intentionally duplicates
 * `com.druk.llamacpp.InferenceLimits.MAX_PAYLOAD_BYTES` — this module cannot
 * depend on `:app`. `ApiLimitsParityTest` in `:app` pins the two together so
 * they cannot drift.
 */
object ApiLimits {

    /**
     * Maximum size of any single string crossing the binder, measured as
     * `length * 2` because `Parcel.writeString` writes UTF-16.
     *
     * Budget arithmetic for inline images: the whole request JSON crosses as
     * one string, so 700 KB ⇒ ≤ 358 400 chars of JSON ⇒ ≤ ~262 KB of raw image
     * bytes once base64's 4/3 inflation is accounted for. Anything larger must
     * go through `putBlob`.
     */
    const val MAX_REQUEST_BYTES = 700 * 1024

    /** Maximum size of a single `putBlob` payload. */
    const val MAX_BLOB_BYTES = 20L * 1024 * 1024

    /** Default context size for an API session. See PROTOCOL.md §lmp.context_size. */
    const val DEFAULT_CONTEXT_SIZE = 4096

    /** Default wall-clock cap on a single request. */
    const val DEFAULT_TIMEOUT_MS = 300_000L

    /** UTF-16 byte cost of [text], matching `Parcel.writeString`. */
    fun byteCost(text: String): Int = text.length * 2

    fun exceedsRequestBudget(text: String): Boolean = byteCost(text) > MAX_REQUEST_BYTES
}
