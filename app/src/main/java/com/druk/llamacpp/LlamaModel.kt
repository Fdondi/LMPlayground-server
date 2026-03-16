package com.druk.llamacpp

/**
 * The `LlamaModel` class represents a loaded large language model (LLM) in the llama.cpp library.
 *
 * It provides methods for interacting with the model, such as creating generation sessions
 * and querying model properties.
 */
class LlamaModel {

    /**
     * The native handle to the model in the llama.cpp library.
     * This field is private and should not be modified directly.
     */
    private var nativeHandle: Long = 0

    /**
     * Creates a new generation session for the loaded model.
     *
     * @return A `LlamaGenerationSession` object for managing text generation.
     */
    external fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int
    ): LlamaGenerationSession?

    external fun getContextTrainSize(): Int

    /**
     * Gets the size of the model in bytes.
     *
     * @return The size of the model.
     */
    external fun getModelSize(): Long

    external fun getModelReport(): String

    /**
     * Checks if the model's chat template supports thinking/reasoning mode.
     *
     * @return true if the model supports thinking.
     */
    external fun supportsThinking(): Boolean

    /**
     * Loads a multimodal projector model for vision support.
     *
     * @param mmprojPath The path to the mmproj GGUF file.
     */
    external fun loadMmprojModel(mmprojPath: String)

    /**
     * Checks if the model supports vision input (image analysis).
     *
     * @return true if a multimodal projector is loaded and supports vision.
     */
    external fun supportsVision(): Boolean

    /**
     * Unloads the model from memory and releases associated resources.
     */
    external fun unloadModel()

}