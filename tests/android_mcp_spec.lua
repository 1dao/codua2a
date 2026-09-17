package.path = 'scripts/?.lua;android/?.lua;' .. package.path
local json = require('xutils')
local network = require('network')
local original = dofile
local callback, request, closed
local fake = network.wrap({ request = function(opts, cb)
    request = opts; callback = cb
    return { close = function() closed = true end }
end })
function dofile(path)
    if path == 'scripts/core/share/xhttp_stream.lua' then return fake end
    return original(path)
end
local transport = require('mcp_transport')
local count = 0
local function check(value, message) assert(value, message); count = count + 1; print('PASS ' .. message) end
local function invoke(notification)
    closed = false
    local state = {}
    local conn = { url = 'https://example.invalid/mcp', timeout_ms = 1000 }
    local co = coroutine.create(function()
        if notification then state.value, state.err = transport.notify(conn, 'notifications/initialized')
        else state.value, state.err = transport.rpc(conn, 'tools/list', {}) end
        state.done = true
    end)
    assert(coroutine.resume(co))
    return state, assert(json.json_unpack(request.body)).id, conn
end
local function run()
    local state, id, conn = invoke()
    callback.on_headers(200, { ['content-type'] = 'application/json', ['mcp-session-id'] = 'session-a' })
    local payload = assert(json.json_pack({ jsonrpc = '2.0', id = id, result = { tools = {} } }))
    callback.on_body(payload:sub(1, 10)); check(not state.done, 'partial JSON waits')
    callback.on_body(payload:sub(11))
    check(state.done and state.value.tools and conn.session_id == 'session-a', 'JSON reply and session ID')
    check(not closed, 'MCP socket close deferred outside callback')
    network.tick(); check(closed, 'MCP keep-alive socket released')
    state, id = invoke()
    callback.on_headers(200, { ['content-type'] = 'text/event-stream' })
    callback.on_sse('message', json.json_pack({ id = id + 1, result = {} }))
    check(not state.done, 'unrelated SSE response ignored')
    callback.on_sse('message', json.json_pack({ id = id, result = { text = '你好 😀' } }))
    check(state.done and state.value.text == '你好 😀', 'SSE completes before HTTP disconnect')
    network.tick()
    state = invoke(true); callback.on_headers(202, {})
    check(state.done and state.value == true, 'notification accepts 202 with no body'); network.tick()
    state = invoke(); network.cancel()
    check(state.done and state.err == 'cancelled', 'cancel releases pending MCP call')
    network.cancelled = false
    state = invoke(); callback.on_headers(403, {})
    check(state.done and state.err:find('403', 1, true), 'HTTP error surfaced'); network.tick()
    state, id = invoke(); callback.on_headers(200, {})
    callback.on_body(json.json_pack({ id = id, error = { code = -32601, message = 'not found' } }))
    check(state.done and state.err:find('not found', 1, true), 'JSON-RPC error surfaced'); network.tick()
    state = invoke(); for active in pairs(network.active) do active.deadline = 0 end; network.tick()
    check(state.done and state.err:find('timeout', 1, true), 'MCP timeout releases coroutine')

    package.loaded['xagent.mcp.transport_http'] = { rpc = function(_, method, params)
        if not params or not params.cursor then return { tools = { { name = 'first' } }, nextCursor = 'page2' } end
        return { tools = { { name = 'second' } } }
    end }
    local client = require('xagent.mcp.client').new('test', {})
    client.status = 'connected'; client.capabilities = { tools = {} }; client.transport = {}
    local list = assert(client:list_tools())
    check(#list == 2 and list[2].name == 'second', 'tools/list follows pagination')
    print(count .. ' MCP checks passed')
end
return { __init = function()
    local ok, err = xpcall(run, debug.traceback)
    if not ok then io.stderr:write(err .. '\n') end
    xthread.stop(ok and 0 or 1)
end }
