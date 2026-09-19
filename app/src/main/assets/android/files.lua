local host = android_bridge
local utils = require('xutils')
local glob = dofile('scripts/core/share/xglob.lua')
local text = dofile('scripts/core/share/xtext.lua')
local M = {}
-- Reuse core APIs; only workspace policy belongs to the Android host.
local function list_dir(path)
    assert(host.check_path(path), 'Path outside workspace')
    local items, truncated = utils.list_dir(path, 10000)
    if not items then return nil, truncated end
    local entries = {}
    for _, item in ipairs(items) do
        local full = path .. '/' .. item.name
        local info, err = utils.stat(full)
        assert(info, err)
        if info.exists and (info.type == 'directory' or info.type == 'file') and host.check_path(full) then
            entries[#entries + 1] = { name = item.name, path = full, is_dir = info.type == 'directory', size = info.size }
        end
    end
    return entries, truncated
end
local deferred = {}
function M.tick()
    local waiting = deferred; deferred = {}
    for _, resume in ipairs(waiting) do resume() end
end
local function pause()
    dofile('scripts/core/share/xasync.lua').await(function(resolve) deferred[#deferred + 1] = resolve end)
end
local function base(input, ctx)
    local path = input.path or ctx.cwd
    assert(type(path) == 'string' and host.check_path(path), 'Path outside workspace')
    return path:sub(1, 1) == '/' and path or ctx.cwd .. '/' .. path
end
function M.walk(root, callback, stopped)
    local stack, count = { root }, 0
    while #stack > 0 and count < 10000 do
        local dir = table.remove(stack)
        local entries, truncated = list_dir(dir)
        assert(entries, truncated)
        for _, entry in ipairs(entries) do
            count = count + 1
            if count > 10000 then return false end
            if entry.is_dir then
                if entry.name ~= '.git' then stack[#stack + 1] = entry.path end
            elseif callback(entry) == false then return false end
            if count % 32 == 0 then pause() end
            if stopped and stopped() then return false end
        end
        if truncated then return false end
    end
    return #stack == 0
end

function M.tools(stopped)
    return {
        {
            name = 'LS', description = 'List immediate workspace files and directories, including empty directories. Symlinks are skipped.',
            input_schema = { type = 'object', properties = { path = { type = 'string' } } },
            is_read_only = function() return true end,
            call = function(input, ctx)
                local entries, truncated = list_dir(base(input, ctx)); assert(entries, truncated)
                local out = {}
                for i, entry in ipairs(entries) do if i > 500 then break end; out[#out + 1] = entry.name .. (entry.is_dir and '/' or '') end
                table.sort(out)
                return { content = (#out > 0 and table.concat(out, '\n') or '(empty)') .. ((truncated or #entries > 500) and '\n[list truncated]' or '') }
            end,
        },
        {
            name = 'Glob', description = 'Find workspace files by glob; supports *, ** and ?. Skips .git and symlinks. Scans at most 10000 entries.',
            input_schema = { type = 'object', properties = { path = { type = 'string' }, pattern = { type = 'string' } }, required = { 'pattern' } },
            is_read_only = function() return true end,
            call = function(input, ctx)
                assert(type(input.pattern) == 'string', 'pattern required')
                local root, out = base(input, ctx), {}
                local complete = M.walk(root, function(entry)
                    if glob.match(input.pattern, entry.path:sub(#root + 2)) then out[#out + 1] = entry.path end
                    return #out < 500
                end, stopped)
                table.sort(out)
                return { content = table.concat(out, '\n') .. (complete and '' or '\n[scan stopped or limit reached]') }
            end,
        },
        {
            name = 'Grep', description = 'Search workspace text files using POSIX extended regular expressions (not ripgrep/PCRE), or literal text with literal=true. Skips binary files, files over 2 MiB, .git and symlinks; bounded to 16 MiB total input, 10000 entries and 300 matches.',
            input_schema = { type = 'object', properties = { path = { type = 'string' }, pattern = { type = 'string' }, glob = { type = 'string' }, ignore_case = { type = 'boolean' }, literal = { type = 'boolean' } }, required = { 'pattern' } },
            is_read_only = function() return true end,
            call = function(input, ctx)
                assert(type(input.pattern) == 'string' and input.pattern ~= '', 'pattern required')
                local matcher = not input.literal and host.regex(input.pattern, input.ignore_case)
                local needle = input.ignore_case and input.pattern:lower() or input.pattern
                local out, total, out_bytes = {}, 0, 0
                local root = base(input, ctx)
                local function scan(entry)
                    if entry.size and entry.size > 2 * 1024 * 1024 then return true end
                    if input.glob and not glob.match(input.glob, entry.path:sub(#root + 2)) and not glob.match(input.glob, entry.name) then return true end
                    local file = io.open(entry.path, 'rb'); if not file then return true end
                    local data = file:read(2 * 1024 * 1024 + 1) or ''; file:close()
                    total = total + #data
                    if total > 16 * 1024 * 1024 then return false end
                    if #data > 2 * 1024 * 1024 or data:find('\0', 1, true) then return true end
                    local line_number = 0
                    for line in (data .. '\n'):gmatch('(.-)\n') do
                        line_number = line_number + 1
                        local candidate = input.ignore_case and line:lower() or line
                        if (matcher and matcher:match(line)) or (not matcher and candidate:find(needle, 1, true)) then
                            local value = entry.path .. ':' .. line_number .. ':' .. line:sub(1, 500)
                            out[#out + 1] = value; out_bytes = out_bytes + #value
                            if #out >= 300 or out_bytes >= 30000 then return false end
                        end
                    end
                    return true
                end
                local info, err = utils.stat(root); assert(info, err)
                assert(info.exists and (info.type == 'file' or info.type == 'directory'), 'Path is not a regular file or directory')
                local complete
                if info.type == 'directory' then complete = M.walk(root, scan, stopped)
                else complete = scan({ path = root, name = root:match('[^/]+$') }) end
                return { content = text.valid_utf8((#out == 0 and 'No matches.' or table.concat(out, '\n')) .. (complete and '' or '\n[scan stopped or limit reached]')) }
            end,
        },
    }
end
return M
