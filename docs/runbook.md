# Runbook

## 环境变量

参考 `server/.env.example`：

```env
HOST=0.0.0.0
PORT=8000
DATABASE_URL=sqlite+aiosqlite:///./toolbox.db
PARENT_CHAT_KEY=change-parent-chat-key
CHILD_CHAT_KEY=child-chat-key-change-me
```

注意：

- `PARENT_CHAT_KEY` 只给家长使用，不写入孩子端页面。
- `CHILD_CHAT_KEY` 当前写在 `toolbox-site/index.html` 的 `childChatKey` 常量里，服务端修改后网页也要同步。

## 启动后端

```powershell
cd C:\Ep\Code\AIvibeCoding\originInstallAPK\server
.\venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 服务器首次部署

需要上传的内容：

- `toolbox-site/index.html` -> `toolbox.zakuku.top` 站点根目录的 `index.html`
- `server/app/` -> 后端运行目录的 `app/`
- `server/requirements.txt` -> 后端运行目录
- `server/.env.example` -> 上传后复制为服务器上的 `.env`

不要上传：

- `server/venv/`
- `server/.env`
- `toolbox.db`
- Android 构建产物

推荐服务器目录：

```bash
/opt/study-toolbox/server
```

首次初始化：

```bash
mkdir -p /opt/study-toolbox/server
cd /opt/study-toolbox/server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
nano .env
```

`.env` 至少改：

```env
HOST=127.0.0.1
PORT=8000
DATABASE_URL=sqlite+aiosqlite:///./toolbox.db
PARENT_CHAT_KEY=换成家长端口令
CHILD_CHAT_KEY=child-chat-key-change-me
UPLOAD_DIR=./uploads
MAX_UPLOAD_BYTES=20971520
```

注意：`CHILD_CHAT_KEY` 当前必须和 `toolbox-site/index.html` 里的 `childChatKey` 常量一致。
微聊附件默认保存在 `UPLOAD_DIR/micro-chat/`，通过 `/api/uploads/micro-chat/...` 访问；大小上限由 `MAX_UPLOAD_BYTES` 控制。

手动试跑：

```bash
cd /opt/study-toolbox/server
source .venv/bin/activate
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

另开终端测试：

```bash
curl http://127.0.0.1:8000/
curl http://127.0.0.1:8000/api/micro-chat/messages
```

任务投递接口：

```bash
curl http://127.0.0.1:8000/api/tasks?role=parent \
  -H "X-Task-Key: <PARENT_CHAT_KEY>"

curl -X POST http://127.0.0.1:8000/api/tasks \
  -H "Content-Type: application/json" \
  -H "X-Task-Key: <PARENT_CHAT_KEY>" \
  -d '{"title":"整理错题","description":"完成后告诉我","granularity":"normal","created_by_name":"家长"}'

curl -X PATCH "http://127.0.0.1:8000/api/tasks/1?role=child" \
  -H "Content-Type: application/json" \
  -H "X-Task-Key: <CHILD_CHAT_KEY>" \
  -d '{"status":"accepted","child_reply":""}'
```

远程 HTML 投递接口：

```bash
curl -X POST http://127.0.0.1:8000/api/remote-html \
  -H "Content-Type: application/json" \
  -H "X-Remote-Key: <PARENT_CHAT_KEY>" \
  -d '{"title":"互动教程","description":"点开全屏运行","html":"<h1>Hello</h1>","created_by_name":"高人"}'

curl "http://127.0.0.1:8000/api/remote-html?role=child" \
  -H "X-Remote-Key: <CHILD_CHAT_KEY>"
```

图片/文件消息测试：

```bash
curl -X POST http://127.0.0.1:8000/api/micro-chat/messages/with-file \
  -H "X-Chat-Key: <PARENT_CHAT_KEY>" \
  -F "sender_role=parent" \
  -F "sender_name=家长" \
  -F "body=图片说明" \
  -F "upload=@/path/to/file.png"
```

语音消息会以音频文件走同一个 `/api/micro-chat/messages/with-file` 接口；服务端会根据 `audio/*` MIME 自动写入 `message_type=audio`。

## systemd 常驻服务

创建服务文件：

```bash
nano /etc/systemd/system/study-toolbox.service
```

内容：

```ini
[Unit]
Description=Study Toolbox FastAPI
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/study-toolbox/server
ExecStart=/opt/study-toolbox/server/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

启用：

```bash
systemctl daemon-reload
systemctl enable --now study-toolbox
systemctl status study-toolbox
```

维护：

```bash
systemctl restart study-toolbox
journalctl -u study-toolbox -f
```

## Nginx / 宝塔反向代理

`toolbox.zakuku.top` 站点需要把 `/api/` 转发到 FastAPI：

```nginx
location ^~ /api/ {
    proxy_pass http://127.0.0.1:8000/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

必须使用 `^~ /api/`，否则宝塔默认的图片静态缓存正则可能抢走 `/api/uploads/*.png`，导致聊天图片显示为破图。

## 冒烟测试

后端导入和路由：

```powershell
cd server
.\venv\Scripts\python.exe -c "from app.main import app; print(app.title); print([r.path for r in app.routes if '/api/' in r.path])"
```

网页脚本语法：

```powershell
node -e "const fs=require('fs'); const html=fs.readFileSync('toolbox-site/index.html','utf8'); const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]); scripts.forEach((s,i)=>{ new Function(s); console.log('script', i+1, 'ok'); });"
```

Android 构建：

```powershell
cd android
$env:JAVA_HOME='C:\Ep\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\hp1080\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat' --no-daemon --max-workers=1 :app:assembleDebug
```

当前 Windows 环境曾因本机分页文件/原生内存不足导致 Gradle daemon 崩溃，因此 `android/gradle.properties` 使用较保守的 `-Xmx1024m -XX:MaxMetaspaceSize=512m`。构建产物为：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

2026-06-21+ APK 新能力：

- 孩子端 WebView 可打开系统文件选择器上传图片、音频和文件。
- 孩子端 WebView 可申请麦克风权限录制语音留言。
- 原生 `MicroChatPollWorker` 会在系统允许时约 15 分钟轮询一次家长微聊消息，并用系统通知支撑桌面通知点。

## 常见问题

### 平板端没有进入孩子视角

检查页面是否在已安装 App 的 WebView 内打开。普通浏览器不会有 `window.StudyToolbox`，会默认家长端。

### 微聊显示接口不可用

检查：

1. FastAPI 是否运行。
2. Nginx 是否配置 `/api/` 反向代理。
3. `https://toolbox.zakuku.top/api/micro-chat/messages` 是否能返回 JSON。

### 微聊新消息像是丢失

检查 `/api/micro-chat/messages` 的首次加载逻辑是否取“最新一页”。当消息总数超过默认分页上限时，如果服务端按 `id asc limit 80` 返回，会只看到最早 80 条，新消息要等多次轮询才出现，看起来像丢失。当前实现：`since_id=0` 返回最新 80 条并按升序展示；`since_id>0` 才返回增量。

### 微聊没有系统通知或桌面小红点

当前 APK 有两层提醒：网页运行时调用 `StudyToolbox.showNotification(title, message)` 原生桥；2026-06-21+ APK 还会用 `MicroChatPollWorker` 后台轮询家长消息。Android 通知存在时，支持通知点的启动器通常会在 App 图标上显示小红点。若没有效果，检查：

1. 平板系统是否授予“学习工具箱”通知权限。
2. 系统设置里该 App 的“通知点/桌面角标”是否开启。
3. 是否安装了 2026-06-21+ APK；旧 APK 没有 `MicroChatPollWorker`。
4. WorkManager 是系统调度，最快也通常是 15 分钟级别，不保证锁屏、杀后台或孩子端禁用时段实时触达。
5. 后续若要接近 QQ/微信体验，需要原生推送或长连接：FCM/厂商推送/SSE/WebSocket + 前台服务兜底。

### 家长端发送失败

检查浏览器内填写的家长密钥是否等于服务端 `PARENT_CHAT_KEY`。

### 孩子端图片或语音发不出去

检查是否安装 2026-06-21+ APK。旧 APK 没有 WebView 文件选择器和麦克风权限桥，网页代码即使已更新，也无法在 App 内完成图片选择或语音录制。

### 粘贴 HTML 卡顿

使用“从剪贴板导入”。页面会把大段 HTML 存为草稿，不再把全文渲染到 textarea。

### 图片看不清细节

点击聊天图片进入黑底预览后，可用“放大/缩小/复位”按钮、鼠标滚轮、双击或触屏双指缩放。放大后在预览区拖动/滚动查看细节。

### 孩子退回任务后如何重发

家长端任务卡片会出现“重发”按钮。点击后任务回到“待孩子处理”，并在卡片元信息里显示 `重发 N 次`。
