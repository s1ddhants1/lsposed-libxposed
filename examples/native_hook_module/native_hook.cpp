#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "NativeHookModule"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==============================================================================
// LSPosed Native Hook Types
// ==============================================================================
typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

// Function pointers provided by LSPosed
static HookFunType hook_func = nullptr;
static UnhookFunType unhook_func = nullptr;

// ==============================================================================
// 1. C Standard Library Hook (fopen)
// ==============================================================================
static FILE *(*backup_fopen)(const char *filename, const char *mode) = nullptr;

static FILE *fake_fopen(const char *filename, const char *mode) {
    if (filename != nullptr && (strstr(filename, "su") != nullptr || strstr(filename, "magisk") != nullptr)) {
        LOGI("Hiding root binary access: %s", filename);
        return nullptr;
    }
    return backup_fopen(filename, mode);
}

// ==============================================================================
// 2. Dynamic Library Callback (Intercepting libtarget.so functions)
// ==============================================================================
static int (*backup_target_verify)(int code) = nullptr;

static int fake_target_verify(int code) {
    LOGI("Intercepted target_verify(code=%d) -> returning bypass 0", code);
    return 0; // Success
}

static void on_library_loaded(const char *name, void *handle) {
    if (name == nullptr || handle == nullptr) return;

    if (std::string(name).find("libtarget.so") != std::string::npos) {
        LOGI("libtarget.so detected! Hooking functions...");
        void *verify_sym = dlsym(handle, "target_verify");
        if (verify_sym != nullptr) {
            hook_func(verify_sym, (void *) fake_target_verify, (void **) &backup_target_verify);
            LOGI("Hooked target_verify successfully");
        }
    }
}

// ==============================================================================
// 3. JNIEnv Table Hooking (FindClass)
// ==============================================================================
static jclass (*backup_FindClass)(JNIEnv *env, const char *name) = nullptr;

static jclass fake_FindClass(JNIEnv *env, const char *name) {
    if (name != nullptr && strcmp(name, "com/target/security/IntegrityCheck") == 0) {
        LOGI("Preventing resolution of IntegrityCheck class");
        return nullptr;
    }
    return backup_FindClass(env, name);
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        if (hook_func != nullptr && env != nullptr && env->functions != nullptr) {
            hook_func((void *) env->functions->FindClass, (void *) fake_FindClass, (void **) &backup_FindClass);
            LOGI("JNIEnv->FindClass hooked successfully");
        }
    }
    return JNI_VERSION_1_6;
}

// ==============================================================================
// 4. Exported Native Entry Point
// ==============================================================================
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (entries == nullptr) return nullptr;

    hook_func = entries->hook_func;
    unhook_func = entries->unhook_func;

    LOGI("LSPosed native_init started (version: %u)", entries->version);

    // Install system hooks
    hook_func((void *) fopen, (void *) fake_fopen, (void **) &backup_fopen);

    return on_library_loaded;
}
