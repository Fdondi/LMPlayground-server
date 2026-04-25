package com.druk.lmplayground.models

import android.app.ActivityManager
import android.content.Context

/**
 * Heuristics for deciding whether a given model file will fit on the
 * current device. Used to gate model loads and to flag model picker
 * entries that are likely to crash with SIGSEGV during mat-mul under
 * memory pressure (the dominant Vitals crash for v1.5.x — kernel
 * evicts mmap'd weight pages, the next compute kernel reads an unmapped
 * page and segfaults).
 *
 * Heuristic source: llama.cpp issues #18949 / #14999 / discussion #1876
 * confirm that mmap-backed weights need roughly the model's on-disk size
 * resident in RAM during decode; KV cache and activations add ~10–30 %
 * working set on top.
 */
object DeviceCapability {

    enum class FitVerdict {
        /** Model comfortably fits in current available RAM. */
        Fit,

        /** Model fits in total RAM but available RAM is tight; loading
         *  may succeed but is at elevated risk of mmap eviction crashes. */
        Tight,

        /** Model exceeds 70 % of total device RAM — cannot reliably run. */
        WontFit,
    }

    fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info
    }

    fun totalRamBytes(context: Context): Long = memoryInfo(context).totalMem

    fun availableRamBytes(context: Context): Long = memoryInfo(context).availMem

    /**
     * @param modelSizeBytes weight file size on disk
     * @param totalRamBytes  device total RAM (`MemoryInfo.totalMem`)
     * @param availRamBytes  device free RAM (`MemoryInfo.availMem`)
     */
    fun evaluateFit(
        modelSizeBytes: Long,
        totalRamBytes: Long,
        availRamBytes: Long,
    ): FitVerdict {
        if (modelSizeBytes <= 0 || totalRamBytes <= 0) return FitVerdict.Fit
        // Hard block: model alone exceeds 70 % of total RAM. Even on a
        // freshly-rebooted device with nothing else running, the kernel
        // cannot keep enough weight pages resident to avoid eviction.
        if (modelSizeBytes * 10 > totalRamBytes * 7) return FitVerdict.WontFit
        // Soft warn: working set (model × 1.3) doesn't fit in available
        // RAM right now. Loading may work but is the dominant SIGSEGV
        // pattern on Vitals — surface it to the user.
        if (modelSizeBytes * 13 > availRamBytes * 10) return FitVerdict.Tight
        return FitVerdict.Fit
    }

    /**
     * Parse the size embedded in catalog `description` strings such as
     * `"Alibaba · General-purpose chat model · 5.44Gb"` into bytes.
     * Returns 0 when no recognizable size token is present.
     *
     * GGUF size labels in our catalog are GiB/MiB-based, matching what
     * `Formatter.formatFileSize` produces — so e.g. `"5.44Gb"` means
     * 5.44 × 2^30 bytes. We use the same convention here.
     */
    fun parseDeclaredSizeBytes(description: String): Long {
        val match = SIZE_REGEX.find(description) ?: return 0L
        val num = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase()
        return when (unit) {
            "gb" -> (num * (1L shl 30)).toLong()
            "mb" -> (num * (1L shl 20)).toLong()
            "kb" -> (num * (1L shl 10)).toLong()
            else -> 0L
        }
    }

    private val SIZE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(Gb|Mb|Kb)""", RegexOption.IGNORE_CASE)
}
