package com.silentinstaller.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.silentinstaller.installer.SilentPackageInstaller
import com.silentinstaller.network.ApiClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that:
 * 1. Downloads an APK from the server
 * 2. Installs it silently via PackageInstaller
 * 3. Reports status back to server at each step
 *
 * Input data:
 *   "task_id"    : Int
 *   "apk_id"     : Int
 *   "server_url" : String
 *   "device_id"  : String
 *   "psk"        : String
 *
 * Network constraint: only runs when network is available.
 * Retry policy: exponential backoff, max 3 retries.
 */
class InstallWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "InstallWorker"
        private const val DOWNLOAD_DIR = "silent_installer_apks"

        /**
         * Enqueue an installation task.
         */
        fun enqueue(context: Context, taskId: Int, apkId: Int, serverUrl: String, deviceId: String, psk: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putInt("task_id", taskId)
                .putInt("apk_id", apkId)
                .putString("server_url", serverUrl)
                .putString("device_id", deviceId)
                .putString("psk", psk)
                .build()

            val request = OneTimeWorkRequestBuilder<InstallWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag("install_task_$taskId")
                .build()

            WorkManager.getInstance(context)
                .enqueue(request)

            Log.i(TAG, "Enqueued install task $taskId (APK: $apkId)")
        }
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getInt("task_id", 0)
        val apkId = inputData.getInt("apk_id", 0)
        val serverUrl = inputData.getString("server_url") ?: return Result.failure()
        val deviceId = inputData.getString("device_id") ?: return Result.failure()
        val psk = inputData.getString("psk") ?: return Result.failure()

        val apiClient = ApiClient(serverUrl, deviceId, psk)

        // Create download directory
        val downloadDir = File(applicationContext.filesDir, DOWNLOAD_DIR)
        if (!downloadDir.exists()) downloadDir.mkdirs()

        try {
            // Step 1: Download
            apiClient.reportTaskStatus(taskId, "downloading")
            val apkFile = apiClient.downloadApk(apkId, downloadDir)
            if (apkFile == null) {
                apiClient.reportTaskStatus(taskId, "failed", "APK download failed")
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            // Step 2: Install
            apiClient.reportTaskStatus(taskId, "installing")
            val installer = SilentPackageInstaller(applicationContext)
            val sessionId = installer.install(apkFile)

            if (sessionId < 0) {
                apiClient.reportTaskStatus(taskId, "failed", "Installation session creation failed")
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            // Step 3: Success
            apiClient.reportTaskStatus(taskId, "success")
            Log.i(TAG, "Task $taskId completed successfully")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Task $taskId failed", e)
            apiClient.reportTaskStatus(taskId, "failed", e.message ?: "Unknown error")
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
