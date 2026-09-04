# LSPosed & Modern LibXposed Skill

An agent skill for modern Android ART hooking, Xposed module development, and native NDK hooking using **LSPosed** and the **LibXposed** framework (API 101, 102+).

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
- **Invoker Subsystem**: Unhooked original invocations (`Invoker.Type.ORIGIN`), special non-virtual calls (`invokeSpecial`), constructor chaining (`CtorInvoker`).
- **Process Lifecycle & Hot Reloading**: `onPackageReady`, `onSystemServerStarting`, live module reload (`onHotReloading` / `onHotReloaded`) without app restarts.
- **Service IPC & Remote Preferences**: Daemon-backed cross-process configuration, Remote Files, `XposedServiceHelper`, dynamic scope requests.
- **Native C/C++ Hooks**: NDK hooks via `NativeAPIEntries`, `native_init`, `NativeOnModuleLoaded`, and `JNIEnv` function table hooks.
- **Reflection & Bytecode Matching**: `libxposed:helper`, `Reflector`, and `@DexAnalysis` for obfuscated applications.
- **Daemon Architecture & lspctl CLI**: Remote management CLI, SQLite databases (`modules_config.db`), Developer Mode guards, and safe mode recovery.
- **Legacy Migration**: Step-by-step migration from legacy `XposedBridge` (`de.robv.android.xposed`) to modern LibXposed standards.

## 📂 Repository Layout

```
├── SKILL.md                 # LLM entry point: anti-hallucination rules, intent router, templates, diagnostics
├── references/              # Detailed technical documentation
│   ├── INDEX.md             # Master reference catalog and API cross-reference table
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

## 📄 License

Apache-2.0 / MIT
