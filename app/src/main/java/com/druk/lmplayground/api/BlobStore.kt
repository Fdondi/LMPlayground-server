package com.druk.lmplayground.api

import android.app.Application
import android.util.Base64
import android.util.Log
import com.druk.lmplayground.api.LmPlaygroundApi.BLOB_URL_PREFIX
import com.druk.lmplayground.conversation.ChatImageStore
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds images staged by clients until the request that uses them completes.
 *
 * Images can reach us two ways, and both end up here:
 *
 * - **Inline `data:` URL**, matching OpenAI exactly. Simple, but the whole
 *   request crosses the binder as one string, so after base64's 4/3 inflation
 *   and the UTF-16 doubling that `Parcel.writeString` applies, only ~262 KB of
 *   raw image fits inside the 700 KB budget.
 * - **`putBlob(pfd, …)`**, which sends a file descriptor instead. FDs are not
 *   counted against transaction size, so this bypasses the cap entirely — it is
 *   the right answer for anything a camera produced.
 *
 * Either way the bytes are re-downscaled through
 * [ChatImageStore.resizeImageForVision] before they reach the vision encoder.
 * We never trust a client to have done that: an oversized image would blow the
 * `setImageData` transaction, and clamping it here is cheaper than explaining
 * the failure.
 */
class BlobStore(
    private val app: Application,
    private val imageStore: ChatImageStore,
) {

    private data class Entry(val file: File, val createdAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private val blobDir: File by lazy {
        File(app.cacheDir, BLOB_DIR).apply { mkdirs() }
    }

    /**
     * Ingest a client-supplied stream and return an `lmp-blob:<uuid>` handle.
     *
     * @param declaredSize the size the client claims. Used to reject obviously
     *        oversized uploads before reading; the read is capped independently
     *        because a client could lie.
     * @return the handle, or null if rejected.
     */
    fun put(stream: InputStream, declaredSize: Long): String? {
        if (declaredSize > ApiLimits.MAX_BLOB_BYTES) {
            Log.w(TAG, "rejecting blob: declared $declaredSize bytes")
            return null
        }
        val raw = try {
            stream.readAtMost(ApiLimits.MAX_BLOB_BYTES.toInt())
        } catch (t: Throwable) {
            Log.w(TAG, "blob read failed", t)
            return null
        } ?: run {
            Log.w(TAG, "rejecting blob: stream exceeded ${ApiLimits.MAX_BLOB_BYTES} bytes")
            return null
        }

        val resized = imageStore.resizeImageForVision(raw)
        if (resized == null) {
            Log.w(TAG, "blob is not a decodable image (${raw.size} bytes)")
            return null
        }

        val id = UUID.randomUUID().toString()
        return try {
            val file = File(blobDir, "$id.jpg")
            file.writeBytes(resized)
            entries[id] = Entry(file, System.currentTimeMillis())
            sweepExpired()
            "$BLOB_URL_PREFIX$id"
        } catch (t: Throwable) {
            Log.w(TAG, "could not persist blob", t)
            null
        }
    }

    /**
     * Resolve an `image_url` to encoded image bytes ready for `setImageData`.
     *
     * Handles both `lmp-blob:` handles and inline `data:` URLs. Returns null
     * for anything else — notably `http(s)://`, which we deliberately do not
     * fetch: this app is offline-first and silently reaching out to a URL a
     * third-party app supplied would be a real privacy regression.
     */
    fun resolveImage(url: String): ByteArray? = when {
        url.startsWith(BLOB_URL_PREFIX) -> {
            val id = url.removePrefix(BLOB_URL_PREFIX)
            entries[id]?.file?.takeIf { it.exists() }?.readBytes()
                ?: run { Log.w(TAG, "unknown or expired blob: $id"); null }
        }
        url.startsWith(DATA_URL_PREFIX) -> decodeDataUrl(url)
        else -> {
            Log.w(TAG, "unsupported image_url scheme; only data: and lmp-blob: are accepted")
            null
        }
    }

    /** Release a blob once the request that consumed it has finished. */
    fun release(url: String?) {
        if (url == null || !url.startsWith(BLOB_URL_PREFIX)) return
        val id = url.removePrefix(BLOB_URL_PREFIX)
        entries.remove(id)?.file?.delete()
    }

    /**
     * Delete blobs left behind by a previous process. Called from
     * `App.onCreate` — a process death between `putBlob` and the request that
     * would have consumed it leaves an orphan that nothing else will clean up.
     */
    fun sweepOnStartup() {
        runCatching {
            blobDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun sweepExpired() {
        val cutoff = System.currentTimeMillis() - BLOB_TTL_MS
        entries.entries.removeAll { (_, entry) ->
            if (entry.createdAtMs < cutoff) {
                entry.file.delete()
                true
            } else {
                false
            }
        }
    }

    private fun decodeDataUrl(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0) {
            Log.w(TAG, "malformed data: URL")
            return null
        }
        val header = url.substring(0, comma)
        if (!header.contains(";base64")) {
            Log.w(TAG, "only base64 data: URLs are supported")
            return null
        }
        val decoded = try {
            Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "could not base64-decode data: URL", t)
            return null
        }
        // Re-encode through the same downscaler the chat uses, so an
        // over-large inline image can still work instead of blowing the
        // setImageData transaction.
        return imageStore.resizeImageForVision(decoded)
    }

    /**
     * Read the whole stream, or null if it exceeds [limit].
     *
     * Reading rather than trusting the declared size matters: `putBlob`'s
     * `sizeBytes` is client-supplied, and a pipe can deliver more than it
     * promised.
     */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val buffer = ByteArray(READ_CHUNK)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        private const val TAG = "BlobStore"
        private const val BLOB_DIR = "api_blobs"
        private const val DATA_URL_PREFIX = "data:"
        private const val READ_CHUNK = 64 * 1024

        /** Blobs are dropped after this long even if never consumed. */
        const val BLOB_TTL_MS = 10 * 60_000L
    }
}
