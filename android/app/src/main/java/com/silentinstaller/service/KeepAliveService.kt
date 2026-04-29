package com.silentinstaller.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.silentinstaller.MainActivity
import com.silentinstaller.SilentInstallerApp
import com.silentinstaller.network.ApiClient
import com.silentinstaller.network.WebSocketClient
import com.silentinstaller.worker.InstallWorker
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service that maintains the WebSocket connection and keeps the app alive.
 *
 * This is the brain of the tablet app. It starts on boot, holds the long-lived
 * WebSocket connection, and delegates download + install tasks to WorkManager.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "silent_installer"
    }

    private lateinit var wsClient: WebSocketClient
    private lateinit var apiClient: ApiClient
    private lateinit var deviceId: String
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        deviceId = getOrCreateDeviceId()
        val serverUrl = com.silentinstaller.BuildConfig.SERVER_BASE_URL
        val wsUrl = com.silentinstaller.BuildConfig.WS_URL
        val psk = com.silentinstaller.BuildConfig.DEVICE_PSK

        apiClient = ApiClient(serverUrl, deviceId, psk)
        wsClient = WebSocketClient(wsUrl, deviceId, psk)

        wsClient.setOnTaskReceived { task ->
            handleNewTask(task)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Foreground service started")

        // Register device with server, then connect WebSocket
        serviceScope.launch {
            registerDevice()
            wsClient.connect()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wsClient.disconnect()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ── Task handling ─────────────────────────────────────────────

    private fun handleNewTask(task: JsonObject) {
        val taskId = task.get("task_id")?.asInt ?: return
        val apkId = task.get("apk_id")?.asInt ?: return
        val serverUrl = com.silentinstaller.BuildConfig.SERVER_BASE_URL

        Log.i(TAG, "New task received: task=$taskId, apk=$apkId")

        // Report "downloading" immediately via WebSocket
        wsClient.sendTaskStatus(taskId, "downloading")

        // Enqueue the download + install work via WorkManager
        val psk = com.silentinstaller.BuildConfig.DEVICE_PSK
        InstallWorker.enqueue(this, taskId, apkId, serverUrl, deviceId, psk)
    }

    // ── Device registration ───────────────────────────────────────

    private suspend fun registerDevice() {
        try {
            val model = Build.MODEL
            val deviceName = "BZC-W00"  // could be dynamic
            val psk = com.silentinstaller.BuildConfig.DEVICE_PSK

            val success = apiClient.registerDevice(deviceId, deviceName, model, psk)
            Log.i(TAG, "Device registration: ${if (success) "success" else "failed"}")
        } catch (e: Exception) {
            Log.e(TAG, "Device registration error", e)
        }
    }

    // ── Device ID ─────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            id = if (androidId != null && androidId != "9774d56d682e549c") {
                androidId
            } else {
                UUID.randomUUID().toString()
            }
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    // ── Notification ──────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SilentInstallerApp.CHANNEL_ID)
            .setContentTitle("学习助手")
            .setContentText("后台运行中，等待安装任务...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
