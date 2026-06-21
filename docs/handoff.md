# Handoff

最后整理日期：2026-06-21

## 当前项目状态

项目已从“远程静默安装 APK”转向“学习工具箱锚点 App + 可远程更新网页 + 微聊后端”。

2026-06-22 前还有一次 APK 安装窗口，应优先安装 2026-06-21+ APK；之后在拿不到平板的情况下，应优先通过 `toolbox-site/index.html` 和 `server/` 升级功能，不要依赖重新安装 APK。

## 当前可用入口

- 平板 App 固定站点：`https://toolbox.zakuku.top/`
- 静态站点源码：`toolbox-site/index.html`
- 微聊 API：`/api/micro-chat/messages`
- 后端入口：`server/app/main.py`
- Android 主 Activity：`android/app/src/main/java/com/studytoolbox/anchor/MainActivity.kt`
- 微聊后台轮询 Worker：`android/app/src/main/java/com/studytoolbox/anchor/MicroChatPollWorker.kt`

## 已完成

- 清理旧 React Web 管理端。
- 清理旧静默安装后端。
- 清理旧 Android 静默安装器代码。
- 添加工具箱 WebView 锚点壳。
- 添加受控工具箱网页。
- 添加 DeepSeek HTML 导入/保存/运行。
- 添加运行态沉浸和可拖动控制条。
- 添加大 HTML 剪贴板导入优化。
- 添加微聊留言后端和聊天 UI。
- 添加 App 内孩子端自动识别。
- 升级工具箱网页 UI：移动端底部导航、模块页隐藏首页横幅、全屏 HTML 运行器、小任务卡片列表、微聊气泡和 toast 反馈。
- 微聊支持图片/文件发送，附件走 `/api/uploads/...`；Nginx 反代必须用 `location ^~ /api/` 避免图片静态规则抢走上传图片。
- 微聊支持微信/QQ 式语音留言：按住说话、松开发送、上滑或取消按钮放弃；App 内优先走原生 M4A 录音桥，浏览器端回退 `MediaRecorder`。
- 2026-06-21+ APK 支持图库式图片选择、普通文件选择和麦克风权限桥，解决孩子端图片上传、语音录制、在线播放兼容性问题。
- 微聊支持本机设置昵称和头像；孩子端可改昵称/头像，家长端可改昵称/头像和口令。
- 微聊设置区改为折叠；图片消息以缩略图展示，点击进入黑底大图预览，避免孩子端图片撑爆聊天窗口。
- 任务模块已从本地 TODO 升级为亲子投递：家长发布任务，孩子接受/退回/完成，家长端查看全状态；孩子退回后家长可重发，并记录重发次数。
- 图片消息大图预览支持按钮缩放、鼠标滚轮缩放、双击切换缩放和触屏双指缩放，方便查看高清细节。
- 微聊首次加载改为取最新一页消息，避免消息超过分页上限后只看到最早消息。
- 学习收件箱支持远程投递 HTML，孩子端可全屏运行或保存到本地记录。
- 网页内微聊入口支持未读小红点；App 内运行时可用 `StudyToolbox.showChatBadge` 原生桥发独立微聊通知；2026-06-21+ APK 还会用 WorkManager 后台轮询家长消息并触发通知/桌面通知点。
- 微聊历史加载改为 QQ/微信式滑动窗口：默认只加载最新消息，滑到顶部才显示“加载更早消息”，加载后保留原可见消息位置，可多轮向上补齐历史。

## 下一步建议

1. 上传最新 `toolbox-site/index.html` 到 `toolbox.zakuku.top`。
2. 部署 `server/` 并重启 `study-toolbox`，确认 `/api/` 反向代理仍使用 `^~`。
3. 在家长浏览器端填写 `PARENT_CHAT_KEY`，测试文字、图片和任务重发流程。
4. 在 2026-06-22 前安装 `android/app/build/outputs/apk/debug/app-debug.apk`，确认通知权限、图片上传和语音录制。
5. 在平板端等待下次打开 App，确认自动孩子视角。
6. 下一阶段可做 AI 辅助 TODO 拆分、父级批准的网址胶囊、SSE/Push 消息同步和更接近 IM 的原生推送。

## 红线

- 不要恢复 Device Owner / 静默安装路线。
- 不要恢复旧 `web/` 管理面板。
- 不要让孩子端自己选择家长身份。
- 不要把 `PARENT_CHAT_KEY` 写入孩子端页面源码。
