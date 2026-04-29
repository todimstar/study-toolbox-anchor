package com.silentinstaller.network

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * REST API client for device registration, APK download, and status reporting.
 */
class ApiClient(
    private val baseUrl: String,
    private val deviceId: String = "",
    private val psk: String = ""
) {

    companion object {
        private const val TAG = "ApiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()
    private val gson = com.google.gson.Gson()

    /**
     * Register the device with the server.
     */
    fun registerDevice(deviceId: String, deviceName: String, model: String, psk: String): Boolean {
        val body = mapOf(
            "device_id" to deviceId,
            "device_name" to deviceName,
            "model" to model,
            "psk" to psk
        )
        val requestBody = gson.toJson(body).toRequestBody(json)

        val request = Request.Builder()
            .url("$baseUrl/api/devices/register")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.i(TAG, "Device registration: ${response.code}")
            response.isSuccessful
        } catch (e: IOException) {
            Log.e(TAG, "Device registration failed", e)
            false
        }
    }

    /**
     * Download an APK file using device auth (device_id + PSK).
     */
    fun downloadApk(apkId: Int, downloadDir: File): File? {
        val url = "$baseUrl/api/apps/device-download/$apkId?device_id=$deviceId&psk=$psk"
        val request = Request.Builder().url(url).build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return null
            }

            val body = response.body ?: return null
            val fileName = "install_${apkId}_${System.currentTimeMillis()}.apk"
            val apkFile = File(downloadDir, fileName)

            FileOutputStream(apkFile).use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output, 8192)
                }
            }

            Log.i(TAG, "Downloaded APK: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            apkFile
        } catch (e: IOException) {
            Log.e(TAG, "APK download failed", e)
            null
        }
    }

    /**
     * Report task status to server via device auth endpoint.
     */
    fun reportTaskStatus(taskId: Int, status: String, error: String = ""): Boolean {
        val body = mapOf(
            "device_id" to deviceId,
            "status" to status,
            "error_message" to error
        )
        val requestBody = gson.toJson(body).toRequestBody(json)

        val request = Request.Builder()
            .url("$baseUrl/api/tasks/$taskId/device-status")
            .patch(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "Status report task $taskId → $status (HTTP ${response.code})")
            response.isSuccessful
        } catch (e: IOException) {
            Log.e(TAG, "Status report failed", e)
            false
        }
    }
}
