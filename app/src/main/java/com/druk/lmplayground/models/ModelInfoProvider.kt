package com.druk.lmplayground.models

import android.net.Uri
import com.druk.lmplayground.R
import java.time.LocalDate

object ModelInfoProvider {

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
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Qwen 3 1.7B",
            filename = "Qwen3-1.7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.28Gb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Qwen 3 4B",
            filename = "Qwen3-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.5Gb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Qwen 3.5 0.8B",
            filename = "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 Lightweight chat model \u00B7 466Mb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Qwen 3.5 2B",
            filename = "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.07Gb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Qwen 3.5 4B",
            filename = "Qwen_Qwen3.5-4B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/Qwen_Qwen3.5-4B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.25Gb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Gemma 3 1B",
            filename = "gemma-3-1b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 Lightweight chat model \u00B7 806Mb",
            logoRes = R.drawable.logo_google
        ),
        ModelInfo(
            name = "Gemma 3 4B",
            filename = "gemma-3-4b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 General-purpose chat model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_google
        ),
        ModelInfo(
            name = "Llama 3.2 1B",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 Lightweight chat model \u00B7 808Mb",
            logoRes = R.drawable.logo_meta
        ),
        ModelInfo(
            name = "Llama 3.2 3B",
            filename = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 2.02Gb",
            logoRes = R.drawable.logo_meta
        ),
        ModelInfo(
            name = "Phi-4 mini",
            filename = "Phi-4-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-15"),
            description = "Microsoft \u00B7 Compact reasoning model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_microsoft
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 1.5B",
            filename = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Compact reasoning model \u00B7 1.12Gb",
            logoRes = R.drawable.logo_deepseek
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 7B",
            filename = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Advanced reasoning model \u00B7 4.68Gb",
            logoRes = R.drawable.logo_deepseek
        ),
        ModelInfo(
            name = "LFM2.5 1.2B Thinking",
            filename = "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-09"),
            description = "Liquid AI \u00B7 Thinking model \u00B7 731Mb",
            logoRes = R.drawable.logo_liquid
        ),
        ModelInfo(
            name = "Ministral 3 3B Instruct",
            filename = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight chat model \u00B7 2.15Gb",
            logoRes = R.drawable.logo_mistral
        ),
        ModelInfo(
            name = "Ministral 3 3B Reasoning",
            filename = "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Reasoning-2512-GGUF/resolve/main/Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight reasoning model \u00B7 2.15Gb",
            logoRes = R.drawable.logo_mistral
        ),
        ModelInfo(
            name = "Ministral 3 8B Instruct",
            filename = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/Ministral-3-8B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 General-purpose chat model \u00B7 5.2Gb",
            logoRes = R.drawable.logo_mistral
        ),
        ModelInfo(
            name = "Ministral 3 8B Reasoning",
            filename = "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Reasoning-2512-GGUF/resolve/main/Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Advanced reasoning model \u00B7 5.2Gb",
            logoRes = R.drawable.logo_mistral
        ),
        ModelInfo(
            name = "Granite 4.0 Micro",
            filename = "granite-4.0-micro-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-micro-GGUF/resolve/main/granite-4.0-micro-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Enterprise chat model \u00B7 2.1Gb",
            logoRes = R.drawable.logo_ibm
        ),
        ModelInfo(
            name = "Granite 4.0 H-Tiny",
            filename = "granite-4.0-h-tiny-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-h-tiny-GGUF/resolve/main/granite-4.0-h-tiny-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Hybrid enterprise model \u00B7 4.23Gb",
            logoRes = R.drawable.logo_ibm
        ),
        ModelInfo(
            name = "Gemma 3n E2B",
            filename = "gemma-3n-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E2B-it-text-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 2.79Gb",
            logoRes = R.drawable.logo_google
        ),
        ModelInfo(
            name = "Gemma 3n E4B",
            filename = "gemma-3n-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E4B-it-text-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 4.24Gb",
            logoRes = R.drawable.logo_google
        ),
        ModelInfo(
            name = "Qwen2.5 0.5B",
            filename = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            inputPrefix = "<|im_start|>user\n",
            inputSuffix = "<|im_end|>\n<|im_start|>assistant\n",
            antiPrompt = arrayOf("<|im_end|>"),
            description = "Alibaba \u00B7 Ultra-lightweight chat model \u00B7 398Mb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Qwen2.5 1.5B",
            filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            inputPrefix = "<|im_start|>user\n",
            inputSuffix = "<|im_end|>\n<|im_start|>assistant\n",
            antiPrompt = arrayOf("<|im_end|>"),
            description = "Alibaba \u00B7 Compact chat model \u00B7 986Mb",
            logoRes = R.drawable.logo_qwen
        ),
        ModelInfo(
            name = "Phi3.5 mini",
            filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-08-20"),
            inputPrefix = "<|user|>\n",
            inputSuffix = "<|end|>\n<|assistant|>\n",
            antiPrompt = arrayOf("<|end|>", "<|assistant|>"),
            description = "Microsoft \u00B7 Compact chat model \u00B7 2.2Gb",
            logoRes = R.drawable.logo_microsoft
        ),
        ModelInfo(
            name = "Mistral 7B",
            filename = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-05-22"),
            inputPrefix = "[INST]",
            inputSuffix = "[/INST]",
            description = "Mistral \u00B7 General-purpose chat model \u00B7 4.37Gb",
            logoRes = R.drawable.logo_mistral
        ),
        ModelInfo(
            name = "Llama 3.1 8B",
            filename = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-07-23"),
            inputPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
            inputSuffix = "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
            antiPrompt = arrayOf("<|eot_id|>"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 4.92Gb",
            logoRes = R.drawable.logo_meta
        ),
        ModelInfo(
            name = "Gemma2 9B",
            filename = "gemma-2-9b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-06-27"),
            inputPrefix = "<start_of_turn>user\n",
            inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
            antiPrompt = arrayOf("<start_of_turn>user", "<start_of_turn>model", "<end_of_turn>"),
            description = "Google \u00B7 Advanced chat model \u00B7 5.44Gb",
            logoRes = R.drawable.logo_google
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
    
    /**
     * Get models with their download status.
     */
    fun getModelsWithStatus(downloadedFilenames: Set<String>): List<ModelWithStatus> {
        return allModels
            .sortedByDescending { it.releaseDate }
            .map { model ->
            ModelWithStatus(
                model = model,
                isDownloaded = model.filename in downloadedFilenames
            )
        }
    }
}
