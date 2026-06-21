# Architecture

## 系统边界

```text
平板 App（已安装）
  -> WebView 固定打开 https://toolbox.zakuku.top/
  -> 注入 window.StudyToolbox JSBridge

toolbox-site/index.html
  -> 孩子端：App 内自动识别
  -> 家长端：普通浏览器默认识别
  -> 移动端优先的单页工具箱 UI
  -> /api/micro-chat/messages
  -> /api/remote-html
  -> /api/tasks

server/app
  -> FastAPI
  -> SQLite
  -> micro_chat_messages
  -> remote_html_deliveries
  -> study_tasks
```

## Android 锚点壳

Android 包名是 `com.studytoolbox.anchor`。它只做稳定的受控 WebView 容器，不再申请 Device Owner 或静默安装相关权限。

已提供的 JSBridge：

- `StudyToolbox.showNotification(title, message)`：网页触发原生通知。
- `StudyToolbox.getAppVersion()`：网页判断当前运行在工具箱 App 内。
- `StudyToolbox.setFullscreen(enabled)`：离线 HTML 运行器进入或退出沉浸式运行态。
- `StudyToolbox.getClipboardText()` / `StudyToolbox.setClipboardText(text)`：大段 HTML 从剪贴板导入，避免 textarea 卡顿。

2026-06-21+ APK 额外补齐：

- WebView 文件选择器：让孩子端也能从微聊上传图片、音频和文件。
- WebView 麦克风授权：让网页 `MediaRecorder` 能在 App 内录制语音留言。
- `MicroChatPollWorker`：Android WorkManager 每 15 分钟左右轮询微聊家长消息，触发系统通知和支持通知点的桌面红点。

## 网页身份识别

不需要重新安装 APK。网页通过检测：

```js
!!(window.StudyToolbox && StudyToolbox.getAppVersion)
```

判断是否运行在平板 App 内。

- 为真：孩子端视角，隐藏家长密钥输入。
- 为假：浏览器视角，默认家长端，需要 `PARENT_CHAT_KEY` 才能发送家长消息。

## 微聊数据模型

表：`micro_chat_messages`

字段：

- `id`
- `sender_role`: `parent` 或 `child`
- `sender_name`
- `sender_avatar`
- `body`
- `message_type`: `text | image | audio | file`
- `attachment_url`
- `attachment_name`
- `attachment_mime`
- `attachment_size`
- `created_at`

## 任务投递数据模型

表：`study_tasks`

字段：

- `id`
- `title`
- `description`
- `granularity`
- `status`: `pending | accepted | returned | done`
- `resubmit_count`
- `created_by_name`
- `child_reply`
- `created_at`
- `updated_at`

## 远程 HTML 投递数据模型

表：`remote_html_deliveries`

字段：

- `id`
- `title`
- `description`
- `html`
- `created_by_name`
- `created_at`

## 设计取舍

- 2026-06-22 前存在一次装机窗口，应优先安装 2026-06-21+ APK；窗口结束后继续回到“网页 + 后端远程升级”为主。
- 工具箱页面优先移动端和平板触控体验：底部导航、48px 触控目标、toast 反馈、全屏 HTML 运行器。
- 当前微聊使用 3 秒轮询，足够 MVP；后续可升级 SSE 或 WebSocket。
- 微聊首次加载必须取最新一页消息，而不是最早一页；否则消息超过默认分页上限后，新消息会看起来“丢失”。
- 当前 APK 同时有网页通知桥和 WorkManager 微聊轮询。桌面图标小红点依赖 Android 通知权限、启动器通知点支持和系统调度，不保证像微信/QQ 一样实时。
- 孩子端发送密钥目前在前端内置，适合家庭内轻量使用；后续拿到平板后应升级为 App 注入设备密钥。
- 任务投递当前复用微聊口令体系，先保证平板离手后的远程可升级性；后续再拆出正式账号和设备绑定。
