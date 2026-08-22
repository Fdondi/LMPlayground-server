package com.druk.lmplayground.api

import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.Requirements
import com.druk.lmplayground.models.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The model-selection decision table.
 *
 * The load-bearing case is [foregroundFailingRequirementsIsRefusedNotEvicted]:
 * an API caller must never be able to unload the model the user is working
 * with, no matter what it asks for.
 *
 * Plain JVM — [ApiModelResolver] has no Android dependencies apart from
 * DeviceCapability's pure arithmetic, which is why it was written that way.
 */
class ApiModelResolverTest {

    private val twelveGigRam = 12L * 1024 * 1024 * 1024

    private fun model(
        name: String,
        filename: String = "$name.gguf",
        vision: Boolean = false,
        tools: Boolean = false,
        thinking: Boolean = false,
        released: LocalDate? = LocalDate.of(2025, 1, 1),
    ) = ModelInfo(
        name = name,
        filename = filename,
        description = "",
        releaseDate = released,
        supportsTools = tools,
        supportsThinking = thinking,
        mmprojFilename = if (vision) "$name-mmproj.gguf" else null,
    )

    private fun candidate(
        name: String,
        sizeBytes: Long = 1_000_000_000,
        vision: Boolean = false,
        tools: Boolean = false,
        thinking: Boolean = false,
        released: LocalDate? = LocalDate.of(2025, 1, 1),
    ) = ApiModelResolver.Candidate(
        info = model(name, vision = vision, tools = tools, thinking = thinking, released = released),
        sizeBytes = sizeBytes,
        visionReady = vision,
        tools = tools,
        thinking = thinking,
    )

    private fun foreground(
        name: String,
        vision: Boolean = false,
        tools: Boolean = false,
        thinking: Boolean = false,
        maxContext: Int = 8192,
    ) = ApiModelResolver.ForegroundSnapshot(
        info = model(name),
        vision = vision,
        tools = tools,
        thinking = thinking,
        maxContext = maxContext,
    )

    private fun resolve(
        requestedModel: String? = null,
        requirements: Requirements = Requirements(),
        foreground: ApiModelResolver.ForegroundSnapshot? = null,
        downloaded: List<ApiModelResolver.Candidate> = emptyList(),
        allowLoad: Boolean = true,
    ) = ApiModelResolver.resolve(
        requestedModel, requirements, foreground, downloaded, allowLoad, twelveGigRam,
    )

    // ── A model is loaded ────────────────────────────────────────────────

    @Test
    fun loadedModelMeetingRequirementsIsUsed() {
        val fg = foreground("Qwen", tools = true)
        val result = resolve(requirements = Requirements(tools = true), foreground = fg)
        assertTrue(result is ApiModelResolver.Resolution.UseForeground)
    }

    @Test
    fun noRequirementsAlwaysUsesTheLoadedModel() {
        val result = resolve(foreground = foreground("Gemma"))
        assertTrue(result is ApiModelResolver.Resolution.UseForeground)
    }

    @Test
    fun foregroundFailingRequirementsIsRefusedNotEvicted() {
        val fg = foreground("Gemma 3 1B")
        val qwen = candidate("Qwen 3.5 2B", vision = true)
        val result = resolve(
            requirements = Requirements(vision = true),
            foreground = fg,
            downloaded = listOf(qwen, candidate("Llama")),
        )

        val failure = result as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.CAPABILITY_UNAVAILABLE, failure.error.type)
        assertEquals(409, failure.error.httpStatus)
        // Names what is loaded, and only lists models that would actually work.
        assertEquals("Gemma 3 1B.gguf", failure.error.loadedModelId)
        assertEquals(listOf("Qwen 3.5 2B"), failure.error.candidates.map { it.displayName })
        // Crucially: not a LoadHeadless. The user's model stays put.
        assertFalse(result is ApiModelResolver.Resolution.LoadHeadless)
    }

    @Test
    fun requestingADifferentModelThanLoadedIsAMismatch() {
        val result = resolve(
            requestedModel = "Llama.gguf",
            foreground = foreground("Qwen"),
        )
        val failure = result as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.MODEL_MISMATCH, failure.error.type)
        assertEquals("Qwen.gguf", failure.error.loadedModelId)
    }

    @Test
    fun minContextIsCheckedAgainstTheLoadedModel() {
        val result = resolve(
            requirements = Requirements(minContext = 32768),
            foreground = foreground("Qwen", maxContext = 8192),
        )
        val failure = result as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.CAPABILITY_UNAVAILABLE, failure.error.type)
        assertEquals("lmp.require.min_context", failure.error.param)
    }

    // ── Nothing is loaded ────────────────────────────────────────────────

    @Test
    fun picksTheSmallestQualifyingModel() {
        val result = resolve(
            downloaded = listOf(
                candidate("Big", sizeBytes = 8_000_000_000),
                candidate("Small", sizeBytes = 800_000_000),
                candidate("Medium", sizeBytes = 3_000_000_000),
            ),
        )
        val load = result as ApiModelResolver.Resolution.LoadHeadless
        assertEquals("Small", load.candidate.info.name)
    }

    @Test
    fun tieBreaksOnNewestRelease() {
        val result = resolve(
            downloaded = listOf(
                candidate("Older", sizeBytes = 1_000, released = LocalDate.of(2024, 1, 1)),
                candidate("Newer", sizeBytes = 1_000, released = LocalDate.of(2026, 1, 1)),
            ),
        )
        val load = result as ApiModelResolver.Resolution.LoadHeadless
        assertEquals("Newer", load.candidate.info.name)
    }

    @Test
    fun requirementsFilterCandidatesBeforeSize() {
        val result = resolve(
            requirements = Requirements(vision = true),
            downloaded = listOf(
                candidate("TinyTextOnly", sizeBytes = 100),
                candidate("BigVision", sizeBytes = 5_000_000_000, vision = true),
            ),
        )
        val load = result as ApiModelResolver.Resolution.LoadHeadless
        assertEquals("BigVision", load.candidate.info.name)
    }

    @Test
    fun allowLoadFalseRefusesRatherThanLoading() {
        val result = resolve(
            downloaded = listOf(candidate("Qwen")),
            allowLoad = false,
        )
        val failure = result as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.NO_MODEL_LOADED, failure.error.type)
        assertEquals(503, failure.error.httpStatus)
    }

    @Test
    fun nothingQualifyingIsNoModelAvailable() {
        val result = resolve(
            requirements = Requirements(vision = true),
            downloaded = listOf(candidate("TextOnly")),
        )
        val failure = result as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.NO_MODEL_AVAILABLE, failure.error.type)
        // Still tells the caller what *is* downloaded, so it can explain itself.
        assertEquals(listOf("TextOnly"), failure.error.candidates.map { it.displayName })
    }

    @Test
    fun nothingDownloadedIsNoModelAvailable() {
        val failure = resolve() as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.NO_MODEL_AVAILABLE, failure.error.type)
        assertTrue(failure.error.message.contains("No models are downloaded"))
    }

    @Test
    fun namingAnUndownloadedModelIsNotFound() {
        val result = resolve(
            requestedModel = "Absent.gguf",
            downloaded = listOf(candidate("Present")),
        )
        val failure = result as ApiModelResolver.Resolution.Failure
        assertEquals(ErrorType.MODEL_NOT_FOUND, failure.error.type)
        assertEquals(404, failure.error.httpStatus)
    }

    // ── The repack decision ──────────────────────────────────────────────

    @Test
    fun largeModelOnSmallDeviceLoadsMemoryMapped() {
        // 5 GB model, 8 GB RAM: repacking would need ~10 GB resident, so the
        // headless path must take the mmap-only route — there is no user to
        // answer a "this might not fit" prompt.
        val result = ApiModelResolver.resolve(
            requestedModel = null,
            requirements = Requirements(),
            foreground = null,
            downloaded = listOf(candidate("Chonky", sizeBytes = 5L * 1024 * 1024 * 1024)),
            allowLoad = true,
            totalRamBytes = 8L * 1024 * 1024 * 1024,
        )
        val load = result as ApiModelResolver.Resolution.LoadHeadless
        assertTrue(load.disableRepack)
    }

    @Test
    fun smallModelOnLargeDeviceKeepsRepacking() {
        val result = resolve(downloaded = listOf(candidate("Tiny", sizeBytes = 500_000_000)))
        val load = result as ApiModelResolver.Resolution.LoadHeadless
        assertFalse(load.disableRepack)
    }

    // ── Requirement semantics ────────────────────────────────────────────

    @Test
    fun falseRequirementMeansNoConstraintNotMustNotHave() {
        // A caller that says vision=false must still be served by a
        // vision-capable model — "false" is absence of a constraint.
        val result = resolve(
            requirements = Requirements(vision = false),
            foreground = foreground("VisionModel", vision = true),
        )
        assertTrue(result is ApiModelResolver.Resolution.UseForeground)
    }
}
