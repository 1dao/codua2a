-- Bound each model stream and allow cancellation on the Lua event-loop thread.
local M = { active = {}, cancelled = false, closing = {} }
local function clock() return android_bridge and android_bridge.now_ms and android_bridge.now_ms() / 1000 or os.time() end

function M.wrap(transport)
    local request = transport.request
    function transport.request(opts, cb)
        if M.cancelled then
            if cb.on_error then cb.on_error('cancelled') end
            return nil
        end
        local timeout = (opts.timeout_ms or 120000) / 1000
        local state = { deadline = clock() + timeout, done = false }
        M.active[state] = true
        local function finish(name, ...)
            if state.done then return end
            state.done = true
            M.active[state] = nil
            if cb[name] then cb[name](...) end
        end
        local callbacks = {}
        for name, fn in pairs(cb) do
            if name == 'on_done' or name == 'on_error' or name == 'on_http_error' then
                callbacks[name] = function(...) finish(name, ...) end
            else
                callbacks[name] = function(...)
                    if not state.done then
                        state.deadline = clock() + timeout
                        fn(...)
                    end
                end
            end
        end
        state.abort = function(reason)
            finish('on_error', reason)
            if state.conn then pcall(state.conn.close, state.conn, reason) end
        end
        state.release = function()
            state.done = true
            M.active[state] = nil
            if state.conn then M.closing[#M.closing + 1] = state.conn end
        end
        state.conn = request(opts, callbacks)
        return state.conn
    end
    return transport
end

function M.close(conn)
    if not conn then return end
    for state in pairs(M.active) do
        if state.conn == conn then state.release(); return end
    end
    M.closing[#M.closing + 1] = conn
end

function M.wrap_http(transport)
    local request = transport.request
    function transport.request(opts, cb)
        if M.cancelled then cb('cancelled'); return {} end
        local state = { deadline = clock() + (opts.timeout_ms or 30000) / 1000, done = false }
        M.active[state] = true
        local function cleanup()
            local h = state.handle
            if not h then return end
            h.done = true
            if h.timer then h.timer:del(); h.timer = nil end
            local conn = h.conn or h.proxy_conn
            if conn then M.closing[#M.closing + 1] = conn end
        end
        local function finish(err, response)
            if state.done then return end
            state.done = true; M.active[state] = nil
            cb(err, response)
        end
        state.abort = function(reason) cleanup(); finish(reason) end
        state.release = function() state.done = true; M.active[state] = nil; cleanup() end
        state.handle = request(opts, finish)
        return state.handle
    end
    return transport
end

-- A provider can finish on message_stop before the peer closes its socket.
-- Close on the next tick, outside the native packet callback stack.
function M.complete()
    local pending = {}
    for state in pairs(M.active) do pending[#pending + 1] = state end
    for _, state in ipairs(pending) do state.release() end
end

function M.cancel()
    M.cancelled = true
    local pending = {}
    for state in pairs(M.active) do pending[#pending + 1] = state end
    for _, state in ipairs(pending) do state.abort('cancelled') end
end

function M.tick()
    local closing = M.closing
    M.closing = {}
    for _, conn in ipairs(closing) do pcall(conn.close, conn, 'completed') end
    local expired = {}
    for state in pairs(M.active) do
        if clock() >= state.deadline then expired[#expired + 1] = state end
    end
    for _, state in ipairs(expired) do state.abort('network timeout') end
end

return M
