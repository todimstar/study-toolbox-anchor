package com.silentinstaller.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin 广播接收器。
 * 这是 Device Owner 激活的入口 —— 通过 adb shell dpm set-device-owner 激活。
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        android.util.Log.i("DeviceAdmin", "Device Admin 已启用")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        android.util.Log.w("DeviceAdmin", "Device Admin 已禁用")
    }
}
