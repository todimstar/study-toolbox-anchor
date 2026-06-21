package com.studytoolbox.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器
 * 确保设备重启后，后台通知轮询任务能自动恢复
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 重新注册后台轮询任务
            MicroChatPollWorker.enqueue(context)
        }
    }
}
