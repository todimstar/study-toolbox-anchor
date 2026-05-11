# 学习工具箱静态站点

这是平板端 `学习工具箱` App 的远端首页。当前 APK 已固定访问 `https://toolbox.zakuku.top/`，平板不在手时优先通过更新这个静态页和 `server/` 后端继续迭代。

## 快速部署

1. 把本目录里的 `index.html` 上传覆盖 `toolbox.zakuku.top` 站点根目录的 `index.html`。
2. 确保能通过 HTTPS 访问：
   - `https://toolbox.zakuku.top/`
3. 打开或刷新平板 App，WebView 会加载最新网页。

不要为了普通网页功能重新打包 APK。只有新增原生桥能力、改固定域名或改 Android 容器行为时，才需要修改 `android/app/build.gradle.kts` 并重新安装 APK。

## 当前功能

- 受控首页功能块
- DeepSeek HTML 粘贴、保存、运行、编辑、删除
- 亲子任务投递：家长发布任务，孩子接受/退回/完成，家长查看状态并可重发退回任务。
- 微聊留言互动，依赖后端 `/api/micro-chat/messages`
  - 支持文字、图片、文件附件。
  - 图片消息可点击进入大图预览，并用按钮、滚轮、双击或双指缩放看细节。
  - 在平板 App 内会检测 `window.StudyToolbox`，自动进入孩子视角，不显示家长密钥设置。
  - 在普通浏览器内默认进入家长视角，需要填写 `PARENT_CHAT_KEY` 后才能发送。
- 学习材料投递区占位
- 原生通知桥测试：`StudyToolbox.showNotification(title, message)`

## App 内识别规则

网页通过 `window.StudyToolbox && StudyToolbox.getAppVersion` 判断是否运行在已安装的平板 App 内：

- App WebView 内：自动孩子端视角，隐藏家长密钥输入。
- 普通浏览器内：默认家长端，需要填写 `PARENT_CHAT_KEY` 后才能发送。

## 微聊后端部署

微聊不是纯静态功能，需要把仓库里的 `server` 服务部署起来，并在 Nginx/宝塔站点里把 `/api/` 反向代理到 FastAPI。

示例 Nginx 片段：

```nginx
location ^~ /api/ {
    proxy_pass http://127.0.0.1:8000/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

服务端环境变量参考 `server/.env.example`：

- `PARENT_CHAT_KEY`：家长端发送消息时填写的密钥，不要写进网页源码。
- `CHILD_CHAT_KEY`：孩子端发送消息用的密钥；当前静态页默认是 `child-chat-key-change-me`，如果服务端改了，`index.html` 里的 `childChatKey` 也要同步改。
- `UPLOAD_DIR` / `MAX_UPLOAD_BYTES`：聊天附件保存目录和上传大小上限。

## 后续演进

- 家长端账号和孩子端设备绑定
- 孩子本地 TODO 默认隔离，仅主动分享给家长
- AI 苏格拉底式任务拆分，支持 16-/16+ 模式和动态进度条
- 远程投递 HTML/学习材料
