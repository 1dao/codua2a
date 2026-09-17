-- Consume the matching JSON-RPC response as soon as it arrives, including SSE
-- servers that keep the HTTP connection open after a response.
local async = dofile('scripts/core/share/xasync.lua')
local stream = dofile('scripts/core/share/xhttp_stream.lua')
local rpc = require('xagent.mcp.jsonrpc')
local json = require('xutils')
local network = require('network')
local M = {}

local function post(conn, message, notification)
    return async.await(function(resolve)
        local connection, done, status, length, is_sse
        local body = ''
        local function finish(result, err)
            if done then return end
            done = true
            network.close(connection)
            resolve(result, err)
        end
        local function accept(object)
            if type(object) ~= 'table' then return end
            if object[1] then for _, item in ipairs(object) do accept(item) end; return end
            if object.id == message.id and object.id ~= nil then
                local result, err = rpc.parse_response(object)
                finish(result, err)
            end
        end
        local headers = { ['Content-Type'] = 'application/json', ['Accept'] = 'application/json, text/event-stream' }
        if conn.session_id then headers['Mcp-Session-Id'] = conn.session_id end
        if conn.protocol_version then headers['MCP-Protocol-Version'] = conn.protocol_version end
        for key, value in pairs(conn.headers or {}) do headers[key] = value end
        connection = stream.request({ url = conn.url, method = 'POST', headers = headers,
            body = assert(rpc.encode(message)), verify = true, ca_file = conn.ca_file,
            timeout_ms = conn.timeout_ms or 30000 }, {
            on_headers = function(code, response_headers)
                status = code
                conn.session_id = response_headers['mcp-session-id'] or conn.session_id
                is_sse = (response_headers['content-type'] or ''):find('text/event-stream', 1, true)
                length = tonumber(response_headers['content-length'])
                if code >= 400 then finish(nil, 'MCP HTTP ' .. code)
                elseif notification and code >= 200 and code < 300 then finish(true)
                elseif length == 0 then finish(nil, 'MCP response is empty') end
            end,
            on_body = function(chunk)
                if done or is_sse then return end
                body = body .. chunk
                if #body > 4 * 1024 * 1024 then finish(nil, 'MCP response exceeds 4 MiB'); return end
                local parsed, obj = pcall(json.json_unpack, body)
                if parsed and type(obj) == 'table' then accept(obj) end
                if not done and length and #body >= length then finish(nil, 'MCP response has no matching request ID') end
            end,
            on_sse = function(_, data)
                if done then return end
                if #data > 4 * 1024 * 1024 then finish(nil, 'MCP event exceeds 4 MiB'); return end
                local parsed, obj = pcall(json.json_unpack, data)
                if parsed then accept(obj) end
            end,
            on_done = function() if not done then finish(nil, 'MCP connection closed before matching response') end end,
            on_error = function(err) finish(nil, tostring(err)) end,
            on_http_error = function(code) finish(nil, 'MCP HTTP ' .. code) end,
        })
        if done then network.close(connection) end
    end)
end

function M.rpc(conn, method, params)
    return post(conn, rpc.request(rpc.next_id(), method, params), false)
end
function M.notify(conn, method, params)
    return post(conn, rpc.notification(method, params), true)
end
return M
