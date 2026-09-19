# Android 工具能力与实现

原生 API 优先，Lua 组合实现工具逻辑；缺少通用底层能力时在 `xnet2lua`
实现并导出，而不是要求用户安装桌面命令。Shell 是额外能力，不是文件操作的前提。

| 工具 | Android 实现 | 外部程序依赖 |
| --- | --- | --- |
| Read / Write / Edit / MultiEdit | Lua io，Write 用 xutils.mkdir_p 创建父目录 | 无 |
| LS / Glob | xutils.list_dir + stat，Lua 遍历与 glob 匹配 | 无 |
| Grep | Lua 有界读取 + Android POSIX 正则；支持 literal 模式 | 无，无需 rg |
| FileInfo | xutils.stat，不跟随符号链接 | 无 |
| MakeDirectory | xutils.mkdir_p | 无 |
| CopyFile | Lua 二进制分块读写，最多 64 MiB，不覆盖目标 | 无 |
| MovePath | Lua os.rename，创建目标父目录，不覆盖目标 | 无 |
| DeletePath | Lua os.remove / xutils.rmtree，禁止删除工作区根 | 无 |
| WebFetch | xnet HTTP/TLS + Lua HTML 文本提取 | 无，无需 curl |
| MemoryWrite / TodoWrite / Skill | Lua 文件及会话逻辑 | 无 |
| LuaApi / RunLua | 随包 API 文档 + 独立 Lua 状态执行脚本 | 无，无需 Python、Shell 或 lua 命令 |
| MCP 工具及资源 | xnet HTTP/TLS + Lua JSON-RPC/SSE | 无，需配置远程服务 |
| Bash（可选） | xproc 创建进程，xnet 异步读输出 | Android 系统 sh，命令本身须存在 |

所有文件变更、Shell 和远程 MCP 工具调用继续走确认流程。FileInfo、目录浏览和搜索只读。
通用文件 API 的补充在上游 `FILESYSTEM_API.md` 中描述；Android 层负责工作区路径检查。

## 当前范围

- Android 系统规则自动加载 `android/lua_rules.md`：数据计算、文本/JSON/CSV 转换和批量生成文件优先使用 Lua。
- `LuaApi` 提供执行环境说明及来自 `core/.api/xutils.lua` 的原生签名；可用范围以 overview 为准。
- `RunLua` 支持内联 `code` 或工作区 `file_path`，总是请求执行确认；支持基础 Lua、受限文件读写及 xutils JSON、哈希、编码、目录 API。
- 每次独立状态，脚本不接触模型配置；文件路径沿用工作区校验。限制 64 KiB 脚本、16 MiB Lua 堆、200 万指令、32 KiB 输出、256 次文件操作及 8 MiB I/O。2 秒截止在 Lua 指令间检查，原生调用不能抢占，原生辅助内存不计入 Lua 堆限制。错误前的文件更改不回滚。
- 当前脚本环境未开放 xnet/xthread、动态库、Shell、完整 io/os；网络使用 WebFetch 或 MCP。`.api` 存在不代表其全部模块已向脚本开放。

- 原有内置工具均有 Android 入口；文件管理不需要启用 Shell。
- Grep 是 POSIX 扩展正则，不承诺 ripgrep 的所有参数与 PCRE 功能。
- Skill 可读取并注入指令；`context: fork` 子代理执行尚未实现。
- MCP 支持 Streamable HTTP；stdio、OAuth 登录尚未实现。
- git、Node、Python、编译器等完整工具链没有内置，不能把任意 Shell 命令
  视为已有的 Lua 工具。新增具体能力应继续按“上游原生 API + Lua 工具”实现。
