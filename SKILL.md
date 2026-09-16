---
name: lsposed-libxposed
description: >-
  LSPosed, modern LibXposed (API 101/102+), and rootless LSPatch guide for Android ART hooking and module development.
  Use for creating, debugging, testing, or migrating Xposed modules, hooking Java/Kotlin methods or constructors,
  using modern LibXposed APIs (XposedModule, Hooker, Chain, HookBuilder, Invoker, deoptimize, hookClassInitializer),
  configuring LSPosed metadata (module.prop, java_init.list, scope.list),
  implementing IPC (Remote Preferences, Remote Files, XposedService, XposedServiceHelper, XposedProvider),
  handling lifecycle and live hot reloading (onModuleLoaded, onPackageReady, onHotReloading, onHotReloaded),
  NDK C/C++ native hooks (NativeAPIEntries, native_init, on_library_loaded, JNIEnv),
  reflection and bytecode matching (libxposed:helper, Reflector, @DexAnalysis),
  managing via lspctl CLI, patching rootless APKs via LSPatch (lspatch.jar, Manager Mode vs Integrated Mode,
  Signature Bypass Levels 0-3, DocumentsProvider SAF private data access), or migrating legacy XposedBridge (de.robv.android.xposed).
  Do NOT use for Frida scripts or generic Magisk modules without Xposed.
---

# LSPosed, Modern LibXposed & LSPatch Development Guide

Expert guide and reference manual for Android ART hooking, Xposed module development, and rootless APK patching using **LSPosed**, modern **LibXposed** (API 101 / 102+), and **LSPatch**.

> [!IMPORTANT]
> **LLM Operating Constraint**: Modern LibXposed completely supersedes legacy `de.robv.android.xposed` (XposedBridge).
> **NEVER** generate legacy XposedBridge code (`XC_MethodHook`, `XposedBridge`, `XposedHelpers`, `XSharedPreferences`, `assets/xposed_init`, `IXposedHookLoadPackage`) unless the user explicitly asks for legacy migration.

---

## 1. Hallucination Traps & Golden Rules (CRITICAL)

LLM training data is heavily biased toward 10-year-old deprecated XposedBridge APIs. You **MUST** strictly adhere to the modern LibXposed and LSPatch standards:

| Topic | ❌ Deprecated Hallucination (DO NOT USE) | ✅ Modern LibXposed & LSPatch Standard (MANDATORY) |
| :--- | :--- | :--- |
| **API Dependency** | `compileOnly("de.robv.android.xposed:api:82")` | `compileOnly("io.github.libxposed:api:102.0.0")` |
| **Service Dependency** | Hand-rolled ContentProvider / Broadcast IPC | `implementation("io.github.libxposed:service:102.0.0")` (Module UI only) |
| **Module Entry Config**| `assets/xposed_init` or Manifest `<meta-data>` | `src/main/resources/META-INF/xposed/java_init.list` + `module.prop` |
| **Base Class** | `implements IXposedHookLoadPackage` | `class ModuleMain : XposedModule()` |
| **Hook Paradigm** | `XC_MethodHook` (`beforeHookedMethod` / `afterHookedMethod`) | `hook(method).intercept { chain -> ... }` (OkHttp-style interceptor) |
| **Argument Mutation** | `param.args[0] = "newVal"` | `chain.proceed(arrayOf("newVal", chain.getArg(1)))` |
| **Return Value** | `param.setResult(value)` | Return value directly from interceptor: `return value` or `chain.proceed()` |
| **Constructor Return** | Returning modified instances from constructor | **MUST return `null` / `Unit`**. Returning anything else crashes the ART runtime! |
| **Chain Proceed** | Calling `chain.proceed()` multiple times | `chain.proceed()` **MUST** be called at most once per interception |
| **Original Invocation**| `XposedBridge.invokeOriginalMethod(...)` (slow reflection) | `getInvoker(method).setType(Invoker.Type.ORIGIN).invoke(...)` |
| **Inlined Methods** | Hooks silently failing to trigger | Call `deoptimize(method)` on the `Executable` before hooking |
| **Preferences** | `new XSharedPreferences(...)` (world-readable file hack) | `getRemotePreferences("name")` (LSPosed Daemon or LSPatch SQLite IPC) |
| **AGP Packaging** | Omitting packaging configuration in Gradle | `packaging.resources { merges += "META-INF/xposed/*"; excludes += "**" }` |
| **Hot Reload State** | Storing `ClassLoader`, `Class<?>`, or `Method` in static vars | Strictly clear all target references in `onHotReloading` to avoid ClassLoader leaks |
| **Rootless Patching** | Decompiling/smali patching target APK | Use `lspatch.jar` with `origin.apk` zero-copy nesting (`ApkPatcher`) |
| **LSPatch Modes** | Assuming embedded modules can hot-reload | Use `--manager` for live hot reload; Integrated (`-m`) is standalone static |
| **Signature Bypass** | Disabling signature checks manually in smali | Set `-l 1` (PM), `-l 2` (libc `__openat`), or `-l 3` (raw ARM64 `svc #0`) |
| **Rootless Storage** | Hardcoding `/data/adb/` or `/data/misc/` paths | Use `getRemotePreferences()` / Remote Files API (`lspatch-xposed-remote.db`) |

---

## 2. Deterministic Intent & Topic Router

Use this table to immediately jump to the correct technical reference, key classes, and working examples:

| Developer Intent / Task | Primary Reference File & Section | Key APIs / Symbols | Runnable Example |
| :--- | :--- | :--- | :--- |
| **Scaffold new module** | [project_setup_and_migration.md](./references/project_setup_and_migration.md#1-project-configuration-dependencies) | `module.prop`, `java_init.list`, `scope.list` | [kotlin_modern_module](./examples/kotlin_modern_module/) |
| **Hook method / intercept args** | [modern_libxposed_api.md](./references/modern_libxposed_api.md#3-the-interceptor-chain-hooking-model) | `hook(Executable)`, `Chain.proceed()`, `getArg()` | [kotlin_modern_module](./examples/kotlin_modern_module/) |
| **Hook constructor** | [modern_libxposed_api.md](./references/modern_libxposed_api.md#constructor-invoker-ctorinvokert) | `hook(Constructor)`, return `null`/`Unit` | [kotlin_modern_module](./examples/kotlin_modern_module/) |
| **Invoke original / bypass hooks** | [modern_libxposed_api.md](./references/modern_libxposed_api.md#5-invoker-subsystem-getinvoker) | `Invoker`, `CtorInvoker`, `invokeSpecial` | [kotlin_modern_module](./examples/kotlin_modern_module/) |
| **Deoptimize inlined ART methods** | [modern_libxposed_api.md](./references/modern_libxposed_api.md#6-method-deoptimization-deoptimize) | `deoptimize(Executable)` | [modern_libxposed_api.md](./references/modern_libxposed_api.md) |
| **Hook `<clinit>` static init** | [modern_libxposed_api.md](./references/modern_libxposed_api.md#7-static-initializer-hooking-hookclassinitializer) | `hookClassInitializer(Class<?>)` | [modern_libxposed_api.md](./references/modern_libxposed_api.md) |
| **Read Remote Preferences (target)** | [service_and_ipc.md](./references/service_and_ipc.md#3-remote-preferences) | `getRemotePreferences(name)`, `OnSharedPreferenceChangeListener` | [kotlin_modern_module](./examples/kotlin_modern_module/) |
| **Write Remote Preferences (UI)** | [service_and_ipc.md](./references/service_and_ipc.md#5-module-app-integration-guide) | `XposedServiceHelper`, `XposedProvider`, `.edit()` | [service_ui_app](./examples/service_ui_app/) |
| **Request dynamic scope elevation**| [service_and_ipc.md](./references/service_and_ipc.md#7-dynamic-scope-management) | `XposedService.requestScope(pkg, callback)` | [service_ui_app](./examples/service_ui_app/) |
| **Live Hot Reloading (API 102)** | [hot_reloading_and_lifecycle.md](./references/hot_reloading_and_lifecycle.md#2-hot-reloading-architecture-api-102) | `onHotReloading`, `onHotReloaded`, `Bundle` | [kotlin_modern_module](./examples/kotlin_modern_module/) |
| **Hook system_server / OS services**| [hot_reloading_and_lifecycle.md](./references/hot_reloading_and_lifecycle.md#4-onsystemserverstartingsystemserverstartingparam-param) | `onSystemServerStarting(param)` | [hot_reloading_and_lifecycle.md](./references/hot_reloading_and_lifecycle.md) |
| **Native C/C++ Hooking (NDK)** | [native_hooking.md](./references/native_hooking.md#1-native-hooking-architecture) | `NativeAPIEntries`, `native_init`, `on_library_loaded` | [native_hook_module](./examples/native_hook_module/) |
| **Obfuscated target matching** | [helper_and_matcher_dsl.md](./references/helper_and_matcher_dsl.md#8-high-speed-reflector-engine) | `Reflector`, `@DexAnalysis`, `DexParser` | [helper_and_matcher_dsl.md](./references/helper_and_matcher_dsl.md) |
| **Migrate legacy XposedBridge** | [project_setup_and_migration.md](./references/project_setup_and_migration.md#7-migration-guide-legacy-xposedbridge-to-modern-libxposed) | Legacy Translation Matrix | [project_setup_and_migration.md](./references/project_setup_and_migration.md) |
| **Device testing via `lspctl` CLI**| [lspctl_cli_and_daemon_internals.md](./references/lspctl_cli_and_daemon_internals.md#1-the-lspctl-command-line-interface) | `lspctl status`, `lspctl module`, `lspctl scope` | [lspctl_cli_and_daemon_internals.md](./references/lspctl_cli_and_daemon_internals.md) |
| **Patch APK rootless (Manager Mode)** | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#41-local--manager-mode-patchmodelocal) | `lspatch.jar --manager -l 2` | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#recipe-1-standard-manager-mode-patch) |
| **Patch APK rootless (Integrated Mode)**| [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#42-integrated--portable-mode-patchmodeintegrated) | `lspatch.jar -m <module.apk> -l 2` | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#recipe-2-standalone-portable-patch-with-embedded-modules) |
| **Bypass APK Signature Checks**| [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#5-signature-bypass-subsystem-deep-dive) | `-l 1` (PM), `-l 2` (openat), `-l 3` (svc) | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md) |
| **Access App Private Data (SAF)**| [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#6-storage-access--documents-provider) | `--documents-provider`, SAF authority | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md) |
| **Patch Split APKs / Bundles** | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#8-command-line-tool-reference-lspatchjar) | `lspatch.jar base.apk split_*.apk` | [lspatch_rootless_patching.md](./references/lspatch_rootless_patching.md#recipe-3-multi-apk-app-bundle--split-apks-patching) |
| **Complete Reference Master Index**| [INDEX.md](./references/INDEX.md) | Exhaustive section catalog, cross-reference table | [INDEX.md](./references/INDEX.md) |

---

## 3. Canonical Zero-Shot Templates

### 3.1 Gradle Setup (`app/build.gradle.kts`)
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.module"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.module"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // MANDATORY: Ensure META-INF/xposed files are packaged and not discarded
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    // MANDATORY: API is compileOnly (injected by LSPosed in target processes)
    compileOnly("io.github.libxposed:api:102.0.0")

    // OPTIONAL: Service is implementation (packaged into module APK for UI app)
    // implementation("io.github.libxposed:service:102.0.0")
}
```

### 3.2 Metadata Files (`src/main/resources/META-INF/xposed/`)

#### `module.prop`
```properties
minApiVersion=101
targetApiVersion=102
staticScope=false
autoHotReload=true
```

#### `java_init.list`
```text
com.example.module.ModuleMain
```

#### `scope.list`
```text
com.target.application
```

---

### 3.3 Minimal Compilable Module Entry (`ModuleMain.kt`)

```kotlin
package com.example.module

import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Invoker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ModuleMain : XposedModule() {

    companion object {
        private const val TAG = "MyXposedModule"
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Only hook the primary app package (ignore secondary code contexts)
        if (!param.isFirstPackage) return

        val classLoader = param.classLoader

        try {
            val targetClass = Class.forName("com.target.application.AuthService", true, classLoader)
            val authMethod = targetClass.getDeclaredMethod("isVipUser", String::class.java)
            val ctor = targetClass.getDeclaredConstructor()

            // 1. Hook Method: Spoof return value or modify execution
            hook(authMethod)
                .setId("auth_vip_hook")
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val userId = chain.getArg(0) as String
                    log(Log.INFO, TAG, "isVipUser called for: $userId")

                    // Proceed with original call if desired:
                    // val originalResult = chain.proceed() as Boolean

                    // Return spoofed value:
                    true
                }

            // 2. Hook Constructor: CRITICAL rule -> MUST return null/Unit
            hook(ctor)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    log(Log.INFO, TAG, "AuthService instance created: ${chain.thisObject}")
                    chain.proceed()
                    null // Always return null/Unit for constructors
                }

        } catch (e: ReflectiveOperationException) {
            log(Log.ERROR, TAG, "Hook target resolution failed", e)
        }
    }
}
```

---

### 3.4 Minimal Compilable Java Module Entry (`ModuleMain.java`)

```java
package com.example.module;

import android.util.Log;
import androidx.annotation.NonNull;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private static final String TAG = "MyJavaXposedModule";

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;

        try {
            Class<?> targetClass = Class.forName("com.target.application.AuthService", true, param.getClassLoader());
            Method method = targetClass.getDeclaredMethod("isVipUser", String.class);
            Constructor<?> ctor = targetClass.getDeclaredConstructor();

            // Hook Method
            hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    log(Log.INFO, TAG, "isVipUser intercepted: " + chain.getArg(0));
                    return true; // Return override value
                });

            // Hook Constructor (Must return null)
            hook(ctor)
                .intercept(chain -> {
                    chain.proceed();
                    return null; // Constructors must return null
                });

        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, TAG, "Hook setup failed", e);
        }
    }
}
```

---

### 3.5 Canonical LSPatch CLI Recipes (`lspatch.jar`)

#### Manager Mode (Dynamic Module Scope & Hot Reload)
```bash
java -jar lspatch.jar --manager -l 2 -o ./out -f target.apk
```

#### Standalone Integrated Mode (Zero-Manager Portable APK)
```bash
java -jar lspatch.jar -m my_module.apk -l 2 -o ./out -f target.apk
```

#### Split APKs / App Bundles Patching
```bash
java -jar lspatch.jar --manager -l 2 -o ./out -f base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk
```

#### Reverse Engineering & Security Analysis Patch
```bash
java -jar lspatch.jar -m my_module.apk -d --cleartext --documents-provider -l 2 -o ./out -f target.apk
```

---

## 4. Core Hooking & IPC Patterns Cheatsheet

### 4.1 Modifying Arguments & Proceeding
```kotlin
hook(method).intercept { chain ->
    val originalArg = chain.getArg(0) as String
    val modifiedArgs = arrayOf("spoofed_$originalArg", chain.getArg(1))
    chain.proceed(modifiedArgs)
}
```

### 4.2 Invoking Unhooked Original Method (`Invoker`)
```kotlin
// Bypasses all hooks registered on the method
val unhookedResult = getInvoker(method)
    .setType(Invoker.Type.ORIGIN)
    .invoke(chain.thisObject, "arg1", 123)
```

### 4.3 Invoking Special / Super Method (`invokeSpecial`)
```kotlin
// Invokes parent method implementation without virtual dispatch
val superResult = getInvoker(method).invokeSpecial(chain.thisObject, "arg1")
```

### 4.4 Deoptimizing Inlined Methods
```kotlin
// If an ART method is inlined by JIT/AOT compiler, deoptimize it before hooking:
deoptimize(targetMethod)
hook(targetMethod).intercept { chain -> chain.proceed() }
```

### 4.5 Hooking Static Initializer (`<clinit>`)
```kotlin
hookClassInitializer(targetClass).intercept { chain ->
    log(Log.INFO, TAG, "Class static initialization starting")
    chain.proceed()
    null // Always return null/Unit
}
```

### 4.6 Reading Remote Preferences (Target Process)
```kotlin
// Hooked process (read-only IPC backed by daemon):
val prefs = getRemotePreferences("module_settings")
val isEnabled = prefs.getBoolean("feature_enabled", false)

// Live listener:
prefs.registerOnSharedPreferenceChangeListener { sp, key ->
    val updated = sp.getBoolean(key, false)
    log(Log.INFO, TAG, "Setting $key updated to: $updated")
}
```

### 4.7 Writing Remote Preferences (Module App UI)
```kotlin
// Module App UI Activity (requires io.github.libxposed:service):
App.xposedService?.getRemotePreferences("module_settings")?.edit()
    ?.putBoolean("feature_enabled", true)
    ?.apply()
```

---

## 5. Error Diagnostics & Self-Healing Matrix

When encountering compilation failures, test errors, or logcat exceptions, look up the symptom below:

| Symptom / Error | Root Cause | Immediate Fix |
| :--- | :--- | :--- |
| `ClassNotFoundException: io.github.libxposed.api.XposedModule` | Dependency set to `implementation` or target app not injected by LSPosed | Ensure `compileOnly("io.github.libxposed:api:102.0.0")` in Gradle. Verify target package is added to scope in LSPosed Manager. |
| `IllegalArgumentException: return value does not match constructor` | Constructor interceptor returned a non-null value | Change constructor interceptor return value to `return null` (Java) or `null` (Kotlin). |
| `IllegalStateException: proceed() called multiple times` | Interceptor chain invoked `chain.proceed()` more than once | Store the result of `chain.proceed()` in a variable instead of calling it multiple times. |
| Module not recognized / marked invalid in LSPosed Manager | Missing or improperly packaged metadata files | Verify `META-INF/xposed/module.prop` and `java_init.list` exist in APK root. Add `merges += "META-INF/xposed/*"` in `packaging.resources`. |
| Hooks fail on inlined methods | Android ART JIT/AOT compiled the method inline | Call `deoptimize(method)` on the `Method` object prior to registering `hook(method)`. |
| Remote preferences return default values in hooked app | Scope not enabled, or permission/naming mismatch | Verify module is enabled for target package in LSPosed. Verify preference file name string matches exactly between App UI and target hook. |
| OutOfMemoryError / ClassLoader leak on Hot Reload | Static fields holding target `ClassLoader` or `Class<?>` instances | Clear all static caches, lists, and listeners inside `onHotReloading(HotReloadingParam)`. |
| `SecurityException: lspctl requires Developer Mode` | Developer mode guard active in LSPosed daemon | Run `adb shell su -c 'lspctl --bypass-developer-mode'` or toggle Developer Mode in LSPosed Manager settings. |
| `No installed LSPatch manager carries the loader` | App was patched in Manager Mode, but LSPatch Manager is not installed or has a different package name | Install LSPatch Manager. If cloaked under custom package, re-patch with `--manager-package <name>` or use integrated mode (`-m`). |
| Patched app crashes or detects tampering | Target app verifies APK signatures via libc or raw assembly syscalls | Re-patch with elevated signature bypass: `-l 2` (libc `__openat`) or `-l 3` (ARM64 raw `svc #0` instruction trampolines). |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Installed target APK has higher `versionCode` than patched build | Run `lspatch.jar` with `--version-code <currentVersionCode + 1>`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on splits | Split APKs were signed with differing keys | Pass all split APKs together in a single `lspatch.jar` invocation so they share a key. |
| `HOT_RELOAD_UNSUPPORTED` in target | Live hot reload requested while running in Integrated Mode | Hot reload is supported only in Manager Mode (`--manager`). Integrated mode bakes modules into APK assets statically. |

---

## 6. Verification & Device Testing Workflows

### 6.1 Rooted Testing via `lspctl` CLI
Execute commands via `adb shell su -c`:

```bash
# Check daemon status, version, and zygisk injection
lspctl status --json

# List all installed modules and their status
lspctl module list --json

# Inspect details and active scopes of your module
lspctl module show com.example.module --json

# Enable module
lspctl module enable com.example.module

# Add target package to module scope
lspctl scope add com.example.module com.target.application

# Trigger hot reload on target package (API 102+)
# (Requires XposedService in module app or killing process)
am force-stop com.target.application && monkey -p com.target.application -c android.intent.category.LAUNCHER 1
```

### 6.2 Rootless Testing via `lspatch.jar` CLI & ADB
Test modules on non-rooted devices or emulators using `lspatch.jar`:

```bash
# 1. Pull target APK from connected device
adb shell pm path com.target.application
adb pull /data/app/.../base.apk ./target.apk

# 2. Patch APK with your module (Integrated Mode, Signature Bypass L2)
java -jar lspatch.jar -m my_module.apk -l 2 -o ./out -f target.apk

# 3. Install patched APK
adb install -r ./out/target-*-lspatched.apk

# 4. Stream live LSPatch runtime logs
adb logcat -s LSPatch LSPatch-MetaLoader LSPatch-SigBypass LSPatch-RemotePrefs
```

---

## 7. Master Documentation Index

For exhaustive architectural deep-dives, see:
- 📑 **[Master Reference Index (INDEX.md)](./references/INDEX.md)**: Exhaustive catalog of all sections, classes, and signatures.
- 📦 **[LSPatch Rootless Patching](./references/lspatch_rootless_patching.md)**: Zero-root patching, Manager vs Integrated modes, Signature Bypass (L0-L3), DocumentsProvider.
- 📖 **[Modern LibXposed API](./references/modern_libxposed_api.md)**: Full API 101/102 specification and invokers.
- 🔄 **[Lifecycle & Hot Reloading](./references/hot_reloading_and_lifecycle.md)**: Process phases and hot reload mechanics.
- 📡 **[Service & IPC Guide](./references/service_and_ipc.md)**: Remote Preferences, Remote Files, and `XposedProvider`.
- ⚡ **[Native Hooking](./references/native_hooking.md)**: C/C++ NDK hooking and `native_init`.
- 🔍 **[Helper & Matcher DSL](./references/helper_and_matcher_dsl.md)**: Obfuscated targets, `Reflector`, and `@DexAnalysis`.
- 🛠️ **[Project Setup & Migration](./references/project_setup_and_migration.md)**: Gradle setup and legacy migration guide.
- 💻 **[Daemon Internals & lspctl](./references/lspctl_cli_and_daemon_internals.md)**: CLI reference, SQLite schemas, and safe mode.

