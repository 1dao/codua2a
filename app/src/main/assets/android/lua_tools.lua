local host = android_bridge
local text = dofile('scripts/core/share/xtext.lua')
local M = {}
function M.rules()
    local file = assert(io.open('android/lua_rules.md', 'rb'))
    local rules = file:read('*a'); file:close()
    return rules
end
function M.tools()
    return {
        {
            name = 'LuaApi',
            description = 'Read Android RunLua API documentation. Start with overview; xutils is upstream .api metadata, only the overview allowlist is available in RunLua.',
            input_schema = { type = 'object', properties = { topic = { type = 'string', enum = { 'overview', 'xutils' } } } },
            is_read_only = function() return true end,
            call = function(input)
                local topic = input.topic or 'overview'
                local path = topic == 'overview' and 'android/lua_api.md' or topic == 'xutils' and 'android/api/xutils.lua'
                if not path then return { content = 'Unknown API topic', is_error = true } end
                local file = io.open(path, 'rb')
                if not file then return { content = 'API documentation unavailable', is_error = true } end
                local data = file:read('*a'); file:close()
                return { content = data }
            end,
        },
        {
            name = 'RunLua',
            description = 'Execute Lua in built-in xnet2lua, without Python, Shell or an external Lua executable. Read LuaApi first. Supply code OR a workspace file_path. Always requires approval. Use print for results; file changes before an error are not rolled back.',
            input_schema = { type = 'object', properties = {
                code = { type = 'string', description = 'Lua source, at most 64 KiB' },
                file_path = { type = 'string', description = 'Workspace Lua script, at most 64 KiB' },
            } },
            is_read_only = function() return false end,
            call = function(input, ctx)
                local ok, result = pcall(function()
                    assert((input.code ~= nil) ~= (input.file_path ~= nil), 'Supply exactly one of code or file_path')
                    local code = input.code
                    if input.file_path then
                        assert(type(input.file_path) == 'string' and host.check_path(input.file_path), 'Path outside workspace')
                        local path = input.file_path:sub(1, 1) == '/' and input.file_path or ctx.cwd .. '/' .. input.file_path
                        local info = assert(require('xutils').stat(path))
                        assert(info.type == 'file' and info.size <= 65536, 'Script must be a regular file at most 64 KiB')
                        local file = assert(io.open(path, 'rb'))
                        code = file:read(65537); file:close()
                    end
                    assert(type(code) == 'string' and #code <= 65536, 'Script must be at most 64 KiB')
                    local success, output, err = host.run_lua(code)
                    return { content = text.valid_utf8(output .. (success and (#output == 0 and '(completed without output)' or '')
                        or ('\nLua error: ' .. err .. '\nEarlier file changes, if any, remain.'))), is_error = not success }
                end)
                return ok and result or { content = tostring(result), is_error = true }
            end,
        },
    }
end
return M
