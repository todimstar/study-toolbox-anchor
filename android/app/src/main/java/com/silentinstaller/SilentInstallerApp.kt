package com.silentinstaller

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.silentinstaller.service.KeepAliveService

class SilentInstallerApp : Application() {

    companion object {
        const val CHANNEL_ID = "silent_installer_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "静默安装助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台运行以接收安装任务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
