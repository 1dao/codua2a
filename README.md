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
6. “＋ 图片”从设备选择最多 4 张图片，每张最多 16 MiB；发送前缩小并转换成
   不超过 1 MiB 的 JPEG。模型须支持视觉输入。恢复历史时用文字提示代替旧图片。
7. “设置 → 工具与 MCP”可启用系统 Shell 或配置远程 MCP。Shell 默认关闭，
   开启后每次命令仍需确认；使用 Android `/system/bin/sh`，不是桌面 GNU Bash。
8. 任务执行期间使用前台服务，通知可停止任务；完成后自动退出服务。
   通知权限被拒绝时仍可在应用中停止。单次服务最长运行 30 分钟。

MCP 配置示例（密钥和请求头与模型配置一起加密保存）：

```json
{
  "mcpServers": {
    "example": {
      "type": "http",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer YOUR_TOKEN" }
    }
  }
}
```

支持 Streamable HTTP 的 JSON 和 SSE 响应、会话 ID、工具列表分页与资源读取。
远程工具每次调用均需确认。HTTP 地址以明文传输，带密钥的服务应使用 HTTPS。

## 架构与范围

- Kotlin 系统原生控件，无 WebView、无 raygui。
- JNI 传递 UTF-8 JSON 字节，避免中文 / emoji 的 Modified UTF-8 问题。
- 单个专用线程执行 xnet 的 Lua / 网络事件循环。UI 通过有界命令队列发送操作。
- Activity 重建共享同一个运行时；模型密钥由 Android Keystore AES-GCM 加密保存。
- TLS 使用 core 的 mbedTLS 和内置 CA，开启证书验证。
- Read、Write、Edit、MultiEdit、LS、Glob、Grep、WebFetch、MemoryWrite、TodoWrite、Skill，以及可选 Shell / MCP。
- 文件工具限定内部 workspace，JNI 校验路径和现有符号链接。Shell 权限是整个
  Android 应用沙箱，能访问本应用其他数据，不能视为 workspace 隔离。
- LS / Glob / Grep 不依赖外部 rg；Grep 使用 POSIX 扩展正则，支持字面量模式。
  搜索跳过符号链接、二进制文件及 `.git`，并限制扫描量和输出。
- 模型流支持停止和 120 秒无数据超时；MCP 默认 30 秒，Shell 最长 120 秒。
  停止会关闭网络请求、终止 Shell 进程组并释放待确认操作。
- 会话每次提交和完成后落盘。进程被系统杀死后可恢复已落盘消息，不能恢复在途网络请求。
- Android 会话通过临时文件和原子替换保存，防止写入中断截断原文件。
- 原生 Markdown 显示支持标题、列表、粗体、斜体、链接、行内代码和代码块，
  不包含完整 CommonMark、复杂表格或远程图片渲染。
- 不支持 stdio MCP、旧式独立 SSE 端点及 OAuth 登录；可配置静态请求头。
- 不捆绑 git、Node、Python、包管理器或编译工具链。Shell 只可执行设备已有命令。
- 前台服务不保证绕过厂商省电策略或系统强制结束；进程结束后需从历史恢复。

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
& C:/source/ops/codua/bin/xnet.exe ../../../../tests/android_mcp_spec.lua
```

测试使用假的模型回调，验证工具批准 / 拒绝、停止、会话恢复、路径检查调用和流超时，
不读取真实密钥、不调用外部模型。

设备测试（专用测试设备或模拟器；会创建测试会话和缓存文件）：

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.codua2a.test/app.codua2a.NativeRuntimeTest
adb shell am instrument -w -e mode ui app.codua2a.test/app.codua2a.NativeRuntimeTest
```

已在 Android 14 x86_64 模拟器验证：JNI 启动与中文 / emoji、文件和符号链接边界、
原生正则 / 搜索、真实 Shell 输出 / 超时 / 取消、设备内 HTTPS 模型 SSE、
MCP JSON / 持续 SSE 连接、证书拒绝、图片压缩、Keystore 加密、Activity 重建、
后台任务与服务停止、会话落盘。测试 HTTPS 密钥只打包在测试 APK，生产 APK 不含测试服务。

ARM64 已编译，尚无 ARM 真机验证，也未使用真实模型账户进行计费请求。
不同厂商的后台策略、系统文件选择器导入导出及真实服务兼容性仍需对应设备验收。
