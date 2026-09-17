package.path = 'scripts/?.lua;android/?.lua;' .. package.path
local host = assert(android_bridge, 'Android host bridge required')
local json = require('xutils')
local network = require('network')
local original_dofile = dofile

-- Keep the shared scripts unchanged; adapt their two platform entry points.
function dofile(path)
    local result = original_dofile(path)
    if path == 'scripts/core/share/xfs.lua' then
        result.mkdirp = function(dir) return json.mkdir_p(dir) end
    elseif path == 'scripts/core/share/xhttp_stream.lua' then
        result = network.wrap(result)
    end
    return result
end

local registry = require('xagent.tools.registry')
local session = require('xagent.session.session')
local async = dofile('scripts/core/share/xasync.lua')
local skills = require('xagent.skills')
local router = dofile('scripts/core/share/xrouter.lua')
local current, busy, pending, config
local approval_id = 0
local workspace = host.workspace

local function emit(event)
    local data, err = json.json_pack(event)
    assert(data, err)
    host.emit(data)
end

local function fail(message)
    emit({ type = 'error', error = tostring(message) })
end

local function register(name)
    local tool = require('xagent.tools.' .. name)
    if name == 'todo_write' then tool = tool.tool end
    local call = tool.call
    tool.call = function(input, ctx)
        if current and current.cancelled then return { content = 'Cancelled', is_error = true } end
        for _, key in ipairs({ 'path', 'file_path' }) do
            if input[key] and not host.check_path(input[key]) then
                return { content = 'Access denied: use files inside the imported workspace.', is_error = true }
            end
        end
        return call(input, ctx)
    end
    registry.register(tool)
end

for _, name in ipairs({ 'read', 'write', 'edit', 'multi_edit', 'ls', 'glob',
                        'web_fetch', 'memory_write', 'todo_write', 'skill' }) do
    register(name)
end

local function system_prompt()
    return 'You are Codua2a, a coding assistant running on Android. Reply in the user\'s language.\n'
        .. 'Workspace: ' .. workspace .. '\n'
        .. 'Only access this workspace. Files are imported by the user. '
        .. 'Shell, Bash, external rg, package installation and build execution are unavailable. '
        .. 'Use only advertised tools. Request approval for changes through the provided tools. '
        .. 'Do not claim to have run commands.\nDate: ' .. os.date('%Y-%m-%d')
end

local function options()
    return { cfg = config, cwd = workspace, tools = registry.to_api_params(),
             system = system_prompt(), max_tokens = 4096 }
end

local function snapshot()
    emit({ type = 'session', id = current.id, messages = current.messages })
end

local function attach_confirm()
    current.confirm = function(request)
        if current.cancelled then return false end
        return async.await(function(resolve)
            approval_id = approval_id + 1
            pending = { id = approval_id, resolve = resolve }
            emit({ type = 'confirm', id = approval_id, name = request.name, input = request.input })
        end)
    end
end

local function new_session()
    current = session.new(options())
    attach_confirm()
    snapshot()
end

local function configure(value)
    assert(type(value) == 'table', 'Missing model configuration')
    assert(type(value.base_url) == 'string' and value.base_url:match('^https://[^/%s]+'), 'Use an HTTPS endpoint')
    assert(type(value.model) == 'string' and value.model ~= '', 'Enter a model name')
    assert(type(value.api_key) == 'string' and value.api_key ~= '', 'Enter an API key')
    assert(value.auth_style == 'x-api-key' or value.auth_style == 'bearer', 'Invalid authentication style')
    config = { base_url = value.base_url:gsub('/+$', ''), model = value.model,
        api_key = value.api_key, auth_style = value.auth_style, verify = true, max_retries = 0 }
    if current then current.cfg = config else new_session() end
    emit({ type = 'configured', model = config.model })
end

local function save()
    local path, err = current:save()
    if not path then fail('Cannot save session: ' .. tostring(err)) end
end

local function dispatch(command)
    local action = command.action
    if action == 'cancel' then
        if current then current.cancelled = true end
        if pending then local p = pending; pending = nil; p.resolve(false) end
        network.cancel()
        emit({ type = 'status', text = busy and '停止中…' or '已停止' })
        return
    elseif action == 'confirm' then
        if pending and command.id == pending.id then
            local p = pending; pending = nil; p.resolve(command.allow == true)
        end
        return
    end
    assert(not busy, 'Wait for the current task to finish')
    if action == 'configure' then
        configure(command.config)
    elseif action == 'new' then
        new_session()
    elseif action == 'sessions' then
        emit({ type = 'sessions', items = session.list() })
    elseif action == 'load' then
        assert(type(command.id) == 'string' and command.id:match('^%x+$'), 'Invalid session ID')
        local loaded, err = session.load(session.dir() .. '/' .. command.id .. '.json', options())
        assert(loaded, err)
        current = loaded
        current.cwd = workspace
        attach_confirm()
        snapshot()
    elseif action == 'submit' then
        assert(config, '请先在设置中配置模型')
        assert(type(command.text) == 'string' and command.text:match('%S'), 'Message cannot be empty')
        if not current then new_session() end
        current.cancelled = false
        network.cancelled = false
        current:add_user(command.text)
        save()
        emit({ type = 'user', text = command.text })
        busy = true
        emit({ type = 'busy', value = true })
        local co = coroutine.create(function()
            local ok, err = xpcall(function()
                skills.bootstrap(workspace)
                current:run(emit)
            end, debug.traceback)
            if not ok then fail(err) end
            network.complete()
            save()
            busy = false
            pending = nil
            emit({ type = 'busy', value = false })
        end)
        local ok, err = coroutine.resume(co)
        if not ok then busy = false; fail(err); emit({ type = 'busy', value = false }) end
    else
        error('Unknown action: ' .. tostring(action))
    end
end

return {
    __tick_ms = 16,
    __thread_handle = router.handle,
    __init = function()
        assert(xnet.init())
        assert(json.mkdir_p(workspace))
        assert(json.mkdir_p(session.dir()))
        emit({ type = 'ready', workspace = workspace })
    end,
    __update = function()
        network.tick()
        for _ = 1, 8 do
            local raw = host.poll()
            if not raw then break end
            local ok, err = pcall(function()
                local command = json.json_unpack(raw)
                assert(type(command) == 'table', 'Invalid command')
                dispatch(command)
            end)
            if not ok then fail(err) end
        end
    end,
    __uninit = function() network.cancel(); xnet.uninit() end,
}
