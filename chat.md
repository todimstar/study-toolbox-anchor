---

## 项目名称：远程静默安装助手 (Remote Silent Installer)

### 1. 项目背景与目标
为运行 **Android 10 (EMUI 10.1)** 的华为平板（型号 BZC-W00）开发一款“锚点”应用，配合自建服务器和 Web 家长端，实现：
- 从家长手机或网页上传 APK 到服务器，服务器向平板下发安装任务。
- 平板在 **熄屏、无人操作** 的情况下，**完全静默地** 安装应用，无任何弹窗或亮屏打扰。
- 支持 **弱网/断网** 环境下的任务队列与重试，恢复网络后自动继续。
- 将安装进度、结果 **实时反馈** 给家长端。

核心约束：**平板无法 Root**，但可以尝试通过 **Device Owner (设备所有者)** 模式获得系统级静默安装权限。

### 2. 技术架构

```text
[家长手机/网页] ---（上传APK/查看状态）---> [自建服务器]
                                                  |
                                                  | (长连接推送/任务拉取)
                                                  |
                                        [平板端“锚点”App]
                                                  |
                                     (静默安装，状态上报)
```

- **家长端**：一个简易的 Web 管理面板（或手机 App），用于上传 APK 文件、查看下发状态历史。
- **服务器端**：提供 REST API、WebSocket 服务，存储 APK 文件和任务状态，实现消息推送。
- **平板端**：自研 Android 应用，注册为 Device Owner，启动前台服务保活，长连接接收指令后再利用系统 API 静默安装。

### 3. 平板端核心技术方案

#### 3.1 Device Owner 免 Root 静默安装
这是整个系统的基石。Android 5.0+ 提供 Device Owner 机制，允许应用成为设备最高管理者后，直接使用 `PackageInstaller` API 在后台静默安装应用，无需任何用户交互。

**实施步骤：**
1.  在 `AndroidManifest.xml` 中声明系统级权限：
    ```xml
    <uses-permission android:name="android.permission.INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.DELETE_PACKAGES" />
    ```
2.  创建一个继承自 `DeviceAdminReceiver` 的子类（如 `MyDeviceAdminReceiver`），它必须是 Device Owner 的入口。
3.  应用首次安装后，通过一次性的 ADB 命令激活为 Device Owner：
    ```bash
    adb shell dpm set-device-owner com.your.package/.MyDeviceAdminReceiver
    ```
    **注意**：执行前必须确保平板未登录任何账户（如谷歌、华为账户），否则会失败。
4.  激活后，应用中就可以调用 `PackageInstaller` 实现静默安装：
    ```java
    PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    int sessionId = packageInstaller.createSession(params);
    // 将会话 ID 分配给安装器，并通过流写入 APK 文件...
    // 安装过程在系统后台运行，无任何UI。
    ```
5.  如需静默卸载，可调用 `packageInstaller.uninstall(packageName, ...)`。

#### 3.2 长连接与任务调度（解决熄屏、网络不好）
采用 **WorkManager + WebSocket** 组合。
- **前台服务保活**：启动 `Service` 并调用 `startForeground()`，发送一条常驻通知（如“学习助手运行中”），让应用在熄屏后不被系统杀死，能够维持长连接。
- **WebSocket 长连接**：与服务端建立 WebSocket 连接，实时接收下发的安装任务。如果连接断开，自动重连。
- **任务队列保障**：使用 `WorkManager` 来处理具体的下载和安装任务。`WorkManager` 支持设置网络约束（如“仅在有网络时执行”），并会在网络恢复后自动继续挂起的任务，完美契合“网络不好”的场景。即使 WebSocket 连接中断，平板重启后任务也不会丢失。
    - 任务状态至少包括：`等待下载`、`下载中`、`安装中`、`成功`、`失败(含原因)`。每一步状态变更都通过 WebSocket 实时上报服务器。

#### 3.3 安全设计
- **双向认证**：家长端与服务器间使用用户名/密码 + Token 鉴权；平板与服务器间使用设备唯一 ID 和预先配置的密钥进行双向认证，防止非法设备接入。
- **通信加密**：所有 API 和 WebSocket 连接强制使用 HTTPS/WSS，传输的 APK 文件也应加密存储和传输（服务端存储时加密，平板下载后先解密再安装）。
- **指令校验**：服务器下发指令时携带 HMAC 校验码，平板端验证指令未被篡改。

### 4. 服务器端关键接口设计

1.  **用户认证接口**
    - `POST /api/login`：家长端登录获取 Token。
2.  **APK 上传接口**
    - `POST /api/apps/upload`：家长上传 APK，服务器存储文件并生成一条任务记录（状态：`pending`）。
3.  **任务下发与同步**
    - 平板建立 WebSocket 连接到 `wss://your-server/ws/device?token=xxx`。
    - 当有 `pending` 任务时，服务器通过该连接推送任务详情（包含 APK 下载 URL）。
4.  **平板状态上报**
    - 通过同一 WebSocket 通道，平板实时将状态更新推送回服务器。
    - 备选方案：平板通过 REST API 主动上报状态（`POST /api/tasks/{taskId}/status`）。
5.  **任务结果查询**
    - `GET /api/tasks`：家长端查询所有任务列表及实时状态。

### 5. 家长端 Web 面板
简单页面即可，核心功能：
- 登录。
- 拖拽上传 APK 文件，选择目标设备（如果有多台）。
- 查看任务列表，每条任务显示状态（彩色标识）、时间、APK 名称，支持自动刷新（通过 WebSocket 或短轮询）。

### 6. 开发与部署注意事项

- **华为平板特殊适配**：
    - 必须提前退出“纯净模式”（设置 > 系统和更新 > 纯净模式 > 退出），否则静默安装会被华为的安全机制拦截。
    - 需在电池优化中，将自研应用设为“不允许电池优化”，并允许自启动、关联启动，防止被后台清理。
- **Device Owner 一次性激活**：如果之后需要卸载该应用，必须先在应用中调用 `devicePolicyManager.clearDeviceOwnerApp()` 去除权限，否则无法正常卸载。
- **调试阶段**：可以先在普通 Android 设备上调试核心逻辑，最后再在目标平板上激活 Device Owner 后进行最终测试。

### 7. 替代风险预案
如果由于华为定制层导致 Device Owner 激活失败或 `PackageInstaller` 静默安装被阻止，可降级为备选方案：
- **无障碍服务方案**：在平板端代码中集成 `AccessibilityService`，模拟点击安装按钮。此方案需要提前手动开启无障碍服务，且安装会短暂唤醒屏幕。
- **厂商隐藏接口探索**：联系“赶考小状元”官方确认是否已有 MDM（移动设备管理）推送接口。
