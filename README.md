# Codua2a

Android 原生界面 + Lua Agent。Android 工程参考 `xproxy-android`，
`core/` 是 `xnet2lua` Git submodule；应用层脚本来自 `codua`。

## 构建

需要 JDK 17、Android SDK 34、NDK 25.1.8937393、CMake 3.22.1。
Gradle wrapper 为 8.6，AGP 8.2.0，Kotlin 1.9.22。

```sh
git submodule update --init core
git -C core submodule update --init 3rd/libdeflate
# local.properties: sdk.dir=C:/android/sdk（替换为自己的 SDK 路径）
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat assembleDebug`。输出：
`app/build/outputs/apk/debug/app-debug.apk`。
包含 arm64-v8a 和 x86_64，最低 Android 8.0 / API 26。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.codua2a/.MainActivity
```

## 使用

1. 在设置中填写 HTTPS 服务根地址、模型 ID 和 API 密钥。接口必须兼容
   Anthropic Messages；程序追加 `/v1/messages`。支持 x-api-key 或 Bearer。
2. 开始对话，文本以流式更新。文件变更会弹出允许 / 拒绝确认。
3. 在“文件”中导入设备文档（单文件最多 16 MiB）。同名文件另存，不覆盖原文档。
4. 文件更改保存在应用内部工作区，选择文件可导出回设备。
5. “历史”可恢复已保存会话；“新会话”保留旧会话和工作区。

## 架构与范围

- Kotlin 系统原生控件，无 WebView、无 raygui。
- JNI 传递 UTF-8 JSON 字节，避免中文 / emoji 的 Modified UTF-8 问题。
- 单个专用线程执行 xnet 的 Lua / 网络事件循环。UI 通过有界命令队列发送操作。
- Activity 重建共享同一个运行时；模型密钥由 Android Keystore AES-GCM 加密保存。
- TLS 使用 core 的 mbedTLS 和内置 CA，开启证书验证。
- Read、Write、Edit、MultiEdit、LS、Glob、WebFetch、MemoryWrite、TodoWrite、Skill。
- 仅向模型开放内部 workspace 文件工具；JNI 校验路径和现有符号链接。
- 模型流支持停止和 120 秒无数据超时；其他网络工具遵循其现有超时。
- 会话每次提交和完成后落盘。进程被系统杀死后可恢复已落盘消息，不能恢复在途网络请求。
- 本版未启用 Bash、rg、stdio MCP，也未提供远程 MCP 配置界面。
- 当前面向前台交互，未实现前台服务；后台长期运行不作保证。
- 当前聊天内容用可选择纯文本显示，尚无 Markdown 富文本 / 图片消息。

## 源码布局

```text
core/                       xnet2lua submodule（保持干净）
app/src/main/cpp/           Android host、JNI、CMake
app/src/main/java/app/codua2a/  原生 UI、运行时状态、密钥存储
app/src/main/assets/android/   Android Lua 启动与流式网络适配
app/src/main/assets/scripts/   codua Lua 业务层快照
tests/                      离线 Android 桥接回归测试
```

CMake 从 core 的 runner 生成 Android 入口并注入 bridge，避免修改 submodule。
Android daemon 适配不会覆盖 ART 的信号处理器。升级 core 后需重新验证入口注入及 ABI。
第三方依赖与许可证见 `THIRD_PARTY_NOTICES.md`。

## 离线验证

从 `app/src/main/assets` 目录，用已有桌面版 xnet 运行：

```powershell
& C:/source/ops/codua/bin/xnet.exe ../../../../tests/android_bridge_spec.lua
```

测试使用假的模型回调，验证工具批准 / 拒绝、停止、会话恢复、路径检查调用和流超时，
不读取真实密钥、不调用模型。真实 Android 文件边界和 JNI 仍需设备验证。

原生编译和 APK 构建不能替代真机测试。连接设备后应验证启动、中文 / emoji、
软键盘、屏幕旋转、配置持久化、实际 HTTPS 流、停止、文件导入导出和会话恢复。
