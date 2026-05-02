package com.druk.llamacpp

/**
 * AIDL-proxy view of a loaded model. Holds an opaque positive [modelId]
 * issued by the service; the native pointer never leaves the service
 * process.
 */
class LlamaModel internal constructor(
    private val client: InferenceClient,
    internal val modelId: Int,
) {
    fun getModelSize(): Long = client.requireConnected().getModelSize(modelId)

    fun getModelReport(): String = client.requireConnected().getModelReport(modelId)

    fun getContextTrainSize(): Int = client.requireConnected().getContextTrainSize(modelId)

    fun supportsThinking(): Boolean = client.requireConnected().supportsThinking(modelId)

    fun unloadModel() {
        client.requireConnected().unloadModel(modelId)
    }

    fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String,
    ): LlamaGenerationSession? {
        InferenceLimits.requireWithinBudget(systemPrompt, "system prompt")
        val params = SamplerParams(
            contextSize = contextSize,
            temperature = temperature,
            topP = topP,
            repetitionPenalty = repetitionPenalty,
            topK = topK,
            minP = minP,
            seed = seed,
            thinkingBudget = thinkingBudget,
            systemPrompt = systemPrompt,
        )
        val sessionId = client.requireConnected().createSession(modelId, params)
        if (sessionId == 0) return null
        return LlamaGenerationSession(client, sessionId)
    }
}
