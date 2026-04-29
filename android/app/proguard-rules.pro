# Silent Installer ProGuard rules

# Keep DeviceAdminReceiver (required for Device Owner)
-keep class com.silentinstaller.receiver.DeviceAdminReceiver { *; }

# Keep Gson serialization classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
