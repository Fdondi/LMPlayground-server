package com.druk.llamacpp

/**
 * `LlamaCpp` class provides a Java (JNI) interface for interacting with the `llama.cpp` library,
 * enabling efficient local execution of large language models (LLMs).
 */
class LlamaCpp {

    companion object {
        init {
            System.loadLibrary("llamacpp")
        }
    }

    /**
     * Initializes the underlying llama.cpp environment.
     *
     * @return A status code (0 - success).
     */
    external fun init(): Int

    /**
     * Generate system information about the llama.cpp environment.
     *
     * @return A string containing system information.
     */
    external fun systemInfo(): String

    /**
     * Loads a pre-trained LLM model from the specified file path.
     *
     * @param path The path to the model file on disk.
     * @param progressCallback A callback to receive progress updates during loading.
     * @return A `LlamaModel` instance representing the loaded model.
     */
    external fun loadModel(path: String,
                           progressCallback: LlamaProgressCallback): LlamaModel
}