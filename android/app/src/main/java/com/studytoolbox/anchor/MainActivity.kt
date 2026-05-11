package com.studytoolbox.anchor

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var toolbar: LinearLayout
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

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return !isAllowedUrl(request.url)
                }
            }
            addJavascriptInterface(ToolboxBridge(), "StudyToolbox")
        }

        setContentView(createLayout())
        loadToolbox()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

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
        return root
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
        if (toolboxUri.scheme == "https" && !toolboxUri.host.isNullOrBlank()) {
            webView.loadUrl(BuildConfig.TOOLBOX_URL)
        } else {
            loadLocalHome()
        }
    }

    private fun isAllowedUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme !in setOf("https")) return false
        return uri.host.equals(toolboxUri.host, ignoreCase = true) ||
            uri.host.equals(Uri.parse(LOCAL_BASE_URL).host, ignoreCase = true)
    }

    private fun loadLocalHome() {
        exitFullscreenMode()
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
        if (::toolbar.isInitialized) {
            setFullscreenMode(false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "学习工具箱提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "TODO、互动审核和工具箱消息提醒"
        }
        notificationManager.createNotificationChannel(channel)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotificationPermissionIfNeeded()
                    return@runOnUiThread
                }

                val notification = Notification.Builder(this@MainActivity, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "学习工具箱")
                    .setContentText(message?.takeIf { it.isNotBlank() } ?: "你有一条新提醒")
                    .setStyle(Notification.BigTextStyle().bigText(message ?: "你有一条新提醒"))
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(System.currentTimeMillis().toInt(), notification)
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
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOCAL_BASE_URL = "https://local.study-toolbox/"
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val NOTIFICATION_CHANNEL_ID = "study_toolbox_default"

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
                  <iframe id="preview" sandbox="allow-scripts allow-forms allow-modals allow-pointer-lock"></iframe>
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
                function setPreview(html) {
                  preview.srcdoc = html || '<p style="font-family:sans-serif;padding:24px">还没有内容</p>';
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
