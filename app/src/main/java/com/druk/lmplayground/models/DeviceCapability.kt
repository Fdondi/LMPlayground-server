package com.druk.lmplayground.models

import android.app.ActivityManager
import android.content.Context

/**
 * Heuristic for refusing to load models that won't fit on this device.
 * Gates ConversationViewModel.loadModel against the dominant Vitals
 * crash for v1.5.x — kernel evicts mmap'd weight pages, the next
 * compute kernel reads an unmapped page and segfaults.
 *
 * Heuristic source: llama.cpp issues #18949 / #14999 / discussion #1876
 * confirm that mmap-backed weights need roughly the model's on-disk size
 * resident in RAM during decode; KV cache and activations add ~10–30 %
 * working set on top.
 */
object DeviceCapability {

    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * True when the model alone exceeds 70 % of total device RAM. Even
     * on a freshly-rebooted device with nothing else running, the kernel
     * cannot keep enough weight pages resident to avoid eviction.
     */
    fun exceedsRamBudget(modelSizeBytes: Long, totalRamBytes: Long): Boolean {
        if (modelSizeBytes <= 0 || totalRamBytes <= 0) return false
        return modelSizeBytes * 10 > totalRamBytes * 7
    }
}
