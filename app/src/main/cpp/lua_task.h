/* Included by bridge.c to reuse the Android workspace boundary. Each task gets
 * its own Lua state: no agent globals, credentials, package loader or process API. */
typedef struct {
    size_t memory, io_bytes, output_len;
    unsigned instructions, calls;
    uint64_t deadline;
    char output[32769];
} LuaTask;

static void *task_alloc(void *ud, void *ptr, size_t old, size_t size) {
    LuaTask *task = ud;
    if (!ptr) old = 0;
    if (!size) { free(ptr); task->memory -= old; return NULL; }
    if (size > 16 * 1024 * 1024 || task->memory - old > 16 * 1024 * 1024 - size) return NULL;
    void *next = realloc(ptr, size);
    if (next) task->memory = task->memory - old + size;
    return next;
}
static LuaTask *task_context(lua_State *L) {
    void *ud; lua_getallocf(L, &ud); return ud;
}
static void task_hook(lua_State *L, lua_Debug *ar) {
    (void)ar;
    LuaTask *task = task_context(L);
    task->instructions += 1000;
    if (task->instructions > 2000000 || time_clock_ms() > task->deadline)
        luaL_error(L, "Lua task execution limit reached");
}
static void task_path(lua_State *L, char *path) {
    size_t len;
    const char *input = luaL_checklstring(L, 1, &len);
    if (!path_allowed(input, len)) luaL_error(L, "Path outside workspace");
    int n = input[0] == '/' ? snprintf(path, PATH_MAX, "%s", input)
        : snprintf(path, PATH_MAX, "%s/%s", workspace, input);
    if (n < 0 || n >= PATH_MAX) luaL_error(L, "Path too long");
    if (++task_context(L)->calls > 256) luaL_error(L, "File operation limit reached");
}
static int task_read(lua_State *L) {
    char path[PATH_MAX]; task_path(L, path);
    struct stat st;
    if (stat(path, &st) || !S_ISREG(st.st_mode) || st.st_size > 1024 * 1024)
        return luaL_error(L, "read_file requires a regular file at most 1 MiB");
    LuaTask *task = task_context(L);
    if ((task->io_bytes += st.st_size) > 8 * 1024 * 1024) return luaL_error(L, "I/O limit reached");
    /* Allocate in Lua before opening the file, so OOM cannot leak descriptors. */
    size_t size = (size_t)st.st_size;
    char *buffer = lua_newuserdata(L, size ? size : 1);
    FILE *file = fopen(path, "rb");
    if (!file) return luaL_error(L, "Cannot open file");
    size_t read = fread(buffer, 1, size, file);
    int failed = ferror(file); fclose(file);
    if (failed) return luaL_error(L, "Cannot read file");
    lua_pushlstring(L, buffer, read); return 1;
}
static int task_write(lua_State *L) {
    char path[PATH_MAX]; task_path(L, path);
    size_t len; const char *data = luaL_checklstring(L, 2, &len);
    LuaTask *task = task_context(L);
    if (len > 1024 * 1024 || (task->io_bytes += len) > 8 * 1024 * 1024)
        return luaL_error(L, "write_file limit: 1 MiB/file, 8 MiB/task");
    struct stat st;
    if (!stat(path, &st) && !S_ISREG(st.st_mode)) return luaL_error(L, "Not a regular file");
    FILE *file = fopen(path, "wb");
    if (!file) return luaL_error(L, "Cannot write file; create parent directory first");
    int failed = fwrite(data, 1, len, file) != len;
    if (fclose(file)) failed = 1;
    if (failed) return luaL_error(L, "File write failed");
    lua_pushboolean(L, 1); return 1;
}
static int task_print(lua_State *L) {
    LuaTask *task = task_context(L);
    int count = lua_gettop(L);
    for (int i = 1; i <= count; i++) {
        size_t len; const char *value = luaL_tolstring(L, i, &len);
        if (task->output_len + len + 1 > 32768) return luaL_error(L, "Output exceeds 32 KiB");
        if (i > 1) task->output[task->output_len++] = '\t';
        memcpy(task->output + task->output_len, value, len); task->output_len += len;
        lua_pop(L, 1);
    }
    if (task->output_len >= 32768) return luaL_error(L, "Output exceeds 32 KiB");
    task->output[task->output_len++] = '\n'; return 0;
}
static int task_fs_api(lua_State *L) {
    char path[PATH_MAX]; task_path(L, path);
    lua_pushstring(L, path); lua_replace(L, 1);
    if (lua_toboolean(L, lua_upvalueindex(2))) {
        lua_settop(L, 1); lua_pushinteger(L, 1000);
    }
    int count = lua_gettop(L);
    lua_pushvalue(L, lua_upvalueindex(1)); lua_insert(L, 1);
    lua_call(L, count, LUA_MULTRET); return lua_gettop(L);
}
extern int luaopen_xutils(lua_State *L);
static int task_require(lua_State *L) {
    const char *name = luaL_checkstring(L, 1);
    if (strcmp(name, "xutils")) return luaL_error(L, "Only require('xutils') is available; read LuaApi");
    lua_getglobal(L, "xutils"); return 1;
}
static int task_init(lua_State *L) {
    luaL_requiref(L, "_G", luaopen_base, 1); lua_pop(L, 1);
    const char *removed[] = {"dofile", "loadfile", "load", "collectgarbage", "pcall", "xpcall", NULL};
    for (int i = 0; removed[i]; i++) { lua_pushnil(L); lua_setglobal(L, removed[i]); }
    luaL_requiref(L, "string", luaopen_string, 1); lua_pop(L, 1);
    luaL_requiref(L, "table", luaopen_table, 1); lua_pop(L, 1);
    luaL_requiref(L, "math", luaopen_math, 1); lua_pop(L, 1);
    luaL_requiref(L, "utf8", luaopen_utf8, 1); lua_pop(L, 1);
    lua_pushcfunction(L, task_print); lua_setglobal(L, "print");
    lua_pushcfunction(L, task_require); lua_setglobal(L, "require");
    lua_newtable(L);
    lua_pushcfunction(L, task_read); lua_setfield(L, -2, "read_file");
    lua_pushcfunction(L, task_write); lua_setfield(L, -2, "write_file");
    lua_setglobal(L, "fs");
    luaopen_xutils(L);
    lua_newtable(L);
    const char *safe[] = {"json_pack", "json_unpack", "json_null", "sha256", "sha256_hex", "md5_hex", "base64_encode", "base64_decode", "hex_encode", "hex_decode", NULL};
    for (int i = 0; safe[i]; i++) { lua_getfield(L, -2, safe[i]); lua_setfield(L, -2, safe[i]); }
    const char *paths[] = {"stat", "list_dir", "mkdir_p", NULL};
    for (int i = 0; paths[i]; i++) {
        lua_getfield(L, -2, paths[i]); lua_pushboolean(L, i == 1);
        lua_pushcclosure(L, task_fs_api, 2); lua_setfield(L, -2, paths[i]);
    }
    lua_setglobal(L, "xutils"); lua_pop(L, 1);
    return 0;
}
static int run_lua_task(lua_State *L) {
    size_t len; const char *code = luaL_checklstring(L, 1, &len);
    if (len > 65536) return luaL_error(L, "Script exceeds 64 KiB");
    LuaTask task = {0}; task.deadline = time_clock_ms() + 2000;
    lua_State *child = lua_newstate(task_alloc, &task, 0);
    if (!child) return luaL_error(L, "Cannot allocate Lua task");
    lua_pushcfunction(child, task_init);
    int rc = lua_pcall(child, 0, 0, 0);
    if (!rc) rc = luaL_loadbufferx(child, code, len, "workspace-task", "t");
    if (!rc) { lua_sethook(child, task_hook, LUA_MASKCOUNT, 1000); rc = lua_pcall(child, 0, 0, 0); }
    char error[512] = {0};
    if (rc) snprintf(error, sizeof(error), "%s", lua_tostring(child, -1) ? lua_tostring(child, -1) : "Lua task failed");
    lua_close(child);
    lua_pushboolean(L, rc == 0);
    lua_pushlstring(L, task.output, task.output_len);
    lua_pushstring(L, error);
    return 3;
}
