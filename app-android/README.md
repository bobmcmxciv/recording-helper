# Android 应用

原生 Kotlin 应用，Material 3 界面，不使用 WebView，不需要服务器。

- 环境：JDK 17、Android SDK 35、Gradle 8.10.2（AGP 8.7.3 / Kotlin 2.0.21）。
- 调试包：`gradle testStandardDebugUnitTest assembleStandardDebug`
- 正式包：本地放置 `key.properties`（见 `.gitignore`，不入库）后 `gradle assembleStandardRelease`
- 仪器测试：`gradle assembleStandardReleaseAndroidTest`，安装后 `adb shell am instrument -w com.bob.recordinghelper.test/androidx.test.runner.AndroidJUnitRunner`

两个产品风味：`standard`（通用，含联发科 NPU 适配与 NNAPI）与 `snapdragon`（额外打包高通 QNN 运行库，约 +50 MB）。

模块划分与存储位置见 `../docs/ARCHITECTURE.md`，原生库来源见 `native/README.md`。
