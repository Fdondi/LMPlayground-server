package com.druk.llamacpp.jni

import com.druk.llamacpp.LlamaProgressCallback

/**
 * Direct JNI binding to llama.cpp. Loaded only inside the `:llama` process.
 *
 * Public app code should use `com.druk.llamacpp.LlamaCpp` (the binder proxy)
 * instead of constructing this class directly.
 */
class NativeLlamaCpp {

    companion object {
        init {
            System.loadLibrary("llamacpp")
        }
    }

    external fun init(): Int

    external fun systemInfo(): String

    external fun loadModel(
        path: String,
        progressCallback: LlamaProgressCallback
    ): NativeLlamaModel

    external fun probeModelMetadata(path: String): Array<String>?
}
