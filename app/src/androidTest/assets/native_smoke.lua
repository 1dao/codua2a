package.path = 'scripts/?.lua;android/?.lua;' .. package.path
local json = require('xutils')
local host = android_bridge
local process = require('process')
local files = require('files')
local network = require('network')
local original_dofile = dofile
function dofile(path)
    local result = original_dofile(path)
    if path == 'scripts/core/share/xhttp_stream.lua' then return network.wrap(result) end
    return result
end
package.loaded['xagent.mcp.transport_http'] = require('mcp_transport')
local start
local waiting = {}
local function run()
    assert(host.check_path('file.txt'))
    assert(not host.check_path('../outside'))
    assert(not host.check_path('/data/local/tmp/secret'))
    assert(host.regex('HELLO|世界', true):match('hello'))
    assert(not pcall(host.regex, '['))
    local result = process.run({ '/system/bin/sh', '-c', 'printf "hello 世界"; mkdir -p empty; rm -f outside; ln -s /data/local/tmp outside' }, host.workspace, 5000)
    assert(not result.is_error and result.content:find('hello 世界', 1, true), result.content)
    assert(not host.check_path('outside/test.txt'))
    local f = assert(io.open(host.workspace .. '/note.txt', 'wb')); f:write('hello 世界\nsecond line\n'); f:close()
    local tools = {}; for _, tool in ipairs(files.tools(function() return false end)) do tools[tool.name] = tool end
    assert(tools.LS.call({}, { cwd = host.workspace }).content:find('empty/', 1, true))
    assert(tools.Glob.call({ pattern = '**/*.txt' }, { cwd = host.workspace }).content:find('note.txt', 1, true))
    assert(tools.Grep.call({ pattern = 'HELLO|世界', ignore_case = true }, { cwd = host.workspace }).content:find(':1:hello 世界', 1, true))
    -- Native file tools must work even when shell entry points are unavailable.
    local old_execute, old_popen = os.execute, io.popen
    os.execute = function() error('File tool attempted a shell command') end
    io.popen = os.execute
    local ctx = { cwd = host.workspace }
    for _, tool in ipairs(require('xagent.tools.file_ops').tools()) do tools[tool.name] = tool end
    local function call(name, input)
        local result = tools[name].call(input, ctx); assert(not result.is_error, result.content); return result.content
    end
    call('DeletePath', { path = 'native-文件', recursive = true })
    call('MakeDirectory', { path = 'native-文件/空目录' })
    assert(json.stat(host.workspace .. '/native-文件/空目录').type == 'directory')
    local wrote = require('xagent.tools.write').call({ file_path = 'native-文件/自动/hello.txt', content = '原生文件 😀' }, ctx)
    assert(not wrote.is_error, wrote.content)
    assert(not json.mkdir_p(host.workspace .. '/native-文件/自动/hello.txt'))
    call('CopyFile', { source = 'native-文件/自动/hello.txt', destination = 'native-文件/copy.txt' })
    assert(tools.CopyFile.call({ source = 'native-文件/copy.txt', destination = 'native-文件/copy.txt' }, ctx).is_error)
    call('MovePath', { source = 'native-文件/copy.txt', destination = 'native-文件/moved.txt' })
    local info = assert(json.json_unpack(call('FileInfo', { path = 'native-文件/moved.txt' })))
    assert(info.type == 'file' and info.size == #'原生文件 😀')
    assert(tools.DeletePath.call({ path = './', recursive = true }, ctx).is_error)
    assert(tools.MovePath.call({ source = 'native-文件', destination = 'native-文件/child' }, ctx).is_error)
    assert(tools.CopyFile.call({ source = 'native-文件/moved.txt', destination = '../outside' }, ctx).is_error)
    assert(tools.DeletePath.call({ path = 'native-文件' }, ctx).is_error)
    assert(json.stat(host.workspace .. '/outside').type == 'link')
    assert(not tools.LS.call({}, ctx).content:find('outside', 1, true))
    local bounded, truncated = json.list_dir(host.workspace, 1); assert(#bounded == 1 and truncated)
    call('DeletePath', { path = 'native-文件', recursive = true })
    assert(not json.stat(host.workspace .. '/native-文件').exists)
    os.execute, io.popen = old_execute, old_popen
    local timed = process.run({ '/system/bin/sh', '-c', 'sleep 10' }, host.workspace, 100)
    assert(timed.is_error and timed.content:find('timed out', 1, true), timed.content)
    local result2
    local child = coroutine.create(function() result2 = process.run({ '/system/bin/sh', '-c', 'sleep 10' }, host.workspace, 5000) end)
    assert(coroutine.resume(child)); process.cancel()
    while not result2 do dofile('scripts/core/share/xasync.lua').await(function(resolve) waiting[#waiting + 1] = resolve end) end
    assert(result2.is_error and result2.content:find('Cancelled', 1, true))
    local port_file = assert(io.open('fixture-port.txt')); local port = port_file:read('*a'); port_file:close()
    local url = 'https://localhost:' .. port
    local client = require('xagent.mcp.client').new('fixture', { type = 'http', url = url .. '/mcp', ca_file = 'fixture-ca.pem', verify = true, timeout_ms = 3000 })
    local connected, err = client:connect(); assert(connected, err)
    local tools = assert(client:list_tools()); assert(#tools == 1 and tools[1].name == 'echo')
    local called, call_err = client:call_tool('echo', {}); assert(called, call_err)
    assert(called.content[1].text == 'MCP 你好 😀')
    local provider = require('xagent.llm.anthropic')
    local streamed = ''
    local answer, model_err = dofile('scripts/core/share/xasync.lua').await(function(resolve)
        provider.stream_message({ base_url = url, api_key = 'fixture-only', auth_style = 'bearer', verify = true, ca_file = 'fixture-ca.pem', max_retries = 0 },
            { model = 'fixture', max_tokens = 100, messages = { { role = 'user', content = 'offline test' } } }, {
                on_text = function(value) streamed = streamed .. value end,
                on_done = function(value) resolve(value) end,
                on_error = function(value) resolve(nil, value) end,
            })
    end)
    assert(answer, tostring(model_err)); assert(streamed == '你好 Android 😀', streamed)
    network.complete()
    local untrusted = require('xagent.mcp.client').new('untrusted', { type = 'http', url = url .. '/mcp', verify = true, timeout_ms = 3000 })
    assert(not untrusted:connect(), 'Self-signed TLS certificate must be rejected without fixture CA')
    host.emit(json.json_pack({ type = 'status', text = 'native-tests-passed 你好 😀' }))
end
return {
    __tick_ms = 16,
    __init = function()
        assert(xnet.init()); start = host.now_ms()
        local co = coroutine.create(function()
            local ok, err = xpcall(run, debug.traceback)
            if not ok then host.emit(json.json_pack({ type = 'error', error = tostring(err) })); print(err) end
            xthread.stop(ok and 0 or 1)
        end)
        assert(coroutine.resume(co))
    end,
    __update = function()
        network.tick(); process.tick(); files.tick()
        local ready = waiting; waiting = {}; for _, resume in ipairs(ready) do resume() end
        if host.now_ms() - start > 30000 then print('native smoke timed out'); xthread.stop(1) end
    end,
    __uninit = function() process.cancel(); xnet.uninit() end,
}
