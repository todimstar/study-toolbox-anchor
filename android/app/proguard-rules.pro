# Study Toolbox Anchor ProGuard rules
# WebView JS 桥：release minify 会裁掉未被 Java 侧调用的 @JavascriptInterface 方法
-keepattributes JavascriptInterface
-keepclassmembers class com.studytoolbox.anchor.MainActivity$ToolboxBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# JPush：只 keep SDK，不全局 -dontoptimize（会跟 R8 打架）
-dontwarn cn.jpush.**
-keep class cn.jpush.** { *; }
-keep class * extends cn.jpush.android.service.JPushMessageReceiver { *; }
-dontwarn cn.jiguang.**
-keep class cn.jiguang.** { *; }
