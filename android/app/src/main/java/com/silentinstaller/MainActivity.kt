package com.silentinstaller

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.silentinstaller.receiver.DeviceAdminReceiver
import com.silentinstaller.service.KeepAliveService
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceIdText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        deviceIdText = findViewById(R.id.device_id_text)
        val startServiceBtn = findViewById<Button>(R.id.start_service_btn)

        // Show device ID
        val deviceId = getOrCreateDeviceId()
        deviceIdText.text = "设备 ID: $deviceId"

        // Check Device Owner status
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        statusText.text = buildString {
            append("设备型号: ${Build.MODEL}\n")
            append("Android 版本: ${Build.VERSION.RELEASE}\n")
            append("Device Owner: ${if (isDeviceOwner) "✅ 已激活" else "❌ 未激活"}\n")
            if (!isDeviceOwner) {
                append("\n请通过 ADB 激活 Device Owner:\n")
                append("adb shell dpm set-device-owner ${packageName}/${DeviceAdminReceiver::class.java.name}")
            }
        }

        startServiceBtn.setOnClickListener {
            val intent = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.append("\n\n✅ 后台服务已启动")
        }
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("silent_installer", MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
