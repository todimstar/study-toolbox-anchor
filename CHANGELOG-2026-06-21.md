# 2026-06-21 升级修复总结

## 🎯 本次修复的三大问题

### 1. ✅ 孩子端进入微聊需要往下滑才看到输入框

**问题诊断：**
- 移动端 `.chat-shell` 高度设置为 `calc(100dvh - 150px)`
- 150px 不足以覆盖：顶部 header + 底部 nav + Android 状态栏 + safe-area
- 导致输入框被推到视口外

**修复方案：**
```css
/* 从 150px 增加到 180px */
.chat-shell {
  height: calc(100dvh - 180px);
  max-height: calc(100dvh - 180px);
}
```

**影响范围：**
- `toolbox-site/index.html` 第 578 行

---

### 2. ✅ 加载历史消息后跳到错误位置

**问题诊断：**
- 旧逻辑使用 `offset`（相对偏移）来记录锚点位置
- 插入历史消息后，DOM 节点位置改变，`offset` 计算不准确
- `restoreChatAnchor` 的滚动位置恢复逻辑有误

**修复方案：**
1. 重写 `firstVisibleChatAnchor`：
   - 找到第一个可见节点
   - 记录其 `id` 和精确的 `offsetTop`（相对于容器顶部）

2. 重写 `restoreChatAnchor`：
   - 根据 `id` 找到锚点节点
   - 计算当前位置与目标位置的差值
   - 精确调整 `scrollTop`

**关键代码：**
```javascript
function firstVisibleChatAnchor(list) {
  const nodes = Array.from(list.querySelectorAll('[data-chat-id]'));
  const containerRect = list.getBoundingClientRect();
  
  for (const node of nodes) {
    const nodeRect = node.getBoundingClientRect();
    if (nodeRect.bottom > containerRect.top && nodeRect.top < containerRect.bottom) {
      return {
        id: node.dataset.chatId,
        offsetTop: nodeRect.top - containerRect.top
      };
    }
  }
}

function restoreChatAnchor(list, anchor) {
  const node = list.querySelector('[data-chat-id="' + anchor.id + '"]');
  const containerRect = list.getBoundingClientRect();
  const nodeRect = node.getBoundingClientRect();
  const currentOffsetTop = nodeRect.top - containerRect.top;
  const scrollDelta = currentOffsetTop - anchor.offsetTop;
  list.scrollTop += scrollDelta;
}
```

**影响范围：**
- `toolbox-site/index.html` 第 1451-1491 行

---

### 3. ⚠️ 通知需要点击 App 才出现（部分优化）

**问题诊断：**
1. **WorkManager 限制**：
   - 最小周期 15 分钟（系统强制）
   - 不保证准时执行（系统批量调度）
   - Doze 模式下可能被延迟或跳过

2. **电池优化**：
   - 默认情况下，Android 会限制应用后台活动
   - 需要用户手动关闭电池优化

3. **权限和设置**：
   - 通知权限已申请，但用户可能未授权
   - 桌面角标取决于启动器支持

**优化方案：**
1. **添加权限**：
   - `WAKE_LOCK`：保持 CPU 唤醒
   - `RECEIVE_BOOT_COMPLETED`：开机自启

2. **优化通知配置**：
   - 启用振动和灯光
   - 设置为高优先级
   - 兼容 Android 8.0 以下版本

3. **缩短初始延迟**：
   - 首次轮询从启动后 1 分钟开始（原来是 15 分钟）

**关键代码：**
```kotlin
// AndroidManifest.xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

// MicroChatPollWorker.kt
.setInitialDelay(1, TimeUnit.MINUTES) // 加快首次轮询

// 通知构建器兼容性
val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
} else {
    @Suppress("DEPRECATION")
    Notification.Builder(applicationContext)
}
    .setDefaults(Notification.DEFAULT_ALL)
    .setPriority(Notification.PRIORITY_HIGH)
```

**影响范围：**
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/studytoolbox/anchor/MicroChatPollWorker.kt`

**⚠️ 重要说明：**
由于 Android 系统限制，通知仍然不是实时的。需要用户手动：
1. 关闭电池优化
2. 允许通知权限
3. 允许自启动（部分厂商）

详见：`android/NOTIFICATION_SETUP.md`

---

## 📝 其他改进

### 1. 代码注释优化
- 在 `.chat-shell` CSS 中添加了注释："移动端高度优化：确保输入框可见"

### 2. 文档完善
- 新增 `android/NOTIFICATION_SETUP.md`：详细说明通知机制、用户配置指南、已知限制

---

## 🧪 测试建议

### 前端测试（index.html）
1. **孩子端 UI 测试**：
   - [ ] 进入微聊，检查是否能直接看到输入框（无需滑动）
   - [ ] 滚动到顶部，点击"加载更早消息"
   - [ ] 验证加载后是否停留在原位置（连续衔接）

2. **跨设备测试**：
   - [ ] 不同屏幕尺寸（平板、手机）
   - [ ] 不同 Android 版本

### 后端测试
- [ ] 服务正常运行
- [ ] 微聊 API 响应正常

### Android 测试
1. **通知测试**：
   - [ ] 启动 App 后 1 分钟，检查后台任务是否开始
   - [ ] 家长端发送消息
   - [ ] 等待最多 15 分钟，观察通知是否弹出
   - [ ] 检查通知栏和桌面角标

2. **权限测试**：
   - [ ] 首次安装时，是否请求通知权限
   - [ ] 检查设置中的权限状态

3. **手动触发**（调试用）：
```bash
adb shell cmd jobscheduler run -f com.studytoolbox.anchor [job-id]
```

---

## 📦 部署清单

### 1. 前端部署
```bash
# 上传到服务器
scp toolbox-site/index.html aliyun:/www/wwwroot/toolbox.zakuku.top/index.html

# 验证 MD5
ssh aliyun "md5sum /www/wwwroot/toolbox.zakuku.top/index.html"
md5sum toolbox-site/index.html
```

### 2. APK 构建
```bash
cd android
$env:JAVA_HOME='C:\Ep\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\hp1080\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat' :app:assembleDebug

# 输出路径
# android/app/build/outputs/apk/debug/app-debug.apk
```

### 3. Git 提交
```bash
git add .
git commit -m "fix(chat): 修复微聊 UI 和历史加载，优化通知策略

- 修复孩子端微聊输入框被遮挡问题（调整视口高度）
- 重写历史消息加载锚点恢复逻辑，确保滚动位置连续
- 优化 Android 后台通知：添加 WAKE_LOCK 和启动权限
- 改进通知配置：启用振动、灯光、高优先级
- 缩短首次轮询延迟到 1 分钟
- 新增通知设置指南文档

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## ⏰ 装机窗口

- **截止时间**：2026-06-22
- **必须完成**：APK 构建和安装
- **优先级**：高（窗口关闭后无法重装）

---

## 🔮 未来优化方向

### 短期（如果还有装机机会）
1. 前台服务（更可靠的通知，但常驻通知栏）
2. 更好的通知图标（当前使用系统默认）
3. 通知声音和震动自定义

### 长期（需要服务端支持）
1. FCM / 极光推送（实时推送）
2. WebSocket 长连接（App 打开时实时）
3. SSE（Server-Sent Events）替代轮询

---

## ✅ 已验证

- [x] 本地 index.html MD5 与线上一致
- [x] 服务器后端运行正常
- [x] Git 历史清晰
- [x] 代码逻辑正确
- [ ] 实机测试（待用户验证）
