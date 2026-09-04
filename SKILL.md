---
name: lsposed-libxposed
description: >-
  Expert guide for Android ART hooking and Xposed module development using LSPosed and the modern LibXposed API (API 101, 102+).
  Use whenever the user asks about creating, developing, debugging, or migrating Xposed modules, hooking Android Java/Kotlin methods or constructors,
  using modern LibXposed APIs (XposedModule, XposedInterface, Hooker, Chain, HookBuilder, HookHandle, Invoker, CtorInvoker, deoptimize, hookClassInitializer, detach),
  configuring LSPosed module metadata (module.prop, java_init.list, native_init.list, scope.list, xposedscope),
  implementing inter-process communication (Remote Preferences, Remote Files, XposedService, XposedServiceHelper, XposedProvider, dynamic scope requests),
  handling module lifecycle and live hot reloading (onModuleLoaded, onPackageLoaded, onPackageReady, onSystemServerStarting, onHotReloading, onHotReloaded),
  building C/C++ native hooks with Android NDK (NativeAPIEntries, native_init, NativeOnModuleLoaded, JNI_OnLoad, JNIEnv hooking),
  fuzzy reflection matching and bytecode inspection (libxposed:helper, @DexAnalysis, Reflector),
  or migrating legacy XposedBridge (de.robv.android.xposed) projects to modern LibXposed standards.
---

# LSPosed & Modern LibXposed Development Guide

Expert workflow and reference manual for building modern Android ART hooking modules using **LSPosed** and the **LibXposed** framework (API 101, 102+).

---

## 1. Quick Reference & Architecture Overview

| Core Concept | Legacy Xposed / EdXposed | Modern LibXposed (LSPosed Standard) |
| :--- | :--- | :--- |
| **API Version** | API 54..93 (`de.robv.android.xposed`) | **API 101, 102+** (`io.github.libxposed.api`) |
| **Base Class** | `IXposedHookLoadPackage` | `extends XposedModule` |
| **Hook Paradigm**| `beforeHookedMethod` / `afterHookedMethod` | **OkHttp-style Interceptor Chain** (`Hooker` + `Chain`) |
| **Entry Discovery**| `assets/xposed_init` + `<meta-data>` in Manifest | `META-INF/xposed/java_init.list` + `module.prop` + `scope.list` |
| **Configuration** | `XSharedPreferences` (world-readable file hack) | **Remote Preferences & Remote Files** (LSPosed Daemon IPC) |
| **Bypass Hooks** | `XposedBridge.invokeOriginalMethod()` | **Invoker Subsystem** (`Invoker`, `CtorInvoker`, `invokeSpecial`) |
| **Inlined Methods**| Silently fails | `deoptimize(Executable)` runtime ART deoptimization |
| **Hot Reload** | Force-stop / reboot required | **Live Hot Reload** without restarting target apps (API 102) |
| **Native Hooks** | `assets/native_init` | `META-INF/xposed/native_init.list` (`native_init` + `LSPlant`) |

---

## 2. Topic References & Guides

For deep technical specifications, see the detailed reference files:

- 📖 **[Modern LibXposed API Specification](./references/modern_libxposed_api.md)**: Full API 101/102 specification, `XposedInterface`, interceptor chains, `HookBuilder`, `ExceptionMode`, `Invoker`, method deoptimization, and `<clinit>` static initializer hooks.
- 🔄 **[Lifecycle & Hot Reloading Guide](./references/hot_reloading_and_lifecycle.md)**: Process startup flow, `onModuleLoaded`, `onPackageReady`, `onSystemServerStarting`, live hot reloading (`onHotReloading`, `onHotReloaded`), and classloader memory leak prevention.
- 📡 **[Service & IPC Guide](./references/service_and_ipc.md)**: Remote Preferences, Remote Files, `XposedServiceHelper`, `XposedProvider`, dynamic scope management, and queried running targets.
- ⚡ **[Native Hooking Specification](./references/native_hooking.md)**: C/C++ NDK hooking, `NativeAPIEntries`, `native_init`, `NativeOnModuleLoaded`, `JNIEnv` function table hooks, and multi-arch settings.
- 🔍 **[Helper & Matcher DSL Guide](./references/helper_and_matcher_dsl.md)**: `libxposed:helper` & `helper-ktx`, `Reflector`, type-safe Matchers, and `@DexAnalysis` bytecode inspection for obfuscated targets.
- 🛠️ **[Project Setup, Packaging & Migration](./references/project_setup_and_migration.md)**: Gradle setup, `libs.versions.toml`, `module.prop`, ProGuard rules, Android Lint (`@SinceApi`, `@InternalApi`), and legacy migration step-by-step.
- 💻 **[Daemon Architecture & lspctl CLI Guide](./references/lspctl_cli_and_daemon_internals.md)**: Live debugging via `lspctl`, JSON schema, module and scope mutations, Developer Mode security guards, SQLite database schema (`modules_config.db`), and safe mode recovery.

---

## 3. Quick Cheatsheets

### 3.1 Creating a Modern Module (Kotlin)

#### 1. Gradle Dependencies (`app/build.gradle.kts`)
```kotlin
android {
    defaultConfig { minSdk = 26; targetSdk = 35 }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
```

#### 2. Metadata Configuration (`src/main/resources/META-INF/xposed/`)
- **`module.prop`**:
  ```properties
  minApiVersion=101
  targetApiVersion=102
  staticScope=false
  autoHotReload=true
  ```
- **`java_init.list`**:
  ```text
  com.example.module.ModuleMain
  ```
- **`scope.list`**:
  ```text
  com.target.application
  ```

#### 3. Module Entry Implementation (`ModuleMain.kt`)
```kotlin
package com.example.module

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Invoker

class ModuleMain : XposedModule() {
    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return

        val targetClass = Class.forName("com.target.application.AuthService", true, param.classLoader)
        val method = targetClass.getDeclaredMethod("isVipUser", String::class.java)

        // Register Interceptor Hook
        hook(method)
            .setPriority(PRIORITY_DEFAULT)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                log(android.util.Log.INFO, "Module", "isVipUser called for: ${chain.getArg(0)}")
                
                // Proceed with original or return custom value
                val original = chain.proceed()
                true // Override to VIP true
            }
    }
}
```

---

### 3.2 Interceptor Chain Patterns

```kotlin
// 1. Intercept & Modify Arguments:
hook(method).intercept { chain ->
    val newArgs = arrayOf("spoofed_user", chain.getArg(1))
    chain.proceed(newArgs)
}

// 2. Invoke Original Method Bypassing All Hooks:
val rawResult = getInvoker(method)
    .setType(Invoker.Type.ORIGIN)
    .invoke(chain.thisObject, "arg1", "arg2")

// 3. Special Non-Virtual Call (like super.method()):
val specialResult = getInvoker(method).invokeSpecial(chain.thisObject, "arg1")

// 4. Hook Constructor & Call Special Parent Constructor:
hook(ctor).intercept { chain ->
    chain.proceed()
    null // Constructors always return null/Unit
}
```

---

### 3.3 Remote Preferences & Service IPC

```kotlin
// In Hooked Target Process (Read-Only + Live Listener):
val prefs = getRemotePreferences("user_settings")
val isEnabled = prefs.getBoolean("feature_key", false)
prefs.registerOnSharedPreferenceChangeListener { sp, key ->
    val updated = sp.getBoolean(key, false)
}

// In Module App / UI Activity (Read-Write):
App.xposedService?.getRemotePreferences("user_settings")?.edit()
    ?.putBoolean("feature_key", true)
    ?.apply()
```

---

### 3.4 Native C/C++ Hooking (NDK)

```c++
#include "xposed_native.h"

static HookFunType hook_func = nullptr;
static FILE *(*backup_fopen)(const char *, const char *) = nullptr;

static FILE *fake_fopen(const char *path, const char *mode) {
    if (path && strstr(path, "banned_file")) return nullptr;
    return backup_fopen(path, mode);
}

void on_library_loaded(const char *name, void *handle) {
    if (name && strstr(name, "libtarget.so")) {
        void *sym = dlsym(handle, "target_function");
        if (sym) hook_func(sym, (void *) fake_func, (void **) &backup_func);
    }
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    hook_func = entries->hook_func;
    hook_func((void *) fopen, (void *) fake_fopen, (void **) &backup_fopen);
    return on_library_loaded;
}
```

---

## 4. Reference Code Examples

Explore complete, runnable reference implementations in the `examples/` directory:

- 📁 **[Kotlin Modern Module](./examples/kotlin_modern_module/ModuleMain.kt)**: Complete Kotlin module demonstrating Interceptor chains, Remote Preferences, Invokers, and Hot Reload.
- 📁 **[Java Modern Module](./examples/java_modern_module/ModuleMain.java)**: Complete Java module implementation.
- 📁 **[Native Hook Module](./examples/native_hook_module/)**: Full C++ NDK native hooking project with `CMakeLists.txt` and JNIEnv hooks.
- 📁 **[Service UI App](./examples/service_ui_app/)**: Module configuration Activity with `XposedServiceHelper`, Remote Preferences editor, dynamic scope requests, and hot reload trigger.
