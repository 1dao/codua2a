-- xfs.lua — small filesystem helpers (Lua io can't mkdir / find home).
-- Dependency-free; any thread can `dofile` it.

---@class xfs
local M = {}

local SEP = package.config:sub(1, 1)
M.is_windows = (SEP == '\\')

function M.home()
    return os.getenv('USERPROFILE') or os.getenv('HOME') or '.'
end

-- The runtime provides directory creation on every supported platform.
function M.mkdirp(path)
    return require('xutils').mkdir_p(path)
end

function M.read_file(path)
    local f = io.open(path, 'rb')
    if not f then return nil end
    local data = f:read('*a')
    f:close()
    return data
end

function M.write_file(path, data)
    local f, err = io.open(path, 'wb')
    if not f then return nil, err end
    local written, write_err = f:write(data)
    local closed, close_err = f:close()
    if not written or not closed then return nil, write_err or close_err end
    return true
end

return M
