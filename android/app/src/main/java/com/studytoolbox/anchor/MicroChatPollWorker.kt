package com.studytoolbox.anchor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MicroChatPollWorker(
    context: Context,
    workerParameters: WorkerParameters
) : Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            val latest = fetchLatestParentMessage() ?: return Result.success()
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastSeen = prefs.getInt(KEY_LAST_SEEN_PARENT_ID, 0)
            val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_PARENT_ID, 0)
            if (lastSeen == 0) {
                prefs.edit().putInt(KEY_LAST_SEEN_PARENT_ID, latest.id).apply()
                return Result.success()
            }
            if (latest.id > lastSeen && latest.id > lastNotified) {
                postNotification(latest)
                prefs.edit()
                    .putInt(KEY_LAST_SEEN_PARENT_ID, latest.id)
                    .putInt(KEY_LAST_NOTIFIED_PARENT_ID, latest.id)
                    .apply()
            }
            Result.success()
        } catch (error: Exception) {
            Result.retry()
        }
    }

    private fun fetchLatestParentMessage(): LatestMessage? {
        val url = URL("${BuildConfig.TOOLBOX_URL}api/micro-chat/messages?limit=20")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val items = JSONObject(reader.readText()).optJSONArray("items") ?: return null
            var latest: LatestMessage? = null
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("sender_role") != "parent") continue
                val id = item.optInt("id")
                if (latest == null || id > latest.id) {
                    latest = LatestMessage(
                        id = id,
                        senderName = item.optString("sender_name", "高人"),
                        body = item.optString("body").ifBlank {
                            item.optString("attachment_name").ifBlank { "发来一条新消息" }
                        }
                    )
                }
            }
            return latest
        }
    }

    private fun postNotification(message: LatestMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "微聊新消息",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "微聊新消息提醒和桌面通知点"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("微聊有新消息")
            .setContentText("${message.senderName}：${message.body.take(80)}")
            .setStyle(Notification.BigTextStyle().bigText("${message.senderName}：${message.body}"))
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setNumber(1)
            .setContentIntent(MainActivity.chatPendingIntent(applicationContext))
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        manager.notify(NATIVE_CHAT_NOTIFICATION_ID, notification)
    }

    private data class LatestMessage(
        val id: Int,
        val senderName: String,
        val body: String
    )

    companion object {
        private const val WORK_NAME = "study_toolbox_micro_chat_poll"
        private const val PREFS_NAME = "study_toolbox_native_prefs"
        private const val KEY_LAST_SEEN_PARENT_ID = "last_seen_parent_message_id"
        private const val KEY_LAST_NOTIFIED_PARENT_ID = "last_notified_parent_message_id"
        private const val NOTIFICATION_CHANNEL_ID = "study_toolbox_chat_messages"
        private const val NATIVE_CHAT_NOTIFICATION_ID = 2001

        fun enqueue(context: Context) {
            val network = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val manager = WorkManager.getInstance(context)
            // 启动立刻跑一次：15 分钟周期最短也要等，孩子刚打开 App 不应空等
            manager.enqueue(
                OneTimeWorkRequestBuilder<MicroChatPollWorker>()
                    .setConstraints(network)
                    .build()
            )
            val request = PeriodicWorkRequestBuilder<MicroChatPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
