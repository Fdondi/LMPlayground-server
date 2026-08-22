package com.druk.lmplayground.api

import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.CandidateModel
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.Requirements
import com.druk.lmplayground.models.ModelInfo

/**
 * Decides which model serves an API request.
 *
 * The governing rule is that **LM Playground never unloads the model the user
 * is working with.** A background app asking for vision does not get to evict a
 * multi-gigabyte model out from under someone mid-conversation; it gets a clear
 * error naming what is loaded and what would work instead.
 *
 * Pure by construction — it takes a snapshot and returns a decision, with no
 * Android or engine dependencies, so the whole table below is a plain JVM test.
 */
object ApiModelResolver {

    /** What the arbiter knows about the model the user currently has loaded. */
    data class ForegroundSnapshot(
        val info: ModelInfo,
        val vision: Boolean,
        val tools: Boolean,
        val thinking: Boolean,
        val maxContext: Int,
    )

    /** A downloaded model the resolver may choose to load headlessly. */
    data class Candidate(
        val info: ModelInfo,
        val sizeBytes: Long,
        /** True when the vision projector is present on disk, not just declared. */
        val visionReady: Boolean,
        val tools: Boolean,
        val thinking: Boolean,
    )

    sealed interface Resolution {
        /** Serve on the already-loaded model, in a new independent session. */
        data class UseForeground(val snapshot: ForegroundSnapshot) : Resolution

        /** Nothing is loaded; load this one headlessly. */
        data class LoadHeadless(
            val candidate: Candidate,
            /**
             * Force the memory-mapped load path. Headless requests have no user
             * to answer a "this might not fit" dialog, so we take the smaller
             * resident footprint rather than risk an OOM kill.
             */
            val disableRepack: Boolean,
        ) : Resolution

        data class Failure(val error: ApiError) : Resolution
    }

    /**
     * @param requestedModel a GGUF filename, or null for "auto".
     * @param foreground the model the user has loaded, or null.
     * @param downloaded every model on disk, with its resolved capabilities.
     * @param allowLoad false forbids a headless load.
     * @param totalRamBytes device RAM, for the repack decision.
     */
    fun resolve(
        requestedModel: String?,
        requirements: Requirements,
        foreground: ForegroundSnapshot?,
        downloaded: List<Candidate>,
        allowLoad: Boolean,
        totalRamBytes: Long,
    ): Resolution {
        if (foreground != null) {
            return resolveAgainstForeground(requestedModel, requirements, foreground, downloaded)
        }

        if (!allowLoad) {
            return Resolution.Failure(ApiError(
                message = "No model is loaded and `lmp.allow_load` is false. Ask the user to " +
                    "open LM Playground and load a model, or set allow_load to true.",
                type = ErrorType.NO_MODEL_LOADED,
                param = "lmp.allow_load",
                candidates = downloaded.map { it.toCandidateModel() },
            ))
        }

        if (requestedModel != null && downloaded.none { it.info.filename == requestedModel }) {
            return Resolution.Failure(ApiError(
                message = "Model '$requestedModel' is not downloaded on this device.",
                type = ErrorType.MODEL_NOT_FOUND,
                param = "model",
                candidates = downloaded.map { it.toCandidateModel() },
            ))
        }

        val eligible = downloaded
            .filter { requestedModel == null || it.info.filename == requestedModel }
            .filter { satisfies(it, requirements) }

        if (eligible.isEmpty()) {
            return Resolution.Failure(ApiError(
                message = describeNoCandidate(requirements, downloaded),
                type = ErrorType.NO_MODEL_AVAILABLE,
                param = "lmp.require",
                candidates = downloaded.map { it.toCandidateModel() },
            ))
        }

        // Smallest first: an unattended background request should have the most
        // predictable latency and the least chance of an OOM. Newest release
        // breaks ties so equal-size candidates prefer the better model.
        val chosen = eligible.minWithOrNull(
            compareBy<Candidate> { it.sizeBytes }
                .thenByDescending { it.info.releaseDate }
        ) ?: eligible.first()

        return Resolution.LoadHeadless(
            candidate = chosen,
            disableRepack = com.druk.lmplayground.models.DeviceCapability
                .exceedsRamBudget(chosen.sizeBytes, totalRamBytes),
        )
    }

    private fun resolveAgainstForeground(
        requestedModel: String?,
        requirements: Requirements,
        foreground: ForegroundSnapshot,
        downloaded: List<Candidate>,
    ): Resolution {
        if (requestedModel != null && requestedModel != foreground.info.filename) {
            return Resolution.Failure(ApiError(
                message = "You asked for '$requestedModel' but the user has " +
                    "'${foreground.info.name}' loaded. LM Playground will not unload a model " +
                    "the user is using — retry with \"model\": \"auto\" to use it, or wait.",
                type = ErrorType.MODEL_MISMATCH,
                param = "model",
                loadedModelId = foreground.info.filename,
                candidates = listOf(
                    CandidateModel(foreground.info.filename, foreground.info.name, true)
                ),
            ))
        }

        if (satisfiesForeground(foreground, requirements)) {
            return Resolution.UseForeground(foreground)
        }

        val missing = missingCapabilities(foreground, requirements)
        return Resolution.Failure(ApiError(
            message = "The loaded model '${foreground.info.name}' does not support " +
                "${missing.joinToString(" and ")}. LM Playground will not unload a model the " +
                "user is using.",
            type = ErrorType.CAPABILITY_UNAVAILABLE,
            param = "lmp.require.${missing.firstOrNull() ?: "capabilities"}",
            loadedModelId = foreground.info.filename,
            candidates = downloaded
                .filter { satisfies(it, requirements) }
                .map { it.toCandidateModel() },
        ))
    }

    // ── Capability matching ──────────────────────────────────────────────

    /**
     * An omitted or `false` requirement means *no constraint* — it never means
     * "must not have". That is why every check is `!required || available`.
     */
    private fun satisfies(candidate: Candidate, requirements: Requirements): Boolean {
        if (requirements.vision && !candidate.visionReady) return false
        if (requirements.tools && !candidate.tools) return false
        if (requirements.thinking && !candidate.thinking) return false
        // min_context cannot be checked before a load — getContextTrainSize
        // needs the weights. The turn runner re-checks it authoritatively once
        // the model is up; see PROTOCOL.md §capabilities.
        return true
    }

    private fun satisfiesForeground(
        foreground: ForegroundSnapshot,
        requirements: Requirements,
    ): Boolean = missingCapabilities(foreground, requirements).isEmpty()

    private fun missingCapabilities(
        foreground: ForegroundSnapshot,
        requirements: Requirements,
    ): List<String> = buildList {
        if (requirements.vision && !foreground.vision) add("vision")
        if (requirements.tools && !foreground.tools) add("tools")
        if (requirements.thinking && !foreground.thinking) add("thinking")
        if (requirements.minContext > 0 && foreground.maxContext < requirements.minContext) {
            add("min_context")
        }
    }

    private fun describeNoCandidate(
        requirements: Requirements,
        downloaded: List<Candidate>,
    ): String {
        if (downloaded.isEmpty()) {
            return "No models are downloaded. Ask the user to open LM Playground and " +
                "download one."
        }
        val needed = buildList {
            if (requirements.vision) add("image input")
            if (requirements.tools) add("tool calling")
            if (requirements.thinking) add("reasoning")
        }
        return if (needed.isEmpty()) {
            "No downloaded model could be selected."
        } else {
            "None of the ${downloaded.size} downloaded models support " +
                "${needed.joinToString(" and ")}."
        }
    }

    private fun Candidate.toCandidateModel() =
        CandidateModel(info.filename, info.name, downloaded = true)
}
