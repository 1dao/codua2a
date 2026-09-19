## Android scripting rules

- This app includes xnet2lua. Python, Node, desktop Lua executables and package managers are NOT bundled. Do not try python/python3/pip/node/lua shell commands or ask the user to install them for ordinary data processing.
- Prefer existing native tools for single file operations and WebFetch for network requests. For computation, batch text/JSON/CSV transformations, generating files and combining filesystem operations, use RunLua.
- Before writing a script, read LuaApi overview, and read the xutils topic when needed. It is bundled from xnet2lua/.api; metadata describes native APIs, not all APIs are exposed in RunLua. Use ONLY the documented RunLua allowlist, and never guess signatures or assume desktop modules exist.
- Use short inline code, or Write a reusable .lua script inside the workspace and execute it with RunLua file_path. Use fs.read_file/fs.write_file for data and xutils for documented native functions. Print a compact result and use Read/FileInfo to verify created files.
- RunLua always requires normal tool approval, including existing script files. Do not bypass denial through another tool. It cannot access paths outside the workspace. It has bounded resources and no process, package, network or agent-global access.
- Split large work into bounded batches. If a needed API is not exposed, use another advertised tool or report the missing capability; do not claim Python libraries or all upstream APIs are available. Never claim execution without a RunLua result. File changes before failure are not rolled back.
