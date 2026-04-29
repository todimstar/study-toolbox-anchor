package com.silentinstaller.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Wraps Android's PackageInstaller API for silent APK installation.
 *
 * REQUIRES: The app must be set as Device Owner via:
 *   adb shell dpm set-device-owner com.silentinstaller/.receiver.DeviceAdminReceiver
 *
 * Without Device Owner, this will fail with a security exception.
 */
class SilentPackageInstaller(private val context: Context) {

    companion object {
        private const val TAG = "SilentInstaller"
        const val ACTION_INSTALL_COMPLETE = "com.silentinstaller.INSTALL_COMPLETE"
    }

    /**
     * Install an APK silently. Returns the session ID on success, -1 on failure.
     *
     * @param apkFile The APK file to install.
     * @param packageName Expected package name (optional, for later uninstall).
     */
    fun install(apkFile: File, packageName: String = ""): Int {
        if (!apkFile.exists() || !apkFile.canRead()) {
            Log.e(TAG, "APK file not readable: ${apkFile.absolutePath}")
            return -1
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        var sessionId = -1
        try {
            // Create installation session
            sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write APK file to the session
            session.openWrite("package", 0, apkFile.length()).use { output ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }

            // Commit the installation (runs in background by the system)
            val intent = Intent(ACTION_INSTALL_COMPLETE)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()

            Log.i(TAG, "Installation session $sessionId committed for ${apkFile.name}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error: Device Owner not activated. " +
                    "Run: adb shell dpm set-device-owner com.silentinstaller/.receiver.DeviceAdminReceiver", e)
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
        }

        // Clean up the downloaded APK file
        if (apkFile.exists()) {
            apkFile.delete()
        }

        return sessionId
    }

    /**
     * Uninstall a package silently.
     */
    fun uninstall(packageName: String): Boolean {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            packageInstaller.uninstall(
                packageName,
                android.app.PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_INSTALL_COMPLETE),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                ).intentSender
            )
            Log.i(TAG, "Uninstall committed for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall failed for $packageName", e)
            false
        }
    }
}
