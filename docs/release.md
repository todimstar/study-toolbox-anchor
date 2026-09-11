# APK 发布流程（本机 + GitHub Release）

> 目标：每次改 APK 都要出一个**正式签名、可覆盖升级、有 tag 可追溯**的版本，
> 不再散装 debug APK。

## 一、签名密钥（一次性，已就绪）

- 文件：`android/keystore/study-toolbox-release.jks`（本机，不提交 git，已 ignore）
- 别名：`studytoolbox`　口令见 `android/keystore.properties`（同样不提交）
- **证书指纹**（用于核对是否同一份密钥，覆盖升级必须一致）：
  - SHA-256: `d3c355e37ffb45cd438d8118ea17cea96397fdf2ee07d3ec963e20300ec899c2`
  - SHA-1: `bee3466ee787407122852e933e948b99b537ce81`

> ⚠️ 这两份文件（.jks + .properties）请自己离线备份一份。
> 丢失/泄露 = 无法再覆盖升级孩子在用的一切版本。

## 二、发一个版本的标准步骤

1. **改代码**：`MainActivity.kt`、`build.gradle.kts` 等。
2. **升版本号**（两处，必须同步）：
   - `android/app/build.gradle.kts`：`versionCode` +1、`versionName` 换新。
   - `MainActivity.kt` 的 `getAppVersion()` 返回字符串，与 `versionName` 一致。
   - `versionCode` 只能增不能减（Android 据此判断能否覆盖安装）。
3. **编译**（本机无 `gradlew`，用发行版）：
   ```bash
   cd android
   GRADLE_BIN=$(ls -d ~/.gradle/wrapper/dists/gradle-8.10.2-bin/*/gradle-8.10.2/bin | head -1)
   "$GRADLE_BIN/gradle" assembleRelease
   ```
   产物：`android/app/build/outputs/apk/release/app-release.apk`
4. **核对签名**（可选但推荐）：
   ```bash
   APKSIGNER=$(find ~/Ep/Android/Sdk/build-tools -name apksigner.bat | sort | tail -1)  # 按实际路径
   "$APKSIGNER" verify --print-certs android/app/build/outputs/apk/release/app-release.apk
   ```
   确认 `Signer #1 certificate DN: CN=Study Toolbox` 且 SHA-256 与上面一致。
5. **提交并打 tag**：
   ```bash
   git add android/ .gitignore
   git commit -m "v0.1.1 (中文说明)"
   git tag -a v0.1.1 -m "v0.1.1"
   git push origin main --tags
   ```
6. **上传 GitHub Release**（用 `gh`，把 APK 附到 release）：
   ```bash
   gh release create v0.1.1 \
     --title "v0.1.1" \
     --notes "改动说明..." \
     android/app/build/outputs/apk/release/app-release.apk
   ```
7. **装到平板**：下载 v0.1.1 APK → `adb install -r app-release.apk`（覆盖安装，签名一致）。

## 三、为什么这样设计

- **密钥本机 + 不入库**：git 是公开仓库，签名密钥/口令进库等于把"覆盖权限"交给全世界。
- **versionCode 单调递增**：Android 覆盖安装的依据，退回旧码会被拒装。
- **tag + Release**：每个平板上的 APK 都能查到对应源码和版本号，排查问题可回溯。

## 四、易错点

- 忘了同步 `getAppVersion()`：网页端靠它判断是否在 App 内，版本号不一致会导致功能判断出错。
- 用 debug APK 当正式版：debug 签名是公共的，不能作为长期覆盖升级的凭据。
- `keystore.properties` 别提交（含明文口令）。