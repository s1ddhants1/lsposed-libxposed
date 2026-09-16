# LSPosed, Modern LibXposed & LSPatch Skill

An agent skill for modern Android ART hooking, Xposed module development, native NDK hooking, and rootless APK patching using **LSPosed**, the **LibXposed** framework (API 101, 102+), and **LSPatch**.

## 🚀 Installation

Install this skill using the `skills` CLI:

```bash
# Global install (recommended for all AI coding agents)
npx skills add s1ddhants1/lsposed-libxposed -g

# Or project-specific install
npx skills add s1ddhants1/lsposed-libxposed
```

## 📖 Topics & Capabilities

- **Modern LibXposed API (API 101/102+)**: `XposedModule`, `Hooker`, OkHttp-style `Chain`, `HookBuilder`, `ExceptionMode`, runtime ART deoptimization, static initializers `<clinit>`.
- **Rootless APK Patching (LSPatch)**: Zero-root module execution via standalone APK repacking (`lspatch.jar`), zero-copy nested `origin.apk` architecture, Local/Manager Mode vs Integrated/Portable Mode, multi-tier Signature Bypass (Levels 0–3: Java PM, libc `__openat`, raw ARM64 `svc #0`), SAF `LSPatchDocumentsProvider` for private data access.
- **Invoker Subsystem**: Unhooked original invocations (`Invoker.Type.ORIGIN`), special non-virtual calls (`invokeSpecial`), constructor chaining (`CtorInvoker`).
- **Process Lifecycle & Hot Reloading**: `onPackageReady`, `onSystemServerStarting`, live module reload (`onHotReloading` / `onHotReloaded`) without app restarts.
- **Service IPC & Remote Preferences**: Daemon-backed and SQLite-backed cross-process configuration (`RemotePreferenceStore`), Remote Files, `XposedServiceHelper`, dynamic scope requests.
- **Native C/C++ Hooks**: NDK hooks via `NativeAPIEntries`, `native_init`, `NativeOnModuleLoaded`, and `JNIEnv` function table hooks.
- **Reflection & Bytecode Matching**: `libxposed:helper`, `Reflector`, and `@DexAnalysis` for obfuscated applications.
- **Daemon Architecture & lspctl CLI**: Remote management CLI, SQLite databases (`modules_config.db`), Developer Mode guards, and safe mode recovery.
- **Legacy Migration**: Step-by-step migration from legacy `XposedBridge` (`de.robv.android.xposed`) to modern LibXposed standards.

## 📂 Repository Layout

```
├── SKILL.md                 # LLM entry point: anti-hallucination rules, intent router, templates, diagnostics
├── references/              # Detailed technical documentation
│   ├── INDEX.md             # Master reference catalog and API cross-reference table
│   ├── lspatch_rootless_patching.md # Comprehensive LSPatch rootless patching guide & CLI reference
│   ├── modern_libxposed_api.md
│   ├── hot_reloading_and_lifecycle.md
│   ├── service_and_ipc.md
│   ├── native_hooking.md
│   ├── helper_and_matcher_dsl.md
│   ├── project_setup_and_migration.md
│   └── lspctl_cli_and_daemon_internals.md
└── examples/                # Runnable reference implementations
    ├── kotlin_modern_module/
    ├── java_modern_module/
    ├── native_hook_module/
    └── service_ui_app/
```

## ⚡ Quick Start: Rootless Patching (`lspatch.jar`)

```bash
# 1. Patch an APK in Manager Mode (for live module management and hot reload)
java -jar lspatch.jar --manager -l 2 -o ./out -f com.target.app.apk

# 2. Patch an APK in Standalone Integrated Mode (zero-dependency portable APK)
java -jar lspatch.jar -m my_module.apk -l 2 -o ./out -f com.target.app.apk

# 3. Patch modern Split APK bundles
java -jar lspatch.jar --manager -l 2 -o ./out -f base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk

# 4. Install and monitor live runtime logs
adb install -r ./out/com.target.app-*-lspatched.apk
adb logcat -s LSPatch LSPatch-MetaLoader LSPatch-SigBypass LSPatch-RemotePrefs
```

## 📄 License

Apache-2.0 / MIT
