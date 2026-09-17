local async = dofile('scripts/core/share/xasync.lua')
local text = dofile('scripts/core/share/xtext.lua')
local ok, proc = pcall(require, 'xproc')
local M = { jobs = {} }
local function clock() return android_bridge.now_ms and android_bridge.now_ms() or os.time() * 1000 end
function M.supported() return ok and proc.supported() end

function M.run(argv, cwd, timeout_ms)
    if not M.supported() then return { is_error = true, content = 'Native process execution unavailable' } end
    return async.await(function(resolve)
        local h, err = proc.spawn({ argv = argv, cwd = cwd,
            env = { HOME = cwd, TMPDIR = cwd, PATH = '/system/bin:/system/xbin' }, merge_stderr = true })
        if not h then resolve({ is_error = true, content = tostring(err) }); return end
        local job = { pid = h.pid, deadline = clock() + timeout_ms, chunks = {}, bytes = 0, resolve = resolve }
        M.jobs[job] = true
        if h.stdin_fd >= 0 then xnet.close_fd(h.stdin_fd) end
        if h.stderr_fd >= 0 then xnet.close_fd(h.stderr_fd) end
        job.conn, err = xnet.attach(h.stdout_fd, {
            on_packet = function(_, data)
                local part = data:sub(1, math.max(0, 64000 - job.bytes))
                if #part > 0 then job.chunks[#job.chunks + 1] = part end
                job.bytes = job.bytes + #part
                if #part < #data then job.truncated = true end
                return #data
            end,
            on_close = function() job.eof = true end,
        })
        if not job.conn then
            -- xnet.attach takes ownership and closes the descriptor on failure.
            job.eof = true; job.reason = 'Cannot attach process output: ' .. tostring(err)
            proc.kill(h.pid, true)
        else job.conn:set_framing({ type = 'raw', max_packet = 1024 * 1024 }) end
    end)
end

function M.cancel()
    for job in pairs(M.jobs) do
        job.reason = 'Cancelled'; proc.kill(job.pid, true)
        if job.conn then job.conn:close('cancelled') end
        job.eof = true
    end
end

function M.tick()
    local finished = {}
    for job in pairs(M.jobs) do
        if not job.reason and clock() >= job.deadline then
            job.reason = 'Command timed out'; proc.kill(job.pid, true)
            if job.conn then job.conn:close('timeout') end
            job.eof = true
        end
        if not job.exited then
            local exited, code = proc.wait(job.pid, true)
            if exited or exited == nil then job.exited = true; job.code = exited and code or -1 end
        end
        if job.exited and job.eof then finished[#finished + 1] = job end
    end
    for _, job in ipairs(finished) do
        M.jobs[job] = nil
        local out = table.concat(job.chunks)
        if job.truncated then out = out .. '\n[output truncated at 64000 bytes]' end
        out = out .. '\n[exit ' .. tostring(job.code) .. ']'
        if job.reason then out = out .. '\n' .. job.reason end
        job.resolve({ content = text.valid_utf8(out), is_error = job.reason ~= nil or job.code ~= 0 })
    end
end

M.tool = {
    name = 'Bash',
    description = 'Execute an Android /system/bin/sh command, not GNU Bash. Runs in the app sandbox with workspace as cwd. Only installed system commands exist; git, node, python and package managers may be unavailable. Every call requires user approval.',
    input_schema = { type = 'object', properties = {
        command = { type = 'string' }, timeout = { type = 'number', description = 'Seconds, maximum 120' },
    }, required = { 'command' } },
    is_read_only = function() return false end,
    call = function(input, ctx)
        if type(input.command) ~= 'string' or not input.command:match('%S') then return { is_error = true, content = 'command required' } end
        return M.run({ '/system/bin/sh', '-c', input.command }, ctx.cwd,
            math.min(120, math.max(1, tonumber(input.timeout) or 30)) * 1000)
    end,
}
return M
