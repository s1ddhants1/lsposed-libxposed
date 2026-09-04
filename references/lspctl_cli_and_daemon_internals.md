# LSPosed Daemon Architecture, lspctl CLI & Device Internals

This document provides a technical specification for the **LSPosed Framework Daemon (`lspd`)**, the **`lspctl` Command-Line Interface**, and the internal filesystem / database architecture as verified on **LSPosed 2.2.0-it (build 7866)** with root (`su`) access.

> [!NOTE]
> **Source Verification**: Reverse-engineered and verified directly from the active LSPosed internal testing installation on the connected Android device (`CPH2573`, KernelSU root `uid=0`, API 102, Zygisk mode), decompiled bytecode from `/data/adb/modules/zygisk_lsposed/daemon.apk` and `framework.dex`, and live tests against `/data/adb/modules/zygisk_lsposed/lspctl`.

---

## 1. The `lspctl` Command-Line Interface

`lspctl` is an ADB shell debugging and administration tool designed specifically for developers and AI agents to inspect and configure modules without interacting with the Manager GUI.

```
Usage: lspctl [-hV] [--json] COMMAND
Interactively debug LSPosed modules from a computer.
  -h, --help      Show this help message and exit.
      --json      Print JSON for the current interactive session.
  -V, --version   Print version information and exit.
Commands:
  status  Show LSPosed daemon status.
  stop    Stop the LSPosed daemon.
  module  Inspect or change Xposed modules.
  scope   Inspect or change Xposed module scopes.

This CLI is intended for humans and AI agents debugging modules through ADB.
It is not a stable control protocol. Everything may change without notice.
Do not build apps or unattended integrations against it.
```

### 1.1 Invocation Mechanism
`lspctl` is a POSIX shell wrapper script located at `/data/adb/modules/zygisk_lsposed/lspctl`:
```bash
exec /system/bin/app_process \
  -Djava.class.path="$dir/daemon.apk" \
  -Xnoimage-dex2oat \
  /system/bin --nice-name=lspctl org.lsposed.lspd.cli.CliMain "$@"
```
It requires root (`uid == 0`). If invoked from non-root shells, it exits with:
```json
{"ok":false,"command":"<command>","error":{"code":4,"name":"ROOT_REQUIRED","message":"lspctl must be run as root"}}
```

---

## 2. Command Reference

### 2.1 `lspctl status`
Displays live status of the running LSPosed daemon.

```bash
/data/adb/modules/zygisk_lsposed/lspctl status --json
```

**JSON Output Schema:**
```json
{
  "ok": true,
  "command": "status",
  "data": {
    "daemonRunning": true,
    "versionName": "2.2.0-it",
    "versionCode": 7866,
    "apiVersion": 102,
    "safeMode": false,
    "systemServerRequested": true,
    "installedModules": 9,
    "enabledModuleStates": 6
  }
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `daemonRunning` | boolean | Indicates whether the background daemon process (`lspd`) is alive |
| `versionName` | string | LSPosed framework release string (e.g. `"2.2.0-it"`) |
| `versionCode` | int | Internal build sequence number (e.g. `7866`) |
| `apiVersion` | int | Active LibXposed API specification level (e.g. `102`) |
| `safeMode` | boolean | `true` if safe mode is currently engaged (all hooks disabled) |
| `systemServerRequested` | boolean | `true` if system server injection has been successfully established |
| `installedModules` | int | Total number of installed Xposed modules detected on the system |
| `enabledModuleStates` | int | Count of enabled module-user instances |

---

### 2.2 `lspctl module`

#### `lspctl module list`
Lists all detected modules with metadata and enabled status.

```bash
/data/adb/modules/zygisk_lsposed/lspctl module list [--user=ID] [--enabled | --disabled] [--json]
```

**JSON Output Schema:**
```json
{
  "ok": true,
  "command": "module list",
  "data": {
    "modules": [
      {
        "packageName": "io.github.s1ddhants1.swiftbackupprem",
        "apkPath": "/data/app/~~Vi1BDDisb5KO0Rq1i05Cqg==/io.github.s1ddhants1.swiftbackupprem-BbnirDMeLeiJ8mNyjxDI4g==/base.apk",
        "minApiVersion": 101,
        "targetApiVersion": 102,
        "legacy": false,
        "staticScope": true,
        "autoHotReload": true,
        "versionName": "3.0.1",
        "versionCode": 301,
        "userId": 0,
        "enabled": true
      }
    ]
  }
}
```

| Property | Meaning |
| :--- | :--- |
| `legacy` | `true` for legacy `XposedBridge` (API 54-93); `false` for modern `LibXposed` (API 101+) |
| `staticScope` | Parsed from `module.prop`; indicates scope cannot be expanded by user |
| `autoHotReload` | Parsed from `module.prop`; framework triggers auto reload on APK replace |
| `userId` | The Android user profile ID (e.g. `0` for primary user) |

#### `lspctl module show`
Shows complete configuration and declared scope for a specific module.

```bash
/data/adb/modules/zygisk_lsposed/lspctl module show [--user=ID] <PACKAGE> [--json]
```

**Example:**
```bash
lspctl module show io.github.s1ddhants1.swiftbackupprem --json
```
```json
{
  "ok": true,
  "command": "module show",
  "data": {
    "packageName": "io.github.s1ddhants1.swiftbackupprem",
    "apkPath": "/data/app/~~.../base.apk",
    "minApiVersion": 101,
    "targetApiVersion": 102,
    "legacy": false,
    "staticScope": true,
    "autoHotReload": true,
    "versionName": "3.0.1",
    "versionCode": 301,
    "users": [
      {
        "userId": 0,
        "enabled": true
      }
    ],
    "declaredScope": [
      "org.swiftapps.swiftbackup"
    ]
  }
}
```

---

### 2.3 `lspctl scope`

#### `lspctl scope list`
Lists all scoped target applications assigned to a module.

```bash
/data/adb/modules/zygisk_lsposed/lspctl scope list [--user=ID] <MODULE_PACKAGE> [--json]
```

**Example:**
```bash
lspctl scope list io.github.s1ddhants1.swiftbackupprem --json
```
```json
{
  "ok": true,
  "command": "scope list",
  "data": {
    "modulePackageName": "io.github.s1ddhants1.swiftbackupprem",
    "scope": [
      {
        "packageName": "org.swiftapps.swiftbackup",
        "userId": 0
      }
    ]
  }
}
```

---

### 2.4 Mutation Commands & Developer Mode Protection

In `org.lsposed.lspd.cli.CliCommand`, mutation subcommands are guarded by Developer Mode:

| Command Category | Subcommand | Syntax | Description |
| :--- | :--- | :--- | :--- |
| **Module Mutation** | `enable` | `lspctl module enable --user=ID <PACKAGE>` | Enables a module for a user |
| | `disable` | `lspctl module disable --user=ID <PACKAGE>` | Disables a module for a user |
| | `disable-all` | `lspctl module disable-all [--user=ID \| --all-users]` | Disables all modules |
| **Scope Mutation** | `add` | `lspctl scope add --user=ID <MODULE> <TARGET...>` | Appends targets to module scope |
| | `remove` | `lspctl scope remove --user=ID <MODULE> <TARGET...>` | Removes targets from scope |
| | `set` | `lspctl scope set --user=ID <MODULE> <TARGET...>` | Replaces scope completely |
| | `clear` | `lspctl scope clear [--user=ID \| --all-users] <MODULE>` | Clears all scoped targets |

#### The Developer Mode Guard
When `enable_developer_mode` is `false` (default) in daemon configs:
1. `h7.b()` connects to the daemon monitor socket and sends byte `0x06` (`CHECK_DEVELOPER_MODE`).
2. The daemon replies `0` (disabled).
3. `CliCommand.removeMutationCommands()` dynamically prunes all mutation classes from the picocli command tree, so they do not even appear in `--help`.
4. If invoked directly, the daemon rejects the command with exit code `6`:
```json
{
  "ok": false,
  "command": "module enable",
  "error": {
    "code": 6,
    "name": "DEVELOPER_MODE_REQUIRED",
    "message": "mutation commands require developer mode in LSPosed Manager settings"
  }
}
```
5. Furthermore, mutations require execution from an ADB root shell (validated by `v1.a(callerPid)` checking parent process ancestry). Non-ADB callers receive `AUTH_DENIED` (`code: 4`).

---

## 3. Internal Filesystem Layout & Architecture

### 3.1 Module Directory: `/data/adb/modules/zygisk_lsposed/`

```
/data/adb/modules/zygisk_lsposed/
├── module.prop                 # Magisk/KernelSU/APatch module metadata
├── action.sh                   # Triggered by root manager action button
├── service.sh                  # Starts background daemon at late_start service phase
├── uninstall.sh                # Cleanup hook on module uninstall
├── sepolicy.rule               # SELinux domain transition and file context rules
├── zn_modules.txt              # Extra zygisk companion injection targets
├── framework.dex               # Core LibXposed & compatibility bridge framework
├── daemon.apk                  # Daemon bytecode + lspctl CLI classes
├── daemon                      # Shell script launcher with crash handler
├── lspd                        # Binary/script launching app_process daemon
├── lspctl                      # CLI tool executable
├── bin/
│   ├── dex2oat32               # Intercepting dex2oat wrapper (32-bit)
│   └── dex2oat64               # Intercepting dex2oat wrapper (64-bit)
├── lib/
│   ├── libpreload32.so         # Preload library for linker hooking
│   └── libpreload64.so         # Preload library for linker hooking
└── zygisk/
    ├── arm64-v8a.so            # 64-bit ARM Zygisk injection library
    └── armeabi-v7a.so          # 32-bit ARM Zygisk injection library
```

#### Companion Injection Targets (`zn_modules.txt`)
LSPosed injects beyond `zygote` to hook Android runtime compilation and custom spawners:
```text
path=/system_ext/bin/hyos_spawner companion zygisk/arm64-v8a.so
name=artd companion zygisk/arm64-v8a.so
```
- `hyos_spawner`: Xiaomi HyperOS application spawner.
- `artd`: Android 14+ ART Compilation Daemon (controls AOT compilation and profile-guided optimization).

---

### 3.2 State & Configuration Directory: `/data/adb/lspd/`

```
/data/adb/lspd/
├── monitor                     # Contains current abstract socket name (e.g. lspbridge-<UUID>)
├── lock                        # Inter-process mutex file lock
├── license.pb                  # Verification state
├── config/
│   ├── modules_config.db       # Primary SQLite database
│   ├── modules_config.db-wal   # Write-Ahead Log
│   ├── dex2oat_service.db      # Managed ODEX tracking database
│   └── dex2oat_service.db-wal
└── log/
    ├── verbose_<timestamp>.log # Full verbose logging
    ├── modules_<timestamp>.log # Module-specific log messages
    ├── props.txt               # Snapshot of system properties (getprop)
    └── kmsg.log                # Kernel log snapshot (dmesg)
```

---

## 4. Daemon Database Schemas (`modules_config.db`)

### 4.1 Schema Definitions
The daemon stores all module states, scopes, and remote preferences in `/data/adb/lspd/config/modules_config.db`:

```sql
-- Installed modules registered with LSPosed
CREATE TABLE modules (
    module_pkg_name TEXT PRIMARY KEY NOT NULL,
    apk_path TEXT
);

-- Per-user activation and restriction states
CREATE TABLE modules_state (
    module_pkg_name TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    enabled BOOLEAN DEFAULT 0,
    scope_request_blocked BOOLEAN DEFAULT 0,
    CHECK (enabled IN (0, 1)),
    CHECK (scope_request_blocked IN (0, 1)),
    PRIMARY KEY (module_pkg_name, user_id),
    CONSTRAINT modules_state_constraint
        FOREIGN KEY (module_pkg_name)
        REFERENCES modules (module_pkg_name)
        ON DELETE CASCADE
);

-- Target application scope per module and user
CREATE TABLE scope (
    module_pkg_name TEXT NOT NULL,
    app_pkg_name TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    PRIMARY KEY (module_pkg_name, app_pkg_name, user_id),
    CONSTRAINT scope_module_constraint
        FOREIGN KEY (module_pkg_name)
        REFERENCES modules (module_pkg_name)
        ON DELETE CASCADE
);

-- Storage for Remote Preferences (getRemotePreferences)
CREATE TABLE module_configs (
    module_pkg_name TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    group_name TEXT NOT NULL,
    key_name TEXT NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (module_pkg_name, user_id, group_name, key_name),
    CONSTRAINT config_module_constraint
        FOREIGN KEY (module_pkg_name)
        REFERENCES modules (module_pkg_name)
        ON DELETE CASCADE
);

-- Application-level configurations
CREATE TABLE app_configs (
    app_pkg_name TEXT NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (app_pkg_name)
);

-- Global daemon settings
CREATE TABLE lspd_configs (
    key_name TEXT NOT NULL PRIMARY KEY,
    data BLOB NOT NULL
);
```

### 4.2 Known `lspd_configs` Keys
| Configuration Key | Data Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `enable_developer_mode` | boolean | `false` | Enables `lspctl` mutation commands from ADB shell |
| `enable_status_notification` | boolean | `true` | Shows persistent "LSPosed activated" system notification |
| `enable_verbose_log` | boolean | `false` | Enables full verbose tracing in `/data/adb/lspd/log/` |
| `enable_hook_debug` | boolean | `false` | Logs individual method hook entries and exits |
| `enable_log_to_daemon` | boolean | `true` | Redirects module log calls to daemon log files |
| `misc_path` | string | `/data/misc/...` | Path to randomized preference / dex storage |

---

## 5. Safe Mode & Emergency Recovery Subsystem

To protect against boot loops caused by faulty or malicious modules, LSPosed implements a multi-tiered safe mode architecture:

1. **Physical Key Trigger**:
   - Holding or pressing physical buttons (Volume Down / Power) during system boot aborts module injection and enters Safe Mode.
2. **System Server Crash Watchdog**:
   - If `system_server` crashes repeatedly within a short interval, `lspd` increments a crash counter and automatically activates `safeMode = true`.
3. **Dialpad Secret Code**:
   - Dialing `*#*#5776733#*#*` (`5776733` spells `LSPOSED`) triggers `android.provider.Telephony.SECRET_CODE` / `android.telephony.action.SECRET_CODE`.
   - LSPosed registers a broadcast receiver for this action to launch the Manager recovery activity even if hidden or uninstalled.
4. **Emergency Scope Clear**:
   - If a specific app bootloops, administrators can clear its scope from ADB:
     ```bash
     lspctl scope remove --user=0 <MODULE_PACKAGE> <CRASHING_APP>
     ```
   - Or disable all modules immediately:
     ```bash
     lspctl module disable-all --all-users
     ```
