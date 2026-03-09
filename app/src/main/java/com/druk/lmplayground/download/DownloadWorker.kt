package com.druk.lmplayground.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"

        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_STORAGE_URI = "storage_uri"
        const val KEY_WORK_NAME = "work_name"

        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BYTES_PER_SEC = "speed_bytes_per_sec"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_STATUS = "status"
        const val KEY_PROGRESS = "progress"

        const val KEY_ERROR = "error"

        const val TAG_MODEL_DOWNLOAD = "model_download"

        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val SPEED_WINDOW_MS = 3000L
        private const val BUFFER_SIZE = 64 * 1024
    }

    private val notificationManager = DownloadNotificationManager(applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return failure("Missing download URL")
        val filename = inputData.getString(KEY_FILENAME) ?: return failure("Missing filename")
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: return failure("Missing model name")
        val storageUriString = inputData.getString(KEY_STORAGE_URI) ?: return failure("Missing storage URI")
        val workName = inputData.getString(KEY_WORK_NAME) ?: "download_$filename"

        val tempFile = File(applicationContext.getExternalFilesDir(null), filename)
        val notificationId = notificationManager.getNotificationId(modelName)

        try {
            setForeground(createForegroundInfo(modelName, workName, notificationId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground: ${e.message}")
        }

        try {
            val downloadResult = downloadFile(url, tempFile, modelName, workName, notificationId)
            if (!downloadResult) {
                return failure("Download stopped")
            }

            reportStatus(modelName, -1f, "Moving to storage…", tempFile.length(), tempFile.length(), 0, 0)
            updateForegroundNotification(notificationId, notificationManager.buildCopyingNotification(modelName))

            val storageUri = Uri.parse(storageUriString)
            val copyResult = copyToSafStorage(tempFile, filename, storageUri)
            if (!copyResult) {
                tempFile.delete()
                return failure("Failed to copy to storage")
            }

            tempFile.delete()

            notificationManager.showNotification(
                notificationId,
                notificationManager.buildCompleteNotification(modelName)
            )

            return Result.success()
        } catch (e: IOException) {
            Log.e(TAG, "Download IO error: ${e.message}", e)
            notificationManager.showNotification(
                notificationId,
                notificationManager.buildFailureNotification(modelName, e.message ?: "Network error")
            )
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            tempFile.delete()
            notificationManager.showNotification(
                notificationId,
                notificationManager.buildFailureNotification(modelName, e.message ?: "Download failed")
            )
            return failure(e.message ?: "Download failed")
        }
    }

    private suspend fun downloadFile(
        url: String,
        tempFile: File,
        modelName: String,
        workName: String,
        notificationId: Int
    ): Boolean {
        var existingBytes = 0L
        var append = false

        if (tempFile.exists() && tempFile.length() > 0) {
            existingBytes = tempFile.length()
            append = true
            Log.d(TAG, "Resuming download from byte $existingBytes")
        }

        val requestBuilder = Request.Builder().url(url)
        if (append) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw IOException("HTTP ${response.code}: ${response.message}")
        }

        if (append && response.code != 206) {
            Log.d(TAG, "Server does not support Range, restarting from scratch")
            existingBytes = 0L
            append = false
        }

        val body = response.body ?: throw IOException("Empty response body")

        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) {
            existingBytes + contentLength
        } else {
            -1L
        }

        val speedTracker = SpeedTracker()

        val outputStream = FileOutputStream(tempFile, append)
        val inputStream = body.byteStream()

        var bytesDownloaded = existingBytes
        var lastProgressUpdate = 0L

        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (isStopped) {
                    Log.d(TAG, "Download cancelled")
                    return false
                }

                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead
                speedTracker.addBytes(bytesRead.toLong())

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                    lastProgressUpdate = now

                    val speed = speedTracker.getSpeedBytesPerSec()
                    val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                    val eta = if (totalBytes > 0 && speed > 0) {
                        (totalBytes - bytesDownloaded) / speed
                    } else {
                        -1L
                    }

                    reportStatus(modelName, progress, "Downloading…", bytesDownloaded, totalBytes, speed, eta)

                    updateForegroundNotification(
                        notificationId,
                        notificationManager.buildProgressNotification(
                            modelName, progress, bytesDownloaded, totalBytes, speed, eta, workName
                        )
                    )
                }
            }
            outputStream.flush()
        } finally {
            inputStream.close()
            outputStream.close()
            response.close()
        }

        return true
    }

    private suspend fun reportStatus(
        modelName: String,
        progress: Float,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speed: Long,
        eta: Long
    ) {
        setProgress(
            Data.Builder()
                .putString(KEY_MODEL_NAME, modelName)
                .putFloat(KEY_PROGRESS, progress)
                .putString(KEY_STATUS, status)
                .putLong(KEY_BYTES_DOWNLOADED, bytesDownloaded)
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putLong(KEY_SPEED_BYTES_PER_SEC, speed)
                .putLong(KEY_ETA_SECONDS, eta)
                .build()
        )
    }

    private fun copyToSafStorage(tempFile: File, filename: String, storageUri: Uri): Boolean {
        val documentFile = DocumentFile.fromTreeUri(applicationContext, storageUri) ?: return false

        documentFile.findFile(filename)?.delete()

        val destFile = documentFile.createFile("application/octet-stream", filename) ?: return false

        applicationContext.contentResolver.openOutputStream(destFile.uri)?.use { outputStream ->
            tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream, bufferSize = 8192)
            }
        } ?: return false

        return true
    }

    private suspend fun updateForegroundNotification(notificationId: Int, notification: android.app.Notification) {
        try {
            setForeground(ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: Exception) {
            notificationManager.showNotification(notificationId, notification)
        }
    }

    private fun createForegroundInfo(
        modelName: String,
        workName: String,
        notificationId: Int
    ): ForegroundInfo {
        val notification = notificationManager.buildProgressNotification(
            modelName = modelName,
            progress = 0f,
            bytesDownloaded = 0,
            totalBytes = 0,
            speedBytesPerSec = 0,
            etaSeconds = 0,
            workName = workName
        )
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun failure(message: String): Result {
        return Result.failure(
            Data.Builder()
                .putString(KEY_ERROR, message)
                .build()
        )
    }

    private class SpeedTracker {
        private data class Sample(val timestampMs: Long, val bytes: Long)

        private val samples = mutableListOf<Sample>()

        fun addBytes(bytes: Long) {
            samples.add(Sample(System.currentTimeMillis(), bytes))
            pruneOldSamples()
        }

        fun getSpeedBytesPerSec(): Long {
            pruneOldSamples()
            if (samples.size < 2) return 0

            val windowStart = samples.first().timestampMs
            val windowEnd = samples.last().timestampMs
            val durationMs = windowEnd - windowStart
            if (durationMs <= 0) return 0

            val totalBytes = samples.sumOf { it.bytes }
            return (totalBytes * 1000L) / durationMs
        }

        private fun pruneOldSamples() {
            val cutoff = System.currentTimeMillis() - SPEED_WINDOW_MS
            samples.removeAll { it.timestampMs < cutoff }
        }
    }
}
