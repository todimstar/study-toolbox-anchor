# Study Toolbox Anchor

这是一个面向学习平板的受控工具箱系统。当前方向已经放弃“远程静默安装 APK”废案，保留本次对话中真正可用的三块：

- `android/`：已安装到平板的 Android WebView 锚点壳。
- `toolbox-site/`：部署到 `https://toolbox.zakuku.top/` 的工具箱网页。
- `server/`：最小 FastAPI 后端，提供微聊、文件附件和亲子任务投递接口。

## 当前能力

- 平板 App 固定访问 `https://toolbox.zakuku.top/`。
- 网页可远程更新，无需重新安装平板 APK。
- 在 App WebView 内可检测 `window.StudyToolbox`，自动识别为孩子端。
- 普通浏览器访问默认是家长端。
- 工具箱网页包含：
  - 移动端优先的卡片首页和底部导航。
  - DeepSeek HTML 导入、保存、全屏运行。
  - 本地小任务清单。
  - 微聊留言，包含类微信/QQ 的气泡聊天 UI、图片/文件发送和大图缩放预览。
  - 亲子任务投递，家长可发布/重发任务，孩子可接受、退回或标记完成。

## 快速路径

### 更新网页

1. 修改 `toolbox-site/index.html`。
2. 上传覆盖服务器站点根目录的 `index.html`。
3. 平板 App 下次打开或刷新即生效。

### 运行后端

```powershell
cd server
.\venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

生产环境需要在 Nginx/宝塔里把 `https://toolbox.zakuku.top/api/` 反向代理到 `http://127.0.0.1:8000/api/`，并用 `location ^~ /api/` 避免上传图片被静态文件规则拦截。

### 构建 APK

只有拿到平板并需要更新原生能力时才需要重新打包：

```powershell
cd android
$env:JAVA_HOME='C:\Ep\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\hp1080\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat' :app:assembleDebug
```

安装产物：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 废弃内容

以下内容已删除或裁剪，不要再恢复为主路线：

- 旧 React Web 管理端 `web/`。
- 旧 APK 上传/设备/任务/WebSocket/静默安装后端。
- 旧 Device Owner 静默安装计划 `chat.md`。
- 旧 Android 静默安装器代码包 `com.silentinstaller`。

原因：目标平板已有孩子端作为 Device Owner，第三方普通 App 无法稳定实现静默安装。
