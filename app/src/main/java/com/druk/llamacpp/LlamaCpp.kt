package com.druk.llamacpp

import android.os.ParcelFileDescriptor

/**
 * App-facing facade for the inference engine.
 *
 * Forwards calls over AIDL to the in-process `LlamaService` (and, after
 * the service moves to `:llama`, the inference process). Replaces the
 * previous direct-JNI `LlamaCpp` — that class moved to
 * [com.druk.llamacpp.jni.NativeLlamaCpp] and is only used by the service
 * implementation and the instrumented JNI tests.
 */
class LlamaCpp(private val client: InferenceClient) {

    /** No-op — the service runs `initBackend()` automatically on bind. */
    fun init(): Int = 0

    fun systemInfo(): String = client.requireConnected().systemInfo()

    /**
     * Load a model identified by a filesystem path. Use this for paths
     * the service can open directly (e.g. `/data/local/tmp/...` in tests).
     * For SAF-backed files use the [ParcelFileDescriptor] overload — paths
     * containing `fd:N` are not valid cross-process.
     */
    fun loadModel(path: String, progressCallback: LlamaProgressCallback): LlamaModel {
        val id = client.requireConnected().loadModel(path, null, wrapProgress(progressCallback))
        if (id == 0) throw IllegalStateException("loadModel failed for $path")
        return LlamaModel(client, id)
    }

    /**
     * Load a model from a [ParcelFileDescriptor]. Binder dups the FD into
     * the service process; the service builds its own `fd:N` string from
     * its dup and holds the PFD alive for the model's lifetime.
     */
    fun loadModel(pfd: ParcelFileDescriptor, progressCallback: LlamaProgressCallback): LlamaModel {
        val id = client.requireConnected().loadModel(null, pfd, wrapProgress(progressCallback))
        if (id == 0) throw IllegalStateException("loadModel failed for pfd")
        return LlamaModel(client, id)
    }

    fun probeModelMetadata(path: String): Array<String>? =
        client.requireConnected().probeModelMetadata(path, null)

    fun probeModelMetadata(pfd: ParcelFileDescriptor): Array<String>? =
        client.requireConnected().probeModelMetadata(null, pfd)

    private fun wrapProgress(cb: LlamaProgressCallback) = object : ILlamaProgressCallback.Stub() {
        override fun onProgress(progress: Float) = cb.onProgress(progress)
    }
}
