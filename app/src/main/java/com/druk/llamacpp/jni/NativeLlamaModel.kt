package com.druk.llamacpp.jni

class NativeLlamaModel {

    private var nativeHandle: Long = 0

    external fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String
    ): NativeLlamaSession?

    external fun getContextTrainSize(): Int

    external fun getModelSize(): Long

    external fun getModelReport(): String

    external fun supportsThinking(): Boolean

    external fun unloadModel()
}
