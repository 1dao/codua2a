# RunLua on Android

Lua 5.5 in an independent state per call. No installation or Shell required.
Supply `code` OR `file_path` (a workspace script). Results come from `print(...)`.
Use normal tool confirmation before execution. State is discarded afterwards.

Available: assert, error, ipairs, pairs, next, select, tonumber, tostring, type,
standard string/table/math/utf8 functions, print, fs, xutils, require('xutils').
No io, os, debug, coroutine, package, load/loadfile/dofile, pcall/xpcall, network,
native extension loading, agent variables or configuration access.

## Files (relative to workspace; absolute paths must remain inside workspace)

- `fs.read_file(path) -> string`: regular file, at most 1 MiB; throws on error.
- `fs.write_file(path, bytes) -> true`: create/overwrite a regular file, at most 1 MiB;
  create parent directories first. Throws on error; writes are not transactional.
- `xutils.mkdir_p(path) -> ok, err`: create directories recursively.
- `xutils.stat(path) -> info, err`: `{exists=true, type='file'|'directory'|'link'|'other', size=bytes, mtime=Unix_seconds}`;
  missing paths return `{exists=false}`, OS errors return `nil, error`. Does not follow final symlinks.
- `xutils.list_dir(path) -> entries, truncated_or_error`: at most 1000 immediate entries.
  Entries are `{name=string, dir=boolean}`. Use stat for metadata and skip links.
  Never follow entries outside workspace. On failure returns `nil, error`.

## Data APIs from xnet2lua/.api/xutils.lua

Only these xutils names are exposed (other upstream APIs are NOT available here):
`json_pack`, `json_unpack`, `json_null`, `sha256`, `sha256_hex`, `md5_hex`,
`base64_encode`, `base64_decode`, `hex_encode`, `hex_decode`,
`stat`, `list_dir`, `mkdir_p`.
Read `LuaApi {"topic":"xutils"}` for original signatures and return conventions.
The metadata is documentation, not a script to execute.

## Limits

64 KiB source; 16 MiB Lua heap; 2 million VM instructions; 2-second deadline checked
between Lua instructions (native calls are not preempted); 32 KiB printed output;
256 filesystem calls; 8 MiB total file I/O. Native helper allocations are outside
the Lua heap counter. Keep inputs small; this is not a full OS sandbox.
On failure, earlier file changes remain. Do not automatically retry partial writes.

## Example: summarize imported JSON without Python

```lua
local u = require('xutils')
local records = assert(u.json_unpack(fs.read_file('records.json')))
local total = 0
for _, row in ipairs(records) do total = total + (tonumber(row.amount) or 0) end
assert(u.mkdir_p('results'))
fs.write_file('results/summary.json', assert(u.json_pack({count = #records, total = total})))
print('records:', #records, 'total:', total)
```
