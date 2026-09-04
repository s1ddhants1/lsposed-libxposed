# Master Reference Index for LSPosed & LibXposed

This index provides a complete, exhaustive catalog of all technical reference documentation, APIs, architectural guides, and example implementations within the `lsposed-libxposed` skill.

---

## 1. Quick Navigation Table

| Document | Key Focus Areas | Best For |
| :--- | :--- | :--- |
| **[modern_libxposed_api.md](./modern_libxposed_api.md)** | `XposedModule`, `XposedInterface`, Interceptor Chains, `HookBuilder`, `Invoker`, `deoptimize`, `hookClassInitializer`, `ExceptionMode` | Java/Kotlin hook implementation, argument mutation, original calls, runtime deoptimization |
| **[hot_reloading_and_lifecycle.md](./hot_reloading_and_lifecycle.md)** | Process startup sequence, `onModuleLoaded`, `onPackageReady`, `onSystemServerStarting`, Live Hot Reload (`onHotReloading` / `onHotReloaded`), memory leak prevention | Module lifecycle, hooking system services, state preservation across reloads |
| **[service_and_ipc.md](./service_and_ipc.md)** | Remote Preferences, Remote Files, `XposedService`, `XposedServiceHelper`, `XposedProvider`, dynamic scope management, hot reload triggers | Inter-process communication between module UI and hooked apps |
| **[native_hooking.md](./native_hooking.md)** | NDK C/C++ hooking, `NativeAPIEntries`, `native_init`, `on_library_loaded`, `JNIEnv` function table hooking, CMake setup | Native library hooks, C standard library interception (`fopen`), JNI hooks |
| **[helper_and_matcher_dsl.md](./helper_and_matcher_dsl.md)** | `libxposed:helper` & `helper-ktx`, `Reflector`, type-safe Matchers, `@DexAnalysis` bytecode analysis, disk caching | Obfuscated apps, reflection without string literals, bytecode pattern matching |
| **[project_setup_and_migration.md](./project_setup_and_migration.md)** | Gradle setup, `libs.versions.toml`, `module.prop`, packaging merges, ProGuard rules, Android Lint, legacy XposedBridge migration | Bootstrapping new modules, configuring build files, migrating legacy modules |
| **[lspctl_cli_and_daemon_internals.md](./lspctl_cli_and_daemon_internals.md)** | `lspctl` CLI syntax, JSON schemas, scope mutations, Developer Mode guards, SQLite database schema (`modules_config.db`), safe mode recovery | Live device testing, shell automation, debugging daemon state, disaster recovery |

---

## 2. Exhaustive Section Catalog

### 2.1 [Modern LibXposed API Specification](./modern_libxposed_api.md)
* **1. Core Architecture Overview**: Differences between legacy XposedBridge and modern LibXposed API 101/102+.
* **2. API Level Matrix & Capabilities**: API constants (`API_101`, `API_102`, `LIB_API`), framework property bitmask (`PROP_CAP_SYSTEM`, `PROP_CAP_REMOTE`, `PROP_RT_API_PROTECTION`), runtime query methods.
* **3. The Interceptor Chain Hooking Model**: OkHttp-style interceptor execution pipeline.
  * **3.1 `XposedInterface.Hooker` & `Chain` Interfaces**: Parameter access (`getArg`, `getArgs`), instance reference (`thisObject`), chain progression (`proceed()`, `proceed(Object[])`).
  * **3.2 Constructor Hooking Rules**: Return value constraints (constructors MUST return `null`/`Unit`), execution order.
  * **3.3 Hook Priorities**: `PRIORITY_HIGHEST` (`Integer.MAX_VALUE`), `PRIORITY_DEFAULT` (50), `PRIORITY_LOWEST` (`Integer.MIN_VALUE`).
  * **3.4 Exception Handling Modes (`ExceptionMode`)**: `PROTECTIVE` (swallows exceptions, logs errors, preserves host stability) vs `PASSTHROUGH` (rethrows exceptions to host app).
  * **3.5 Replacing & Detaching Hooks**: `setId(String)`, `HookHandle`, `detach()`.
* **4. Invoker Subsystem**:
  * **4.1 `Invoker<T, U>`**: Original unhooked execution (`Invoker.Type.ORIGIN`), special non-virtual invocation (`invokeSpecial`).
  * **4.2 `CtorInvoker<T>`**: Constructor invocation without hooks (`newInstanceSpecial`).
* **5. Runtime Method Deoptimization**: `deoptimize(Executable)` — bypassing JIT/AOT inlining on Android ART.
* **6. Static Initializer Hooking (`<clinit>`)**: `hookClassInitializer(Class<?>)` semantics and caveats.
* **7. Logging Subsystem**: `log(int priority, String tag, String msg, Throwable tr)`.

### 2.2 [Lifecycle & Hot Reloading Architecture](./hot_reloading_and_lifecycle.md)
* **1. Module Initialization Lifecycle**: Process startup flow chart (Zygisk -> LSPlant -> `onModuleLoaded` -> `onPackageReady`).
  * **1.1 Lifecycle Callbacks**: `onModuleLoaded`, `onPackageLoaded` (Android Q+), `onPackageReady`, `onSystemServerStarting`.
* **2. Hot Reloading Architecture (API 102+)**:
  * **2.1 How Hot Reload Works**: ClassLoader re-instantiation, hook replacement without killing target process.
  * **2.2 `onHotReloading` Callback**: State serialization via `Bundle`, returning boolean consent.
  * **2.3 `onHotReloaded` Callback**: Receiving saved state in new generation classloader.
  * **2.4 Preventing ClassLoader Memory Leaks**: Strict prohibitions against caching `Class<?>`, `Method`, or target instances in static fields.
* **3. System Server Hooking Guide**: Scoping `android` / `system_server`, permissions, SELinux considerations.

### 2.3 [Service & Inter-Process Communication (IPC)](./service_and_ipc.md)
* **1. Evolution of Content Sharing & IPC**: Comparison of `XSharedPreferences` (world-readable file hack) vs LSPosed Daemon IPC.
* **2. Architecture Diagram**: End-to-end flow between Module App, LSPosed Daemon, and Hooked Target App.
* **3. Remote Preferences**:
  * **3.1 Reading in Hooked Target Process**: `getRemotePreferences(name)`, real-time change listeners (`registerOnSharedPreferenceChangeListener`).
  * **3.2 Writing in Module Configuration App**: `XposedService.getRemotePreferences(name).edit()`, transactions.
  * **3.3 Deleting Remote Preferences**: `deleteRemotePreferences(name)`.
* **4. Remote Files (Binary Data & Large Blobs)**:
  * **4.1 Writing a Remote File (Module App)**: `openRemoteFile(name, mode)` via `ParcelFileDescriptor`.
  * **4.2 Reading a Remote File (Hooked Target)**: Streaming binary data across process boundaries.
  * **4.3 Listing & Deleting Remote Files**: `listRemoteFiles()`, `deleteRemoteFile(name)`.
* **5. Module App Integration Guide**:
  * **5.1 Gradle Dependencies**: `io.github.libxposed:service:102.0.0`.
  * **5.2 Manifest Provider Setup**: Registering `XposedProvider` with authority `io.github.libxposed.service.XposedProvider`.
  * **5.3 Application Class Setup**: `XposedServiceHelper.registerListener` lifecycle, service connection handling.
* **6. Service Diagnostics & Properties**: Checking framework version, service status, and properties from UI.
* **7. Dynamic Scope Management**: `XposedService.requestScope(packageName, callback)` for runtime scope elevation.
* **8. Querying Running Targets & Triggering Hot Reload**: Enumerating active target processes (`getRunningTargets()`) and issuing `hotReload(packageName)`.

### 2.4 [Native Hooking Specification & Guide](./native_hooking.md)
* **1. Native Architecture & Entry Points**:
  * `NativeAPIEntries` struct (`version`, `hook_func`, `unhook_func`).
  * `native_init` export with `extern "C" [[gnu::visibility("default")]] [[gnu::used]]`.
  * `NativeOnModuleLoaded` callback.
* **2. Hooking Native Functions**:
  * C standard library hooking (`fopen`, `stat`).
  * Dynamic library load interception (`on_library_loaded` via `dlsym`).
  * ART `JNIEnv` function table hooking in `JNI_OnLoad`.
* **3. Packaging & Build Configuration**:
  * `CMakeLists.txt` configuration for NDK.
  * `META-INF/xposed/native_init.list`.
  * Multi-architecture packaging (`android:multiArch="true"`, `android:extractNativeLibs="false"`).

### 2.5 [Helper & Matcher DSL Guide](./helper_and_matcher_dsl.md)
* **1. Reflector DSL**: Type-safe reflection without hardcoded string names or unchecked exceptions.
* **2. Matcher Subsystem**:
  * Finding classes by superclass, implemented interfaces, annotations, modifiers.
  * Finding methods by parameter types, return types, modifiers, parameter count.
* **3. `@DexAnalysis` Bytecode Analysis**:
  * Inspecting minified/obfuscated code without fragile method names.
  * Matching by referenced string constants, field read/write access, callee method invocations, and opcode patterns.
* **4. Caching & Performance**: `setCacheInputStream`, `setCacheOutputStream`, `setCacheChecker` for instant subsequent app launches.

### 2.6 [Project Setup, Packaging & Migration Guide](./project_setup_and_migration.md)
* **1. Project Configuration & Dependencies**:
  * Version catalog (`libs.versions.toml`) coordinates.
  * `app/build.gradle.kts` setup: `compileOnly(libxposed.api)` vs `implementation(libxposed.service)`.
  * AGP 8+ Packaging DSL (`merges += "META-INF/xposed/*"`, `excludes += "**"`).
* **2. Modern Metadata Files (`META-INF/xposed/`)**:
  * `module.prop`: `minApiVersion`, `targetApiVersion`, `staticScope`, `autoHotReload`.
  * `java_init.list`: Fully qualified Java/Kotlin entry classes.
  * `native_init.list`: Entry points for native NDK libraries.
  * `scope.list`: Default target package names.
* **3. ProGuard / R8 Consumer Rules**: Preserving entry points and serialization classes.
* **4. Tooling & Lint Checks**: `@SinceApi` and `@InternalApi` annotations.
* **7. Migration Guide: Legacy XposedBridge to Modern LibXposed**:
  * Direct translation lookup table.
  * Step-by-step conversion runbook.

### 2.7 [Daemon Architecture, lspctl CLI & Device Internals](./lspctl_cli_and_daemon_internals.md)
* **1. The `lspctl` Command-Line Interface**:
  * Binary path: `/data/adb/modules/zygisk_lsposed/lspctl`.
  * Invocation syntax via `adb shell su -c`.
* **2. Command Reference & JSON Schemas**:
  * `lspctl status --json`: Daemon version, zygisk mode, API version, SELinux status.
  * `lspctl module list --json`: Enumerating installed modules, version codes, enabled state.
  * `lspctl module show <pkg> --json`: Inspecting scopes, entry points, and flags.
  * `lspctl scope list <pkg> --json`: Listing target packages.
  * `lspctl module enable/disable`: Toggling module state.
  * `lspctl scope set/add/remove`: Mutating target app scopes.
  * **Developer Mode Guard**: Unlocking mutations via `manager_post_notification` or SQLite flag.
* **3. Internal Filesystem Layout & Architecture**:
  * Module root: `/data/adb/modules/zygisk_lsposed/`.
  * State directory: `/data/adb/lspd/` (`modules_config.db`, `log`, `monitor`).
* **4. Daemon Database Schemas (`modules_config.db`)**:
  * SQLite tables: `modules`, `scopes`, `lspd_configs`.
  * Known configuration keys: `dex2oat_enabled`, `verbose_log`, `developer_mode`.
* **5. Safe Mode & Emergency Recovery Subsystem**: Recovering bootloops caused by bad hooks.

---

## 3. Concept & API Cross-Reference

| Concept / API Symbol | Defining Document | Primary Class / Signature |
| :--- | :--- | :--- |
| `XposedModule` | [modern_libxposed_api.md](./modern_libxposed_api.md) | `abstract class XposedModule` |
| `XposedInterface` | [modern_libxposed_api.md](./modern_libxposed_api.md) | `interface XposedInterface` |
| Interceptor Hook | [modern_libxposed_api.md](./modern_libxposed_api.md) | `hook(Executable).intercept(Hooker)` |
| `Chain` | [modern_libxposed_api.md](./modern_libxposed_api.md) | `chain.proceed()`, `chain.getArg(int)` |
| `Invoker` | [modern_libxposed_api.md](./modern_libxposed_api.md) | `getInvoker(Executable).invoke(Object, Object...)` |
| Method Deoptimization | [modern_libxposed_api.md](./modern_libxposed_api.md) | `deoptimize(Executable)` |
| `<clinit>` Hook | [modern_libxposed_api.md](./modern_libxposed_api.md) | `hookClassInitializer(Class<?>)` |
| `ExceptionMode` | [modern_libxposed_api.md](./modern_libxposed_api.md) | `ExceptionMode.PROTECTIVE`, `ExceptionMode.PASSTHROUGH` |
| `onModuleLoaded` | [hot_reloading_and_lifecycle.md](./hot_reloading_and_lifecycle.md) | `onModuleLoaded(ModuleLoadedParam)` |
| `onPackageReady` | [hot_reloading_and_lifecycle.md](./hot_reloading_and_lifecycle.md) | `onPackageReady(PackageReadyParam)` |
| `onSystemServerStarting` | [hot_reloading_and_lifecycle.md](./hot_reloading_and_lifecycle.md) | `onSystemServerStarting(SystemServerStartingParam)` |
| `onHotReloading` | [hot_reloading_and_lifecycle.md](./hot_reloading_and_lifecycle.md) | `onHotReloading(HotReloadingParam): Boolean` |
| `onHotReloaded` | [hot_reloading_and_lifecycle.md](./hot_reloading_and_lifecycle.md) | `onHotReloaded(HotReloadedParam)` |
| Remote Preferences (Read) | [service_and_ipc.md](./service_and_ipc.md) | `getRemotePreferences(String)` |
| Remote Preferences (Write)| [service_and_ipc.md](./service_and_ipc.md) | `XposedService.getRemotePreferences(String).edit()` |
| Remote Files | [service_and_ipc.md](./service_and_ipc.md) | `openRemoteFile(String)`, `ParcelFileDescriptor` |
| `XposedServiceHelper` | [service_and_ipc.md](./service_and_ipc.md) | `XposedServiceHelper.registerListener(Context, OnServiceListener)` |
| `XposedProvider` | [service_and_ipc.md](./service_and_ipc.md) | `io.github.libxposed.service.XposedProvider` |
| Dynamic Scope Elevation | [service_and_ipc.md](./service_and_ipc.md) | `xposedService.requestScope(String, OnScopeEventListener)` |
| Native Entry Point | [native_hooking.md](./native_hooking.md) | `NativeOnModuleLoaded native_init(const NativeAPIEntries *)` |
| Native Library Callback | [native_hooking.md](./native_hooking.md) | `void on_library_loaded(const char *name, void *handle)` |
| `Reflector` DSL | [helper_and_matcher_dsl.md](./helper_and_matcher_dsl.md) | `Reflector.on(ClassLoader).findClass(...)` |
| `@DexAnalysis` Bytecode | [helper_and_matcher_dsl.md](./helper_and_matcher_dsl.md) | `DexParser`, `MethodMatcher.hasOpcodeSequence(...)` |
| `module.prop` | [project_setup_and_migration.md](./project_setup_and_migration.md) | `minApiVersion`, `targetApiVersion`, `autoHotReload` |
| `java_init.list` | [project_setup_and_migration.md](./project_setup_and_migration.md) | `src/main/resources/META-INF/xposed/java_init.list` |
| Packaging Merges | [project_setup_and_migration.md](./project_setup_and_migration.md) | `packaging.resources.merges += "META-INF/xposed/*"` |
| `lspctl` CLI | [lspctl_cli_and_daemon_internals.md](./lspctl_cli_and_daemon_internals.md) | `lspctl status`, `lspctl module`, `lspctl scope` |
| `modules_config.db` | [lspctl_cli_and_daemon_internals.md](./lspctl_cli_and_daemon_internals.md) | `/data/adb/lspd/config/modules_config.db` |

---

## 4. Example Projects Map

| Example Directory | Language | Description | Key Topics Demonstrated |
| :--- | :--- | :--- | :--- |
| **[`examples/kotlin_modern_module/`](../examples/kotlin_modern_module/)** | Kotlin | Full modern module implementation | `XposedModule`, Interceptor chains, Invoker original calls, Remote Preferences listener, Remote Files, Hot Reload state serialization |
| **[`examples/java_modern_module/`](../examples/java_modern_module/)** | Java | Complete Java module equivalent | Java syntax for `hook().intercept()`, Constructor hooking (`return null`), `openRemoteFile` try-with-resources |
| **[`examples/native_hook_module/`](../examples/native_hook_module/)** | C++ / NDK | Complete native C++ hooking project | `CMakeLists.txt`, `native_init`, `NativeAPIEntries`, hooking `fopen`, `dlsym` library tracking, ART `JNIEnv` function table hook |
| **[`examples/service_ui_app/`](../examples/service_ui_app/)** | Kotlin | Module settings and companion UI app | `XposedServiceHelper`, Remote Preferences editor, dynamic scope request flow, querying running targets, triggering hot reload |
