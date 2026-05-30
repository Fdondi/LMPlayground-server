package com.druk.lmplayground.models

import android.net.Uri
import com.druk.lmplayground.R
import java.time.LocalDate

object ModelInfoProvider {

    // Officially declared language support per model family (ISO 639-1 codes),
    // sourced from each model's HuggingFace card / publisher blog.
    private val MULTILINGUAL_BROAD = listOf(
        "en", "ar", "bg", "cs", "da", "de", "el", "es", "fi", "fr",
        "hi", "hu", "id", "it", "ja", "ko", "ms", "nl", "no", "pl",
        "pt", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh"
    )
    private val QWEN25_LANGS = listOf(
        "en", "zh", "fr", "es", "pt", "de", "it", "ru",
        "ja", "ko", "vi", "th", "ar"
    )
    private val LLAMA_LANGS = listOf("en", "de", "fr", "it", "pt", "hi", "es", "th")
    private val PHI_LANGS = listOf(
        "ar", "zh", "cs", "da", "nl", "en", "fi", "fr", "de", "he",
        "hu", "it", "ja", "ko", "no", "pl", "pt", "ru", "es", "sv",
        "th", "tr", "uk"
    )
    private val DEEPSEEK_LANGS = listOf("en", "zh")
    private val LFM_LANGS = listOf("en", "ar", "zh", "fr", "de", "ja", "ko", "es")
    private val MISTRAL_LANGS = listOf(
        "en", "fr", "de", "es", "it", "pt", "nl", "zh", "ja", "ko", "ar"
    )
    private val GRANITE_LANGS = listOf(
        "en", "de", "es", "fr", "ja", "pt", "ar", "cs", "it", "ko", "nl", "zh"
    )
    private val ENGLISH_ONLY = listOf("en")

    /**
     * Static list of all available models
     */
    val allModels: List<ModelInfo> = listOf(
        ModelInfo(
            name = "Qwen 3 0.6B",
            filename = "Qwen3-0.6B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 Lightweight chat model \u00B7 484Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3 1.7B",
            filename = "Qwen3-1.7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.28Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3 4B",
            filename = "Qwen3-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.5Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 0.8B",
            filename = "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 Lightweight chat model \u00B7 466Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 2B",
            filename = "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.07Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 4B",
            filename = "Qwen_Qwen3.5-4B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/Qwen_Qwen3.5-4B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.25Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3 1B",
            filename = "gemma-3-1b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 Lightweight chat model \u00B7 806Mb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3 4B",
            filename = "gemma-3-4b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 General-purpose chat model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Llama 3.2 1B",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 Lightweight chat model \u00B7 808Mb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Llama 3.2 3B",
            filename = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 2.02Gb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Phi-4 mini",
            filename = "Phi-4-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-15"),
            description = "Microsoft \u00B7 Compact reasoning model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_microsoft,
            supportedLanguages = PHI_LANGS
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 1.5B",
            filename = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Compact reasoning model \u00B7 1.12Gb",
            logoRes = R.drawable.logo_deepseek,
            supportedLanguages = DEEPSEEK_LANGS
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 7B",
            filename = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Advanced reasoning model \u00B7 4.68Gb",
            logoRes = R.drawable.logo_deepseek,
            supportedLanguages = DEEPSEEK_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 350M",
            filename = "LFM2.5-350M-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Liquid AI \u00B7 Ultra-lightweight chat model \u00B7 267Mb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 1.2B Thinking",
            filename = "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-09"),
            description = "Liquid AI \u00B7 Thinking model \u00B7 731Mb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 3B Instruct",
            filename = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight chat model \u00B7 2.15Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 3B Reasoning",
            filename = "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Reasoning-2512-GGUF/resolve/main/Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight reasoning model \u00B7 2.15Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 8B Instruct",
            filename = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/Ministral-3-8B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 General-purpose chat model \u00B7 5.2Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 8B Reasoning",
            filename = "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Reasoning-2512-GGUF/resolve/main/Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Advanced reasoning model \u00B7 5.2Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Granite 4.0 Micro",
            filename = "granite-4.0-micro-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-micro-GGUF/resolve/main/granite-4.0-micro-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Enterprise chat model \u00B7 2.1Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.0 H-Tiny",
            filename = "granite-4.0-h-tiny-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-h-tiny-GGUF/resolve/main/granite-4.0-h-tiny-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Hybrid enterprise model \u00B7 4.23Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Nemotron 3 Nano 4B",
            filename = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-12-15"),
            description = "NVIDIA \u00B7 Hybrid reasoning model \u00B7 2.84Gb",
            logoRes = R.drawable.logo_nvidia,
            supportedLanguages = ENGLISH_ONLY
        ),
        ModelInfo(
            name = "Gemma 3n E2B",
            filename = "gemma-3n-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E2B-it-text-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 2.79Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3n E4B",
            filename = "gemma-3n-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E4B-it-text-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 4.24Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 Efficient on-device model \u00B7 3.11Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 4 E4B",
            filename = "gemma-4-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 Efficient on-device model \u00B7 4.98Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen2.5 0.5B",
            filename = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            description = "Alibaba \u00B7 Ultra-lightweight chat model \u00B7 398Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = QWEN25_LANGS
        ),
        ModelInfo(
            name = "Qwen2.5 1.5B",
            filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            description = "Alibaba \u00B7 Compact chat model \u00B7 986Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = QWEN25_LANGS
        ),
        ModelInfo(
            name = "Phi3.5 mini",
            filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-08-20"),
            description = "Microsoft \u00B7 Compact chat model \u00B7 2.2Gb",
            logoRes = R.drawable.logo_microsoft,
            supportedLanguages = PHI_LANGS
        ),
        ModelInfo(
            name = "Mistral 7B",
            filename = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-05-22"),
            description = "Mistral \u00B7 General-purpose chat model \u00B7 4.37Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Llama 3.1 8B",
            filename = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-07-23"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 4.92Gb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Gemma2 9B",
            filename = "gemma-2-9b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-06-27"),
            description = "Google \u00B7 Advanced chat model \u00B7 5.44Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = ENGLISH_ONLY
        ),
        ModelInfo(
            name = "GPT-OSS 20B",
            filename = "gpt-oss-20b-mxfp4.gguf",
            remoteUri = Uri.parse("https://huggingface.co/ggml-org/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-mxfp4.gguf"),
            releaseDate = LocalDate.parse("2025-08-05"),
            description = "OpenAI \u00B7 Large reasoning MoE model \u00B7 12.11Gb",
            logoRes = R.drawable.logo_openai,
            supportedLanguages = MULTILINGUAL_BROAD
        )
    )
    
    /**
     * Get all known model filenames
     */
    val knownFilenames: Set<String> = allModels.map { it.filename }.toSet()
    
    /**
     * Get model by filename
     */
    fun getByFilename(filename: String): ModelInfo? = allModels.find { it.filename == filename }
    
    /**
     * Get display name for a filename
     */
    fun getDisplayName(filename: String): String = getByFilename(filename)?.name ?: filename.removeSuffix(".gguf")
    
    private fun formatFileSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1.0) "%.2fGb".format(gb) else "%dMb".format(bytes / 1_000_000)
    }

    /**
     * Create a ModelInfo for a custom (user-provided) GGUF file.
     */
    fun createCustomModelInfo(filename: String, name: String, sizeBytes: Long): ModelInfo {
        val sizeLabel = formatFileSize(sizeBytes)
        return ModelInfo(
            name = name.ifEmpty { filename.removeSuffix(".gguf") },
            filename = filename,
            remoteUri = null,
            releaseDate = null,
            description = "Custom model · $sizeLabel",
            logoRes = R.drawable.penrose_triangle
        )
    }

    /**
     * Get models with their download status.
     */
    fun getModelsWithStatus(
        downloadedFilenames: Set<String>,
        customModels: List<ModelInfo> = emptyList()
    ): List<ModelWithStatus> {
        val knownModels = allModels
            .sortedByDescending { it.releaseDate }
            .map { model ->
                ModelWithStatus(
                    model = model,
                    isDownloaded = model.filename in downloadedFilenames,
                )
            }
        val customWithStatus = customModels.map { model ->
            ModelWithStatus(model = model, isDownloaded = true)
        }
        return customWithStatus + knownModels
    }
}
