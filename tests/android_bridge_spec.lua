-- Run from app/src/main/assets with an existing desktop xnet runtime.
package.path = 'scripts/?.lua;android/?.lua;' .. package.path
local utils = require('xutils')
local base = utils.cwd():gsub('\\', '/') .. '/../../../../build/lua-test'
assert(utils.mkdir_p(base .. '/workspace'))
local original_dofile = dofile
function dofile(path)
    local result = original_dofile(path)
    if path == 'scripts/core/share/xfs.lua' then result.home = function() return base end end
    return result
end
local queue, events = {}, {}
local lua_runs = 0
android_bridge = {
    run_lua = function(code) lua_runs = lua_runs + 1; return true, 'mock Lua output\n', '' end,
    workspace = base .. '/workspace',
    emit = function(raw) events[#events + 1] = assert(utils.json_unpack(raw)) end,
    poll = function() return table.remove(queue, 1) end,
    check_path = function(path)
        return not path:find('..', 1, true) and path:sub(1, 1) ~= '/' and not path:match('^%a:')
    end,
}
local boot = dofile('android/bootstrap.lua')
local provider = require('xagent.llm.anthropic')
local calls, hold = 0, false
local held_callback
provider.stream_message = function(cfg, params, cb)
    calls = calls + 1
    assert(cfg.verify == true and cfg.max_retries == 0)
    if hold then held_callback = cb; return end
    local blocks, reason
    if calls % 2 == 1 then
        blocks = { { type = 'tool_use', id = 'tool-' .. calls, name = 'Write',
                     input = { file_path = 'result.txt', content = '你好 Android 😀' } } }
        reason = 'tool_use'
    else
        cb.on_text('已完成 😀')
        blocks = { { type = 'text', text = '已完成 😀' } }; reason = 'end_turn'
    end
    cb.on_done({ message = { role = 'assistant', content = blocks },
        usage = { input_tokens = 10, output_tokens = 4 }, stop_reason = reason })
end
local function send(command)
    queue[#queue + 1] = utils.json_pack(command)
    boot.__update()
end
local function last(kind)
    for i = #events, 1, -1 do if events[i].type == kind then return events[i] end end
end
local function check(value, message) assert(value, message); print('PASS ' .. message) end
local function run()
    boot.__init()
    check(last('ready') ~= nil, 'native-ready event')
    send({ action = 'submit', text = 'hello' })
    check(last('error') ~= nil and calls == 0, 'reject send before configuration')
    send({ action = 'configure', config = { base_url = 'http://example.invalid', model = 'test', api_key = 'test', auth_style = 'bearer' } })
    check(last('configured') == nil, 'reject insecure model endpoint')
    send({ action = 'configure', config = { base_url = 'https://example.invalid', model = 'test', api_key = 'test', auth_style = 'bearer' } })
    check(last('configured').model == 'test', 'configure model without network')
    send({ action = 'submit', text = 'write a file' })
    check(last('confirm').name == 'Write' and last('busy').value == true, 'write waits for approval')
    local id = last('confirm').id
    send({ action = 'confirm', id = id + 1, allow = true })
    check(calls == 1, 'stale approval cannot release pending tool')
    send({ action = 'confirm', id = id, allow = true })
    local file = assert(io.open(base .. '/workspace/result.txt', 'rb'))
    local content = file:read('*a'); file:close()
    check(content == '你好 Android 😀', 'approved write preserves Chinese and emoji')
    check(last('busy').value == false and calls == 2, 'tool result continues model loop')
    send({ action = 'sessions' })
    check(#last('sessions').items > 0, 'session saved and listed')
    local saved = last('sessions').items[1].id
    send({ action = 'new' }); send({ action = 'load', id = saved })
    check(#last('session').messages == 4, 'session restores tool pairing and messages')
    send({ action = 'load', id = '../outside' })
    check(last('error').error:find('Invalid session ID', 1, true), 'session traversal rejected')
    local denied = require('xagent.tools.registry').find('Read').call({ file_path = '../private' }, { cwd = base })
    check(denied.is_error, 'tool paths use host boundary check')
    send({ action = 'submit', text = 'deny this write' })
    send({ action = 'confirm', id = last('confirm').id, allow = false })
    check(last('tool_result').result.is_error == true and calls == 4, 'denial feeds error result back to model')
    send({ action = 'submit', text = 'cancel confirmation' })
    send({ action = 'cancel' })
    check(last('busy').value == false and last('done').stop_reason == 'cancelled', 'cancel releases confirmation and completes cleanly')

    -- Exercise cancellation independently of the model decoder.
    local net = require('network')
    net.cancelled = false
    local callbacks, closed, failed = nil, false, 0
    local transport = net.wrap({ request = function(_, cb)
        callbacks = cb
        return { close = function() closed = true; cb.on_done() end }
    end })
    transport.request({}, { on_error = function() failed = failed + 1 end, on_done = function() error('duplicate completion') end })
    net.cancel(); callbacks.on_error('late error')
    check(closed and failed == 1 and next(net.active) == nil, 'stream cancellation closes once and ignores late callbacks')
    net.cancelled = false
    closed = false
    transport.request({}, { on_error = function() failed = failed + 1 end, on_done = function() error('duplicate completion') end })
    for state in pairs(net.active) do state.deadline = 0 end
    net.tick()
    check(closed and failed == 2, 'silent stream times out and releases connection')
    closed = false
    transport.request({}, { on_done = function() error('completion must be suppressed after provider finishes') end })
    net.complete()
    check(not closed, 'defer socket release outside packet callback')
    net.tick()
    check(closed and next(net.active) == nil, 'completed model response releases a keep-alive socket')
    local phase = 0
    provider.stream_message = function(cfg, params, cb)
        assert(params.system:find('Android scripting rules', 1, true))
        local advertised = false
        for _, tool in ipairs(params.tools) do if tool.name == 'RunLua' then advertised = true end end
        assert(advertised)
        phase = phase + 1
        local blocks = phase % 2 == 1 and { { type = 'tool_use', id = 'lua-' .. phase, name = 'RunLua', input = { code = 'print(42)' } } }
            or { { type = 'text', text = 'done' } }
        cb.on_done({ message = { role = 'assistant', content = blocks }, usage = {}, stop_reason = phase % 2 == 1 and 'tool_use' or 'end_turn' })
    end
    send({ action = 'new' }); send({ action = 'submit', text = 'run Lua' })
    check(last('confirm').name == 'RunLua' and lua_runs == 0, 'Lua waits for approval with rules and schema advertised')
    send({ action = 'confirm', id = last('confirm').id, allow = false })
    check(lua_runs == 0 and last('tool_result').result.is_error, 'denied Lua never executes')
    send({ action = 'submit', text = 'run Lua with approval' })
    send({ action = 'confirm', id = last('confirm').id, allow = true })
    check(lua_runs == 1 and not last('tool_result').result.is_error, 'approved Lua output returns to model')
end
return {
    __init = function()
        local ok, err = xpcall(run, debug.traceback)
        if not ok then io.stderr:write(err .. '\n') end
        xthread.stop(ok and 0 or 1)
    end,
    __uninit = boot.__uninit,
}
