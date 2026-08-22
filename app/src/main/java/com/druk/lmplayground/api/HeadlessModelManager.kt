package com.druk.lmplayground.api

import android.app.Application
import android.util.Log
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Loads a model for API requests when the user has none loaded, and unloads it
 * again once nobody has asked for a while.
 *
 * Structurally this mirrors [com.druk.lmplayground.rag.EmbeddingModelManager],
 * which solves the same problem for EmbeddingGemma: a `Mutex`, a
 * `ModelFileHandle` held open for the model's lifetime (the service-side mmap
 * dies with the descriptor), and an idle-unload job that a new use cancels
 * before taking the lock.
 *
 * Two differences from the embedding case, both deliberate:
 *
 * - **A five-minute idle window** rather than one minute. These are multi-GB
 *   models and reloading costs seconds, so churning them is worse than holding
 *   them a little longer. It is still short enough that an occasional API call
 *   does not permanently occupy several gigabytes.
 * - **A unique mmproj temp file.** `ModelRuntime` copies the projector to a
 *   fixed `mmproj_temp.gguf` and deletes it after loading; a headless vision
 *   load racing a user load would clobber it and silently produce a model with
 *   no vision (or a corrupt projector).
 *
 * The arbiter guarantees this only ever loads while nothing is in the
 * foreground, so we never hold two multi-GB models at once.
 */
class HeadlessModelManager(
    private val app: Application,
    private val llamaCpp: LlamaCpp?,
    private val storageRepository: StorageRepository,
    private val scope: CoroutineScope,
) {

    /** Template-detected capabilities, read after the weights are up. */
    data class Capabilities(
        val vision: Boolean,
        val tools: Boolean,
        val thinking: Boolean,
        val maxContext: Int,
    )

    class Loaded(
        val model: LlamaModel,
        val info: ModelInfo,
    ) {
        /**
         * Read the model's real capabilities from its chat template. This is
         * the authoritative source — catalog flags are only hints until a model
         * has been loaded once.
         */
        fun capabilities(): Capabilities = Capabilities(
            vision = runCatching { model.supportsVision() }.getOrDefault(false),
            tools = runCatching { model.supportsToolCalling() }.getOrDefault(false),
            thinking = runCatching { model.supportsThinking() }.getOrDefault(false),
            maxContext = runCatching { model.getContextTrainSize() }.getOrDefault(0),
        )
    }

    private val mutex = Mutex()
    private var model: LlamaModel? = null
    private var loadedInfo: ModelInfo? = null
    private var fileHandle: StorageRepository.ModelFileHandle? = null
    private var idleUnloadJob: Job? = null

    /**
     * Load [info] if it isn't already up, and return a handle to it.
     *
     * Returns null when the engine is unavailable, the file is missing, or the
     * native loader rejected the GGUF.
     */
    suspend fun ensureLoaded(info: ModelInfo, disableRepack: Boolean): Loaded? = mutex.withLock {
        idleUnloadJob?.cancel()
        idleUnloadJob = null

        val current = model
        if (current != null && loadedInfo?.filename == info.filename) {
            return@withLock Loaded(current, info)
        }
        // A different model is up — drop it before mapping another.
        if (current != null) unloadLocked()

        val llamaCpp = llamaCpp ?: return@withLock null
        val handle = storageRepository.openModelFile(info.filename)
        if (handle == null) {
            Log.w(TAG, "model file not available: ${info.filename}")
            return@withLock null
        }

        return@withLock try {
            Log.i(TAG, "headless load: ${info.filename} (disableRepack=$disableRepack)")
            val loaded = llamaCpp.loadModel(
                handle.pfd,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) = Unit
                },
                disableRepack = disableRepack,
            )
            loadMmprojIfPresent(loaded, info)
            model = loaded
            loadedInfo = info
            fileHandle = handle
            Loaded(loaded, info)
        } catch (t: Throwable) {
            Log.e(TAG, "headless load failed for ${info.filename}", t)
            handle.close()
            null
        }
    }

    /**
     * Copy the vision projector somewhere the native mtmd loader can open it.
     *
     * The projector has to be a real filesystem path (unlike the model, which
     * travels as an fd), and the temp file name must not collide with the fixed
     * `mmproj_temp.gguf` that `ModelRuntime` writes and then deletes.
     */
    private fun loadMmprojIfPresent(loaded: LlamaModel, info: ModelInfo) {
        val mmprojFilename = info.mmprojFilename ?: return
        val temp = File.createTempFile("mmproj_api", ".gguf", app.cacheDir)
        try {
            if (storageRepository.copyModelToFile(mmprojFilename, temp)) {
                loaded.loadMmprojModel(temp.absolutePath)
                Log.i(TAG, "mmproj loaded, supportsVision=${loaded.supportsVision()}")
            } else {
                Log.w(TAG, "mmproj not on disk: $mmprojFilename — continuing text-only")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mmproj load failed for $mmprojFilename", t)
        } finally {
            temp.delete()
        }
    }

    /** Restart the idle timer. Called when a turn finishes. */
    fun touch() {
        idleUnloadJob?.cancel()
        if (model == null) return
        idleUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                // A new use cancels this job before taking the mutex, so
                // reaching here means the model really did sit idle. The
                // isActive check keeps a cancel that raced the delay from
                // unloading a model a fresh caller is about to use.
                if (isActive) unloadLocked()
            }
        }
    }

    suspend fun unload() = mutex.withLock {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        unloadLocked()
    }

    /**
     * The service process died, taking the native model with it. Release our
     * local descriptor but do **not** call `unloadModel` — there is nothing on
     * the other end of that binder, and the call would just throw.
     */
    fun dropAfterCrash() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        model = null
        loadedInfo = null
        fileHandle?.close()
        fileHandle = null
    }

    private fun unloadLocked() {
        model?.let {
            Log.i(TAG, "headless unload: ${loadedInfo?.filename}")
            runCatching { it.unloadModel() }
        }
        model = null
        loadedInfo = null
        fileHandle?.close()
        fileHandle = null
    }

    private companion object {
        private const val TAG = "HeadlessModelManager"

        /**
         * Keep-warm window after the last API turn. Longer than the embedding
         * model's 60 s because reloading a multi-GB GGUF is expensive; short
         * enough that an idle background app doesn't hold gigabytes forever.
         */
        const val IDLE_UNLOAD_MS = 5 * 60_000L
    }
}
