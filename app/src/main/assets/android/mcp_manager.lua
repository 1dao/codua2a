local registry = require('xagent.tools.registry')
local clients = require('xagent.mcp.registry')
local client = require('xagent.mcp.client')
local fetch = require('xagent.mcp.fetch_tools')
local M = { config = {}, dirty = false, summary = {} }
local function clear_tools()
    for _, tool in ipairs(registry.all()) do
        if tool.mcp or tool.name == 'ListMcpResources' or tool.name == 'ReadMcpResource' then registry.unregister(tool.name) end
    end
    clients.clear()
end

function M.configure(config)
    assert(type(config) == 'table', 'MCP configuration must be an object')
    local validated, count = {}, 0
    for name, value in pairs(config.mcpServers or {}) do
        count = count + 1; assert(count <= 16, 'Maximum 16 MCP servers')
        assert(type(name) == 'string' and name:match('^[%w_-]+$') and not name:find('__', 1, true), 'MCP names use letters, numbers, single underscores and hyphens')
        assert(type(value) == 'table' and (value.type == nil or value.type == 'http'), 'Use Streamable HTTP MCP (type=http); legacy SSE and stdio are not supported')
        assert(type(value.url) == 'string' and value.url:match('^https?://[^/%s]+'), 'MCP server URL must be HTTP(S)')
        local headers = {}
        assert(value.headers == nil or type(value.headers) == 'table', 'MCP headers must be an object')
        for key, content in pairs(value.headers or {}) do
            assert(type(key) == 'string' and type(content) == 'string' and not key:find('[\r\n]') and not content:find('[\r\n]'), 'Invalid MCP header')
            headers[key] = content
        end
        validated[name] = { type = 'http', url = value.url, headers = headers, verify = true, timeout_ms = 30000 }
    end
    M.config = validated; M.dirty = true
    clear_tools()
    M.summary = {}
end

function M.connect(emit, stopped)
    if not M.dirty then return end
    clear_tools()
    M.dirty = false; M.summary = {}
    local names = {}; for name in pairs(M.config) do names[#names + 1] = name end; table.sort(names)
    for _, name in ipairs(names) do
        if stopped() then M.dirty = true; break end
        local c = client.new(name, M.config[name])
        local ok, err = c:connect()
        local tools = {}
        if ok then
            tools, err = fetch.fetch(c); tools = tools or {}
            local seen = {}
            for _, tool in ipairs(tools) do
                if #tool.name > 128 or seen[tool.name] then err = 'Duplicate or oversized MCP tool name'; break end
                seen[tool.name] = true
            end
        end
        if ok and not err then
            for _, tool in ipairs(tools) do
                -- A remote annotation is a hint, not local permission authority.
                tool.is_read_only = function() return false end
                local call = tool.call
                tool.call = function(input, ctx)
                    if stopped() then return { is_error = true, content = 'Cancelled' } end
                    return call(input, ctx)
                end
                registry.register(tool)
            end
        else c.status = 'failed'; c.error = err end
        clients.set(name, c, tools)
        M.summary[#M.summary + 1] = { name = name, status = c.status, tools = #tools, error = err }
    end
    if #clients.connected() > 0 then
        registry.register(require('xagent.tools.list_mcp_resources'))
        registry.register(require('xagent.tools.read_mcp_resource'))
    end
    emit({ type = 'mcp_status', servers = M.summary })
end
return M
