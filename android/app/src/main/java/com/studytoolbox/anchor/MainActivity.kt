package com.studytoolbox.anchor

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var toolbar: LinearLayout
    private lateinit var contentColumn: LinearLayout
    private lateinit var contentRoot: FrameLayout
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var voiceRecorder: MediaRecorder? = null
    private var voiceOutputFile: File? = null
    private var voiceRecordStartedAt: Long = 0
    private var pickImagesRequestId: String = ""
    private val pickImagesExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var defaultUserAgent: String = ""
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val toolboxUri = Uri.parse(BuildConfig.TOOLBOX_URL)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        MicroChatPollWorker.enqueue(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            // 关键：启用 viewport 和响应式布局支持
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.displayZoomControls = false

            // 外部站点常靠 window.open 拉播放页；交给 onCreateWindow 统一过白名单
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false

            defaultUserAgent = settings.userAgentString
            // UA 只在这里定一次。WebView 一旦在导航过程中改 UA 会重新发起加载，
            // 把正在进行的跳转打断（表现为跳到一半弹回工具箱）。
            // 去掉 wv / Version 标记，否则 Cloudflare、cdndefend 一律按机器人处理。
            settings.userAgentString = defaultUserAgent
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (BuildConfig.DEBUG) {
                        Log.d(WEB_LOG_TAG, "nav mainFrame=${request.isForMainFrame} $url")
                    }
                    if (isToolboxUrl(url)) {
                        if (request.isForMainFrame) applyBrowsingProfile(url)
                        return false
                    }
                    if (!isAllowedExternalUrl(url)) return true
                    if (request.isForMainFrame) {
                        applyBrowsingProfile(url)
                        return false
                    }
                    // 只有"从工具箱页里往外跳"这一种子框架跳转需要接管：
                    // 运行器 iframe 带 sandbox 且无 allow-same-origin，是不透明源，
                    // Cookie / localStorage 全不可用，人机验证页在里面永远过不去。
                    // 已经在外站内部时，子框架（播放器 iframe 等）必须原样放行。
                    if (isOnToolboxPage()) {
                        openInMainFrame(url)
                        return true
                    }
                    return false
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    applyExternalViewport(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    applyExternalViewport(url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (BuildConfig.DEBUG) {
                        Log.w(WEB_LOG_TAG, "load-error ${error.errorCode} ${error.description} <- ${request.url}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: android.webkit.WebResourceResponse
                ) {
                    if (BuildConfig.DEBUG) {
                        Log.w(WEB_LOG_TAG, "http-error ${response.statusCode} <- ${request.url}")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    return try {
                        startActivityForResult(fileChooserIntent(fileChooserParams), REQUEST_FILE_CHOOSER)
                        true
                    } catch (error: Exception) {
                        this@MainActivity.filePathCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    fullscreenCallback = callback
                    contentColumn.visibility = View.GONE
                    contentRoot.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    setFullscreenMode(true)
                }

                override fun onHideCustomView() {
                    val view = fullscreenView ?: return
                    contentRoot.removeView(view)
                    fullscreenView = null
                    contentColumn.visibility = View.VISIBLE
                    setFullscreenMode(false)
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message
                ): Boolean {
                    // 用一个一次性 WebView 只为拿到目标地址，再回到主框架过白名单，
                    // 顺带把弹窗广告挡在外面。
                    val probe = WebView(this@MainActivity)
                    probe.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            openInMainFrame(request.url)
                            v.post { v.destroy() }
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = probe
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) {
                        Log.d(WEB_LOG_TAG, "console[${message.messageLevel()}] ${message.message()}")
                    }
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    val audioResources = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                    if (audioResources.isEmpty()) {
                        request.deny()
                        return
                    }
                    runOnUiThread {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        ) {
                            request.grant(audioResources)
                        } else {
                            pendingPermissionRequest = request
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_WEB_AUDIO)
                        }
                    }
                }
            }
            addJavascriptInterface(ToolboxBridge(), "StudyToolbox")
        }

        // 人机验证页普遍在第三方框架里种 Cookie，不开这个必然卡在验证循环
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        applyBrowsingProfile(toolboxUri)

        setContentView(createLayout())
        loadToolbox()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadToolbox()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGES) {
            handlePickImagesResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_FILE_CHOOSER) return
        val result = parseFileChooserResult(resultCode, data)
        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WEB_AUDIO) {
            val request = pendingPermissionRequest ?: return
            pendingPermissionRequest = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request.deny()
            }
        }
    }

    override fun onBackPressed() {
        if (fullscreenView != null) {
            (webView.webChromeClient)?.onHideCustomView()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun createLayout(): View {
        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val root = contentColumn

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setBackgroundColor(Color.rgb(250, 250, 252))
        }

        val title = TextView(this).apply {
            text = "学习工具箱"
            textSize = 18f
            setTextColor(Color.rgb(34, 34, 34))
            gravity = Gravity.CENTER_VERTICAL
        }

        toolbar.addView(title, LinearLayout.LayoutParams(0, 44.dp, 1f))
        toolbar.addView(toolbarButton("工具箱") { exitFullscreenMode(); loadToolbox() })
        toolbar.addView(toolbarButton("刷新") { webView.reload() })
        toolbar.addView(toolbarButton("离线") { exitFullscreenMode(); loadLocalHome() })

        root.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        // 外层 FrameLayout 供 HTML5 视频全屏时挂 custom view
        contentRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        return contentRoot
    }

    private fun toolbarButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 14f
            minWidth = 0
            minHeight = 0
            setPadding(10.dp, 0, 10.dp, 0)
            setOnClickListener { onClick() }
        }

    private fun loadToolbox() {
        exitFullscreenMode()
        val target = intent?.data?.takeIf { isToolboxUrl(it) }?.toString() ?: BuildConfig.TOOLBOX_URL
        if (toolboxUri.scheme == "https" && !toolboxUri.host.isNullOrBlank()) {
            applyBrowsingProfile(Uri.parse(target))
            webView.loadUrl(target)
        } else {
            loadLocalHome()
        }
    }

    /** 白名单外站统一走主框架打开，避开运行器 iframe 的 sandbox 不透明源。 */
    private fun openInMainFrame(uri: Uri) {
        if (!isAllowedUrl(uri)) return
        webView.post {
            applyBrowsingProfile(uri)
            webView.loadUrl(uri.toString())
        }
    }

    /**
     * 工具箱页和白名单外站需要两套完全不同的 WebView 策略：
     * 工具箱要禁缓存拿最新版、禁混合内容、文本自动放大；
     * 外站要允许 http 子资源（图床/m3u8）、正常缓存、原生布局、可缩放。
     */
    private fun applyBrowsingProfile(uri: Uri) {
        val external = isAllowedExternalUrl(uri)
        // 从运行器全屏跳到外站时工具栏还是隐藏的，孩子会没有回工具箱的入口
        if (external && ::toolbar.isInitialized && fullscreenView == null) {
            setFullscreenMode(false)
        }
        webView.settings.apply {
            mixedContentMode = if (external) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            cacheMode = if (external) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            layoutAlgorithm = if (external) {
                WebSettings.LayoutAlgorithm.NORMAL
            } else {
                WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            }
            setSupportZoom(external)
            builtInZoomControls = external
            displayZoomControls = false
        }
    }

    /**
     * 孩子的平板是 1280x720，外站（网飞猫这类）在 1280 CSS 像素下左侧栏排不开，
     * 排行榜入口被挤出可视区且滑不动。这里强制页面按 EXTERNAL_LAYOUT_WIDTH 布局，
     * 再由 useWideViewPort + loadWithOverviewMode 整体等比缩放到屏宽。
     * 1280x720 与 1600x900 同为 16:9，缩放后拿到的正好是 1600x900 的版式。
     * 只对白名单外站生效，工具箱页保持原样。
     */
    private fun applyExternalViewport(url: String?) {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (!isAllowedExternalUrl(uri)) return
        webView.evaluateJavascript(EXTERNAL_VIEWPORT_JS, null)
    }

    private fun isToolboxUrl(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        return uri.host.equals(toolboxUri.host, ignoreCase = true) ||
            uri.host.equals(Uri.parse(LOCAL_BASE_URL).host, ignoreCase = true)
    }

    /** 主框架当前是否停在工具箱页（含离线页）。about:blank / 空地址按工具箱处理。 */
    private fun isOnToolboxPage(): Boolean {
        val current = webView.url ?: return true
        if (current.startsWith("about:")) return true
        return isToolboxUrl(Uri.parse(current))
    }

    /** 精确放行的外部站点。新增域名时记得同步 res/xml/network_security_config.xml。 */
    private fun isAllowedExternalUrl(uri: Uri): Boolean {
        if (uri.scheme !in setOf("http", "https")) return false
        val host = uri.host?.lowercase() ?: return false
        return EXTERNAL_ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isAllowedUrl(uri: Uri): Boolean = isToolboxUrl(uri) || isAllowedExternalUrl(uri)

    private fun loadLocalHome() {
        exitFullscreenMode()
        applyBrowsingProfile(Uri.parse(LOCAL_BASE_URL))
        webView.loadDataWithBaseURL(
            LOCAL_BASE_URL,
            LOCAL_HOME_HTML,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun setFullscreenMode(enabled: Boolean) {
        toolbar.visibility = if (enabled) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun exitFullscreenMode() {
        if (fullscreenView != null) {
            (webView.webChromeClient)?.onHideCustomView()
        }
        if (::toolbar.isInitialized) {
            setFullscreenMode(false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val defaultChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "学习工具箱提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "TODO、互动审核和工具箱消息提醒"
            setShowBadge(true)
        }
        val chatChannel = NotificationChannel(
            CHAT_NOTIFICATION_CHANNEL_ID,
            "微聊新消息",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "微聊新消息提醒和桌面通知点"
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(defaultChannel)
        notificationManager.createNotificationChannel(chatChannel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    inner class ToolboxBridge {
        @JavascriptInterface
        fun showNotification(title: String?, message: String?) {
            runOnUiThread {
                postChatNotification(
                    title?.takeIf { it.isNotBlank() } ?: "学习工具箱",
                    message?.takeIf { it.isNotBlank() } ?: "你有一条新提醒",
                    System.currentTimeMillis().toInt()
                )
            }
        }

        @JavascriptInterface
        fun showChatBadge(title: String?, message: String?, messageId: Int) {
            runOnUiThread {
                if (messageId <= 0) return@runOnUiThread
                val prefs = getSharedPreferences(NATIVE_PREFS_NAME, Context.MODE_PRIVATE)
                if (messageId <= prefs.getInt(KEY_LAST_NOTIFIED_PARENT_ID, 0)) return@runOnUiThread
                prefs.edit().putInt(KEY_LAST_NOTIFIED_PARENT_ID, messageId).apply()
                postChatNotification(
                    title?.takeIf { it.isNotBlank() } ?: "微聊有新消息",
                    message?.takeIf { it.isNotBlank() } ?: "高人发来一条新消息",
                    CHAT_NOTIFICATION_ID
                )
            }
        }

        @JavascriptInterface
        fun getAppVersion(): String = "0.1.0-anchor"

        @JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            runOnUiThread {
                setFullscreenMode(enabled)
            }
        }

        @JavascriptInterface
        fun getClipboardText(): String {
            return clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this@MainActivity)
                ?.toString()
                .orEmpty()
        }

        @JavascriptInterface
        fun setClipboardText(text: String?) {
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText("学习工具箱", text.orEmpty())
            )
        }

        @JavascriptInterface
        fun isNativeVoiceRecorderAvailable(): Boolean = true

        /**
         * 原生多图选择（回调式）：网页必须在用户手势的同步执行栈里调用。
         * 返回 requestId；选图完成/取消后经 window.onToolboxImagesPicked 回调网页。
         * 协议见仓库根目录《微聊原生增强-交接说明.md》§4.2。
         */
        @JavascriptInterface
        fun pickImages(maxImages: Int): String {
            val limit = maxImages.coerceIn(1, PICK_IMAGES_MAX)
            val requestId = "pick-${System.currentTimeMillis()}"
            runOnUiThread {
                pickImagesRequestId = requestId
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 系统 Photo Picker：零权限、原生多选 UI（ActivityNotFoundException 交由 catch 兜底）
                    Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                        putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, limit)
                    }
                } else {
                    // API 29–32 回退：多数国产相册支持 GET_CONTENT 多选（EXTRA_ALLOW_MULTIPLE + ClipData）
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                try {
                    startActivityForResult(intent, REQUEST_PICK_IMAGES)
                } catch (error: Exception) {
                    pickImagesRequestId = ""
                    deliverPickedImages(
                        JSONObject().put("ok", false).put("reason", "picker_unavailable")
                    )
                }
            }
            return requestId
        }

        @JavascriptInterface
        fun startNativeVoiceRecording(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_NATIVE_AUDIO)
                }
                return voiceJson(ok = false, reason = "permission_requested")
            }
            return try {
                cancelNativeVoiceRecording()
                val output = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
                voiceOutputFile = output
                voiceRecordStartedAt = System.currentTimeMillis()
                voiceRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@MainActivity)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(output.absolutePath)
                    prepare()
                    start()
                }
                voiceJson(ok = true)
            } catch (error: Exception) {
                releaseVoiceRecorder(deleteFile = true)
                voiceJson(ok = false, reason = "start_failed")
            }
        }

        @JavascriptInterface
        fun stopNativeVoiceRecording(): String {
            val recorder = voiceRecorder ?: return voiceJson(ok = false, reason = "not_recording")
            val output = voiceOutputFile ?: return voiceJson(ok = false, reason = "missing_file")
            val durationMs = System.currentTimeMillis() - voiceRecordStartedAt
            return try {
                recorder.stop()
                recorder.release()
                voiceRecorder = null
                voiceOutputFile = null
                val bytes = output.readBytes()
                val payload = Base64.encodeToString(bytes, Base64.NO_WRAP)
                output.delete()
                JSONObject()
                    .put("ok", true)
                    .put("mime", "audio/mp4")
                    .put("fileName", "voice-${System.currentTimeMillis()}.m4a")
                    .put("durationMs", durationMs)
                    .put("base64", payload)
                    .toString()
            } catch (error: Exception) {
                releaseVoiceRecorder(deleteFile = true)
                voiceJson(ok = false, reason = "stop_failed")
            }
        }

        @JavascriptInterface
        fun cancelNativeVoiceRecording(): String {
            releaseVoiceRecorder(deleteFile = true)
            return voiceJson(ok = true, reason = "cancelled")
        }
    }

    private fun fileChooserIntent(params: FileChooserParams): Intent {
        val acceptTypes = params.acceptTypes.joinToString(",").lowercase()
        val wantsImage = acceptTypes.isBlank() || acceptTypes.contains("image/")
        if (wantsImage) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"
                }
            } else {
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    type = "image/*"
                }
            }
        }
        return params.createIntent()
    }

    private fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        }
        data.data?.let { return arrayOf(it) }
        return WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    }

    private fun postChatNotification(title: String, message: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionIfNeeded()
            return
        }
        val notification = Notification.Builder(this, CHAT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setNumber(1)
            .setContentIntent(chatPendingIntent(this))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun releaseVoiceRecorder(deleteFile: Boolean) {
        try {
            voiceRecorder?.release()
        } catch (_: Exception) {
        }
        voiceRecorder = null
        if (deleteFile) {
            voiceOutputFile?.delete()
        }
        voiceOutputFile = null
        voiceRecordStartedAt = 0
    }

    private fun voiceJson(ok: Boolean, reason: String = ""): String =
        JSONObject()
            .put("ok", ok)
            .put("reason", reason)
            .toString()

    /** 解析系统选图结果，把 URI 列表交给后台线程读取/压缩，再回调网页。 */
    private fun handlePickImagesResult(resultCode: Int, data: Intent?) {
        val requestId = pickImagesRequestId
        pickImagesRequestId = ""
        if (requestId.isEmpty()) return

        if (resultCode != RESULT_OK || data == null) {
            deliverPickedImages(JSONObject().put("ok", false).put("reason", "cancelled"), requestId)
            return
        }

        val uris = collectPickedUris(data)
        if (uris.isEmpty()) {
            deliverPickedImages(JSONObject().put("ok", false).put("reason", "cancelled"), requestId)
            return
        }

        pickImagesExecutor.execute {
            val images = org.json.JSONArray()
            var failed = 0
            for (uri in uris) {
                try {
                    readAndCompressImage(uri)?.let { images.put(it) } ?: run { failed++ }
                } catch (_: Exception) {
                    failed++
                }
            }
            val payload = if (images.length() > 0) {
                JSONObject().put("ok", true).put("images", images)
            } else {
                JSONObject().put("ok", false).put("reason", "read_failed")
            }
            mainHandler.post { deliverPickedImages(payload, requestId) }
        }
    }

    private fun collectPickedUris(data: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { result.add(it) }
            }
        }
        data.data?.let { if (result.isEmpty()) result.add(it) }
        return result
    }

    /** 读取 URI 并压缩到长边 ≤2560、JPEG ~85，返回 { base64, fileName, mime } 或 null。 */
    private fun readAndCompressImage(uri: Uri): JSONObject? {
        val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        // 先探尺寸，避免小图也被重编码（重编码会放大体积、丢失 EXIF 朝向尺寸）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val inSampleSize = if (longestEdge > PICK_IMAGE_MAX_EDGE) {
            var size = 1
            while (longestEdge / (size * 2) >= PICK_IMAGE_MAX_EDGE) size *= 2
            size
        } else {
            1
        }

        val bitmap = BitmapFactory.Options()
            .apply { this.inSampleSize = inSampleSize }
            .let { opts -> BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) }
            ?: return null

        // 若采样后依然超过目标长边，等比精确缩放一次，保证输出≤2560
        val outWidth = bitmap.width
        val outHeight = bitmap.height
        val outLongest = maxOf(outWidth, outHeight)
        val preview = if (outLongest > PICK_IMAGE_MAX_EDGE) {
            val scale = PICK_IMAGE_MAX_EDGE.toFloat() / outLongest
            val w = (outWidth * scale).toInt().coerceAtLeast(1)
            val h = (outHeight * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        val output = ByteArrayOutputStream()
        preview.compress(Bitmap.CompressFormat.JPEG, PICK_IMAGE_JPEG_QUALITY, output)
        preview.recycle()

        val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        val mime = guessImageMimeType(uri)
        return JSONObject()
            .put("base64", base64)
            .put("fileName", pickImageFileName(uri, mime))
            .put("mime", mime)
    }

    private fun guessImageMimeType(uri: Uri): String {
        val fromUri = contentResolver.getType(uri).orEmpty()
        if (fromUri.startsWith("image/")) return fromUri
        // 依据扩展名兜底（PNG/WebP 等 system picker 常返回准确 type）
        return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun pickImageFileName(uri: Uri, mime: String): String {
        val ext = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        // 取原始文件名并剥掉已有扩展名（queryDisplayName 常返回含后缀的完整名）
        val rawName = queryDisplayName(uri)?.takeIf { it.isNotBlank() } ?: "IMG"
        val baseName = rawName.substringBeforeLast('.', rawName).ifBlank { "IMG" }
        return "${baseName}.$ext"
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    /** 在主线程把选图结果经 evaluateJavascript 回调网页。 */
    private fun deliverPickedImages(payload: JSONObject, requestId: String = "") {
        val withId = if (requestId.isNotBlank() && !payload.has("requestId")) {
            payload.put("requestId", requestId)
        } else {
            payload
        }
        val js = "window.onToolboxImagesPicked&&window.onToolboxImagesPicked(${withId})"
        webView.evaluateJavascript(js, null)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOCAL_BASE_URL = "https://local.study-toolbox/"

        /**
         * 精确放行的外部站点（含子域）。这是孩子端受控设备上唯一的白名单缺口，
         * 加域名前先想清楚；同时要同步 res/xml/network_security_config.xml。
         */
        private val EXTERNAL_ALLOWED_HOSTS = listOf(
            "ncat23.com",
            "ncat21.com",  // ncat23.com 会 302 到 www.ncat21.com，不放行等于入口直接断掉
            "kpkuang.fyi"
        )

        private const val REQUEST_NOTIFICATIONS = 1001
        private const val REQUEST_FILE_CHOOSER = 1002
        private const val REQUEST_WEB_AUDIO = 1003
        private const val REQUEST_NATIVE_AUDIO = 1004
        private const val REQUEST_PICK_IMAGES = 1005
        private const val PICK_IMAGES_MAX = 9
        private const val PICK_IMAGE_MAX_EDGE = 2560
        private const val PICK_IMAGE_JPEG_QUALITY = 85
        private const val NOTIFICATION_CHANNEL_ID = "study_toolbox_default"
        private const val CHAT_NOTIFICATION_CHANNEL_ID = "study_toolbox_chat_messages"
        private const val CHAT_NOTIFICATION_ID = 2001
        private const val NATIVE_PREFS_NAME = "study_toolbox_native_prefs"
        private const val KEY_LAST_NOTIFIED_PARENT_ID = "last_notified_parent_message_id"
        private const val WEB_LOG_TAG = "ToolboxWeb"

        /** 外站强制的布局宽度（CSS 像素）。改这个等于改"按多宽的屏幕排版"。 */
        private const val EXTERNAL_LAYOUT_WIDTH = 1600

        /**
         * 每次外站页面可见/加载完成后重设 viewport。站点自己可能带 width=device-width，
         * 也可能压根没有 meta，两种情况都要盖掉，所以是"没有就建、有就改"。
         * 内容没变时不写回，避免触发多余的重排。
         */
        private val EXTERNAL_VIEWPORT_JS = """
            (function () {
              try {
                var want = 'width=$EXTERNAL_LAYOUT_WIDTH';
                var head = document.head || document.getElementsByTagName('head')[0];
                if (!head) return;
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                  meta = document.createElement('meta');
                  meta.setAttribute('name', 'viewport');
                  head.appendChild(meta);
                }
                if (meta.getAttribute('content') !== want) {
                  meta.setAttribute('content', want);
                }
              } catch (e) {}
            })();
        """.trimIndent()

        fun chatPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(BuildConfig.TOOLBOX_URL + "#chatPanel")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private val LOCAL_HOME_HTML = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>学习工具箱</title>
              <style>
                :root {
                  color-scheme: light;
                  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  --bg: #f4f6fc;
                  --card: #ffffff;
                  --ink: #192033;
                  --muted: #667085;
                  --brand: #536dfe;
                  --soft: #eef2ff;
                  --line: #e2e7f1;
                  --danger: #c73535;
                }
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                html, body { height: 100%; }
                body { margin: 0; background: var(--bg); color: var(--ink); overflow: hidden; }
                .shell { height: 100vh; display: grid; grid-template-rows: auto 1fr; }
                .top {
                  padding: 16px 16px 12px;
                  background: linear-gradient(135deg, #eef2ff, #ffffff);
                  border-bottom: 1px solid var(--line);
                }
                .top h1 { margin: 0 0 4px; font-size: 24px; }
                .top p { margin: 0; color: var(--muted); line-height: 1.45; font-size: 14px; }
                .stage { position: relative; min-height: 0; overflow: hidden; }
                .empty {
                  position: absolute; inset: 0;
                  display: grid; place-items: center;
                  padding: 22px;
                  text-align: center;
                }
                .empty-card {
                  width: min(520px, 100%);
                  border-radius: 28px;
                  background: var(--card);
                  padding: 24px;
                  box-shadow: 0 18px 45px rgba(31, 38, 57, .12);
                }
                .empty-card h2 { margin: 0 0 10px; font-size: 22px; }
                .empty-card p { margin: 0; color: var(--muted); line-height: 1.7; }
                iframe {
                  display: none;
                  width: 100%;
                  height: 100%;
                  border: 0;
                  background: #fff;
                }
                body.running .shell { grid-template-rows: 1fr; }
                body.running .top { display: none; }
                body.running iframe { display: block; }
                body.running .empty { display: none; }
                .fab-row {
                  position: fixed;
                  right: 14px;
                  bottom: 16px;
                  display: flex;
                  align-items: center;
                  gap: 10px;
                  z-index: 20;
                  touch-action: none;
                  user-select: none;
                }
                .drag-grip {
                  width: 38px;
                  height: 38px;
                  display: grid;
                  place-items: center;
                  border-radius: 999px;
                  background: rgba(255,255,255,.92);
                  border: 1px solid var(--line);
                  color: #536dfe;
                  font-weight: 900;
                  box-shadow: 0 8px 24px rgba(31, 38, 57, .12);
                }
                button {
                  border: 0;
                  border-radius: 999px;
                  padding: 11px 15px;
                  background: var(--brand);
                  color: #fff;
                  font: inherit;
                  font-weight: 800;
                  box-shadow: 0 8px 24px rgba(83, 109, 254, .28);
                }
                button.secondary { background: #fff; color: #3f50c8; border: 1px solid var(--line); box-shadow: 0 8px 24px rgba(31, 38, 57, .08); }
                button.danger { background: #fff0f0; color: var(--danger); box-shadow: none; }
                .scrim {
                  position: fixed; inset: 0;
                  background: rgba(15, 23, 42, .34);
                  opacity: 0;
                  pointer-events: none;
                  transition: opacity .22s ease;
                  z-index: 30;
                }
                .drawer {
                  position: fixed;
                  left: 0; right: 0; bottom: 0;
                  height: min(82vh, 680px);
                  border-radius: 28px 28px 0 0;
                  background: #fff;
                  transform: translateY(100%);
                  transition: transform .25s ease;
                  z-index: 40;
                  display: grid;
                  grid-template-rows: auto 1fr;
                  box-shadow: 0 -18px 50px rgba(15, 23, 42, .2);
                  overflow: hidden;
                }
                body.drawer-open .scrim { opacity: 1; pointer-events: auto; }
                body.drawer-open .drawer { transform: translateY(0); }
                .drawer-head {
                  padding: 12px 16px 10px;
                  border-bottom: 1px solid var(--line);
                }
                .handle {
                  width: 48px;
                  height: 5px;
                  border-radius: 999px;
                  background: #d5dbea;
                  margin: 0 auto 12px;
                }
                .tabs { display: flex; gap: 8px; margin-top: 12px; }
                .tabs button { flex: 1; box-shadow: none; padding: 10px; }
                .tabs button.off { background: var(--soft); color: #3f50c8; }
                .drawer-body { overflow: auto; padding: 14px 16px 28px; }
                input, textarea {
                  width: 100%;
                  border: 1px solid var(--line);
                  border-radius: 16px;
                  padding: 12px;
                  margin: 7px 0;
                  font: inherit;
                  background: #fff;
                  color: var(--ink);
                }
                textarea { min-height: 220px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
                .panel { display: none; }
                .panel.active { display: block; }
                .item { border: 1px solid var(--line); border-radius: 18px; padding: 13px; margin: 10px 0; background: #fbfcff; }
                .item strong { display: block; margin-bottom: 4px; }
                small, .muted { color: var(--muted); }
                .row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
                .draft-box {
                  margin: 8px 0;
                  padding: 12px;
                  border-radius: 16px;
                  background: #f7f9ff;
                  border: 1px dashed #b9c4ff;
                  color: #4051c9;
                  line-height: 1.55;
                }
              </style>
            </head>
            <body>
              <div class="shell">
                <header class="top">
                  <h1>离线 HTML 工具箱</h1>
                  <p>运行区会占满屏幕；从右下角抽屉管理本地 HTML。</p>
                </header>
                <main class="stage">
                  <div class="empty">
                    <div class="empty-card">
                      <h2>选择一个 HTML 开始</h2>
                      <p>适合运行小游戏、交互教程、AI 生成的小网页。点击“抽屉”添加或选择记录。</p>
                    </div>
                  </div>
                  <iframe id="preview" sandbox="allow-scripts allow-forms allow-modals allow-pointer-lock allow-top-navigation-by-user-activation"></iframe>
                </main>
              </div>

              <div id="fabRow" class="fab-row">
                <div id="dragGrip" class="drag-grip" title="拖动">↕</div>
                <button class="secondary" onclick="clearPreview()">退出运行</button>
                <button onclick="openDrawer()">抽屉</button>
              </div>

              <div class="scrim" onclick="closeDrawer()"></div>
              <aside class="drawer" aria-label="HTML 管理抽屉">
                <div class="drawer-head">
                  <div class="handle"></div>
                  <strong>HTML 管理</strong>
                  <div class="tabs">
                    <button id="tabList" onclick="switchPanel('listPanel')">记录</button>
                    <button id="tabEditor" class="off" onclick="switchPanel('editorPanel')">新建/编辑</button>
                  </div>
                </div>
                <div class="drawer-body">
                  <section id="listPanel" class="panel active">
                    <p class="muted">点击“全屏运行”后会自动收起抽屉。</p>
                    <div id="list"></div>
                  </section>
                  <section id="editorPanel" class="panel">
                    <input id="title" placeholder="标题，例如：英语单词小游戏" />
                    <div id="draftStatus" class="draft-box">还没有导入 HTML。推荐点“从剪贴板导入”，大文件不会卡输入框。</div>
                    <textarea id="code" placeholder="也可以直接粘贴到这里；App 会拦截粘贴，只保存草稿，不把全文塞进输入框。"></textarea>
                    <div class="row">
                      <button type="button" onclick="importClipboard()">从剪贴板导入</button>
                      <button onclick="saveHtml()">保存</button>
                      <button class="secondary" onclick="runRaw()">预览当前内容</button>
                      <button class="danger" onclick="clearDraft()">清空草稿</button>
                    </div>
                  </section>
                </div>
              </aside>

              <script>
                const key = 'study_toolbox_html_records_v1';
                const list = document.getElementById('list');
                const preview = document.getElementById('preview');
                const fabRow = document.getElementById('fabRow');
                const dragGrip = document.getElementById('dragGrip');
                const codeInput = document.getElementById('code');
                const draftStatus = document.getElementById('draftStatus');
                const body = document.body;
                let draftHtml = '';
                function records() {
                  try { return JSON.parse(localStorage.getItem(key) || '[]'); } catch { return []; }
                }
                function write(records) {
                  localStorage.setItem(key, JSON.stringify(records));
                  render();
                }
                function openDrawer() { body.classList.add('drawer-open'); }
                function closeDrawer() { body.classList.remove('drawer-open'); }
                function switchPanel(id) {
                  document.getElementById('listPanel').classList.toggle('active', id === 'listPanel');
                  document.getElementById('editorPanel').classList.toggle('active', id === 'editorPanel');
                  document.getElementById('tabList').classList.toggle('off', id !== 'listPanel');
                  document.getElementById('tabEditor').classList.toggle('off', id !== 'editorPanel');
                }
                function formatBytes(text) {
                  const bytes = new Blob([text || '']).size;
                  if (bytes < 1024) return bytes + ' B';
                  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
                  return (bytes / 1024 / 1024).toFixed(2) + ' MB';
                }
                function setDraft(html, source) {
                  draftHtml = html || '';
                  codeInput.value = '';
                  draftStatus.innerHTML = draftHtml
                    ? '已导入 HTML 草稿：<strong>' + formatBytes(draftHtml) + '</strong> · ' + escapeHtml(source || '草稿') + '<br><small>为了不卡顿，正文不会显示在输入框里；保存或预览会使用这份草稿。</small>'
                    : '还没有导入 HTML。推荐点“从剪贴板导入”，大文件不会卡输入框。';
                }
                function importClipboard() {
                  if (!(window.StudyToolbox && StudyToolbox.getClipboardText)) {
                    return alert('当前环境没有剪贴板桥，请直接粘贴到输入框。');
                  }
                  const text = StudyToolbox.getClipboardText();
                  if (!text.trim()) return alert('剪贴板里没有文本');
                  setDraft(text, '来自剪贴板');
                }
                codeInput.addEventListener('paste', event => {
                  const text = event.clipboardData && event.clipboardData.getData('text/plain');
                  if (!text) return;
                  event.preventDefault();
                  setDraft(text, '来自粘贴');
                });
                function saveHtml() {
                  const title = document.getElementById('title').value.trim() || '未命名 HTML';
                  const html = draftHtml || codeInput.value;
                  if (!html.trim()) return alert('先粘贴 HTML 内容');
                  const all = records();
                  all.unshift({ id: Date.now().toString(36), title, html, source: '本地创建', updatedAt: new Date().toLocaleString() });
                  write(all);
                  document.getElementById('title').value = '';
                  setDraft('', '');
                  switchPanel('listPanel');
                }
                function escapeExternalLinks(html) {
                  try {
                    const doc = new DOMParser().parseFromString(html, 'text/html');
                    let changed = false;
                    doc.querySelectorAll('a[href]').forEach(anchor => {
                      const href = anchor.getAttribute('href') || '';
                      if (!/^https?:\/\//i.test(href)) return;
                      try {
                        if (new URL(href).origin === location.origin) return;
                      } catch (error) {
                        return;
                      }
                      anchor.setAttribute('target', '_top');
                      changed = true;
                    });
                    return changed ? '<!doctype html>\n' + doc.documentElement.outerHTML : html;
                  } catch (error) {
                    return html;
                  }
                }
                function setPreview(html) {
                  preview.srcdoc = html ? escapeExternalLinks(html) : '<p style="font-family:sans-serif;padding:24px">还没有内容</p>';
                  body.classList.add('running');
                  if (window.StudyToolbox && StudyToolbox.setFullscreen) StudyToolbox.setFullscreen(true);
                  closeDrawer();
                }
                function runRaw() {
                  setPreview(draftHtml || codeInput.value);
                }
                function clearDraft() {
                  setDraft('', '');
                  codeInput.value = '';
                }
                function runSaved(id) {
                  const item = records().find(x => x.id === id);
                  if (item) setPreview(item.html);
                }
                function clearPreview() {
                  preview.removeAttribute('srcdoc');
                  body.classList.remove('running');
                  if (window.StudyToolbox && StudyToolbox.setFullscreen) StudyToolbox.setFullscreen(false);
                }
                function editSaved(id) {
                  const item = records().find(x => x.id === id);
                  if (!item) return;
                  document.getElementById('title').value = item.title;
                  setDraft(item.html, '来自已保存记录');
                  deleteSaved(id, false);
                  switchPanel('editorPanel');
                  openDrawer();
                }
                function deleteSaved(id, ask = true) {
                  if (ask && !confirm('删除这条 HTML 记录？')) return;
                  write(records().filter(x => x.id !== id));
                }
                function render() {
                  const all = records();
                  list.innerHTML = all.length ? all.map(item =>
                    '<div class="item">' +
                    '<strong>' + escapeHtml(item.title) + '</strong><br>' +
                    '<small>' + escapeHtml(item.source) + ' · ' + escapeHtml(item.updatedAt) + '</small><br>' +
                    '<div class="row">' +
                    '<button onclick="runSaved(\'' + item.id + '\')">全屏运行</button>' +
                    '<button class="secondary" onclick="editSaved(\'' + item.id + '\')">编辑</button>' +
                    '<button class="danger" onclick="deleteSaved(\'' + item.id + '\')">删除</button>' +
                    '</div>' +
                    '</div>'
                  ).join('') : '<p class="muted">暂无记录。去“新建/编辑”粘贴一个 HTML 试试。</p>';
                }
                function escapeHtml(text) {
                  return String(text).replace(/[&<>"']/g, ch => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[ch]));
                }
                function clamp(value, min, max) {
                  return Math.min(max, Math.max(min, value));
                }
                function installFabDrag() {
                  let dragging = false;
                  let offsetX = 0;
                  let offsetY = 0;
                  dragGrip.addEventListener('pointerdown', event => {
                    dragging = true;
                    dragGrip.setPointerCapture(event.pointerId);
                    const rect = fabRow.getBoundingClientRect();
                    offsetX = event.clientX - rect.left;
                    offsetY = event.clientY - rect.top;
                  });
                  dragGrip.addEventListener('pointermove', event => {
                    if (!dragging) return;
                    const x = clamp(event.clientX - offsetX, 6, innerWidth - fabRow.offsetWidth - 6);
                    const y = clamp(event.clientY - offsetY, 6, innerHeight - fabRow.offsetHeight - 6);
                    fabRow.style.left = x + 'px';
                    fabRow.style.top = y + 'px';
                    fabRow.style.right = 'auto';
                    fabRow.style.bottom = 'auto';
                  });
                  dragGrip.addEventListener('pointerup', () => { dragging = false; });
                  dragGrip.addEventListener('pointercancel', () => { dragging = false; });
                }
                render();
                installFabDrag();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
