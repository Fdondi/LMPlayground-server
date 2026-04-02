package com.druk.lmplayground.conversation

data class GenerationParams(
    val contextSize: Int = 4096,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.0f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
    val thinkingBudget: Int = contextSize / 4
)
