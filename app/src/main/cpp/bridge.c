#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <sys/stat.h>
#include "3rd/minilua.h"

/* xlog already writes to logcat. Do not forward potentially sensitive logs to UI. */
void native_log_to_java(int level, const char *tag, const char *message) {
    (void)level; (void)tag; (void)message;
}

int codua2a_run(int argc, char **argv);
typedef struct Command { char *data; size_t len; struct Command *next; } Command;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static Command *head, *tail;
static size_t queued;
static int active, ever_started;
/* Used exclusively by the dedicated JNI run thread, never by the UI thread. */
static JNIEnv *runtime_env;
static jobject receiver;
static jmethodID on_event;
static char workspace[PATH_MAX];

static int poll_command(lua_State *L) {
    pthread_mutex_lock(&lock);
    Command *cmd = head;
    if (cmd) { head = cmd->next; if (!head) tail = NULL; --queued; }
    pthread_mutex_unlock(&lock);
    if (!cmd) return 0;
    lua_pushlstring(L, cmd->data, cmd->len);
    free(cmd->data); free(cmd);
    return 1;
}

static int emit_event(lua_State *L) {
    size_t len;
    const char *s = luaL_checklstring(L, 1, &len);
    JNIEnv *env = runtime_env;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)len);
    if (!bytes) return luaL_error(L, "event allocation failed");
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (const jbyte *)s);
    (*env)->CallVoidMethod(env, receiver, on_event, bytes);
    (*env)->DeleteLocalRef(env, bytes);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return luaL_error(L, "Android event callback failed");
    }
    return 0;
}

static int inside_workspace(const char *path) {
    size_t n = strlen(workspace);
    return strncmp(path, workspace, n) == 0 && (path[n] == '/' || path[n] == '\0');
}

/* Check every existing component, including symlinks. Missing components are
 * permitted for Write; traversal is rejected before any file tool executes. */
static int check_path(lua_State *L) {
    size_t len;
    const char *input = luaL_checklstring(L, 1, &len);
    char path[PATH_MAX], resolved[PATH_MAX];
    if (len == 0 || len >= PATH_MAX || memchr(input, 0, len)) goto denied;
    int count = input[0] == '/' ? snprintf(path, sizeof(path), "%s", input)
        : snprintf(path, sizeof(path), "%s/%s", workspace, input);
    if (count < 0 || count >= PATH_MAX || !inside_workspace(path)) goto denied;
    char *part = path + strlen(workspace);
    while (*part) {
        if (*part == '/') ++part;
        char *end = strchr(part, '/');
        size_t n = end ? (size_t)(end - part) : strlen(part);
        if (n == 2 && part[0] == '.' && part[1] == '.') goto denied;
        if (end) *end = 0;
        struct stat st;
        if (lstat(path, &st) == 0 && (!realpath(path, resolved) || !inside_workspace(resolved))) {
            if (end) *end = '/';
            goto denied;
        }
        if (!end) break;
        *end = '/'; part = end;
    }
    lua_pushboolean(L, 1); return 1;
denied:
    lua_pushboolean(L, 0); return 1;
}

void codua2a_install(lua_State *L) {
    lua_newtable(L);
    lua_pushcfunction(L, poll_command); lua_setfield(L, -2, "poll");
    lua_pushcfunction(L, emit_event); lua_setfield(L, -2, "emit");
    lua_pushcfunction(L, check_path); lua_setfield(L, -2, "check_path");
    lua_pushstring(L, workspace); lua_setfield(L, -2, "workspace");
    lua_setglobal(L, "android_bridge");
}

JNIEXPORT jint JNICALL Java_app_codua2a_AgentRuntime_nativeRun(JNIEnv *env, jobject self, jstring root) {
    pthread_mutex_lock(&lock);
    if (ever_started) { pthread_mutex_unlock(&lock); return -2; }
    ever_started = 1;
    pthread_mutex_unlock(&lock);
    const char *dir = (*env)->GetStringUTFChars(env, root, NULL);
    if (!dir) return -3;
    int rc = chdir(dir);
    setenv("HOME", dir, 1);
    snprintf(workspace, sizeof(workspace), "%s/workspace", dir);
    mkdir(workspace, 0700);
    char canonical_workspace[PATH_MAX];
    if (realpath(workspace, canonical_workspace)) {
        snprintf(workspace, sizeof(workspace), "%s", canonical_workspace);
    } else {
        rc = -1;
    }
    (*env)->ReleaseStringUTFChars(env, root, dir);
    if (rc) return -4;
    runtime_env = env;
    receiver = (*env)->NewGlobalRef(env, self);
    jclass cls = (*env)->GetObjectClass(env, self);
    on_event = (*env)->GetMethodID(env, cls, "onNativeEvent", "([B)V");
    (*env)->DeleteLocalRef(env, cls);
    if (!receiver || !on_event) return -5;
    pthread_mutex_lock(&lock); active = 1; pthread_mutex_unlock(&lock);
    char *argv[] = { "codua2a", "android/bootstrap.lua", "SERVER_NAME=codua2a", NULL };
    rc = codua2a_run(3, argv);
    pthread_mutex_lock(&lock);
    active = 0;
    while (head) { Command *next = head->next; free(head->data); free(head); head = next; }
    tail = NULL; queued = 0;
    pthread_mutex_unlock(&lock);
    (*env)->DeleteGlobalRef(env, receiver);
    receiver = NULL; runtime_env = NULL;
    return rc;
}

JNIEXPORT jboolean JNICALL Java_app_codua2a_AgentRuntime_nativeSend(JNIEnv *env, jobject self, jbyteArray bytes) {
    (void)self;
    jsize len = (*env)->GetArrayLength(env, bytes);
    if (len <= 0 || len > 1024 * 1024) return JNI_FALSE;
    Command *cmd = calloc(1, sizeof(*cmd));
    if (!cmd) return JNI_FALSE;
    cmd->data = malloc((size_t)len + 1); cmd->len = (size_t)len;
    if (!cmd->data) { free(cmd); return JNI_FALSE; }
    (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte *)cmd->data);
    cmd->data[len] = 0;
    pthread_mutex_lock(&lock);
    if (!active || queued >= 64) {
        pthread_mutex_unlock(&lock); free(cmd->data); free(cmd); return JNI_FALSE;
    }
    if (tail) tail->next = cmd; else head = cmd;
    tail = cmd; ++queued;
    pthread_mutex_unlock(&lock);
    return JNI_TRUE;
}
