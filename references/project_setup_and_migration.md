# Project Setup, Packaging & Migration Guide

This document covers Gradle build setup, packaging rules, metadata file definitions, Lint rules, and a step-by-step migration guide from legacy XposedBridge to modern LibXposed.

> [!NOTE]
> **Source**: Metadata format sourced from the [LSPosed Wiki](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API), dependency coordinates from [libxposed/api](https://github.com/libxposed/api), [libxposed/service](https://github.com/libxposed/service), and [libxposed/helper](https://github.com/libxposed/helper) repositories. Module configuration verified against live LSPosed 2.2.0-it via `lspctl module list --json`.

---

## 1. Project Configuration & Dependencies

### 1.1 Gradle Version Catalog (`gradle/libs.versions.toml`)
```toml
[versions]
agp = "8.8.0"
kotlin = "2.1.0"
xposed-api = "102.0.0"
xposed-service = "102.0.0"
xposed-helper = "100.0.0"

[libraries]
libxposed-api = { group = "io.github.libxposed", name = "api", version.ref = "xposed-api" }
libxposed-service = { group = "io.github.libxposed", name = "service", version.ref = "xposed-service" }
libxposed-helper = { group = "io.github.libxposed", name = "helper", version.ref = "xposed-helper" }
libxposed-helper-ktx = { group = "io.github.libxposed", name = "helper-ktx", version.ref = "xposed-helper" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

### 1.2 Module `build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.mymodule"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mymodule"
        minSdk = 26       // Android 8.0+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    // CRITICAL: Packaging DSL to ensure META-INF/xposed files are packaged into the APK
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    // API is compileOnly: Provided at runtime by LSPosed framework inside target processes
    compileOnly(libs.libxposed.api)

    // Service is implementation: Packaged into your module APK for UI and IPC
    implementation(libs.libxposed.service)

    // Optional: Helpers and reflection DSL
    // implementation(libs.libxposed.helper.ktx)
}
```

---

## 2. Modern Metadata Files (`META-INF/xposed/`)

Place all configuration files inside `src/main/resources/META-INF/xposed/`:

```
app/src/main/resources/META-INF/xposed/
├── module.prop         # Module configuration properties
├── java_init.list      # Java/Kotlin entry class names
├── native_init.list    # Native .so library names (optional)
└── scope.list          # Default target package names
```

> [!IMPORTANT]
> Per the wiki: "Create files in `src/main/resources/META-INF`, and Gradle will automatically package files into your APK." Modern API does not use `<meta-data>` tags in AndroidManifest.xml. Module name uses `android:label`, module description uses `android:description`.

### 2.1 `module.prop`
Java properties format defining module capabilities:

```properties
# Minimal Xposed API version required (101 or 102)
minApiVersion=101

# Target Xposed API version
targetApiVersion=102

# If true, users cannot add arbitrary apps outside the declared scope in LSPosed Manager
# (Optional, default: false)
staticScope=false

# If true, framework attempts automatic hot reload on APK update (API 102+)
# (Optional, default: false)
autoHotReload=true

# Global exception mode: "protective" (default) catches/logs hook exceptions;
# "passthrough" rethrows exceptions to the caller
# (Optional, default: protective)
exceptionMode=protective
```

| Property | Format | Optional | Meaning |
| :--- | :--- | :--- | :--- |
| `minApiVersion` | int | **No** | Minimal Xposed API version required by the module |
| `targetApiVersion` | int | **No** | Target Xposed API version the module is designed for |
| `staticScope` | boolean | Yes | If true, users cannot add apps outside `scope.list` |
| `autoHotReload` | boolean | Yes | If true, auto hot reload on APK update (API 102+) |
| `exceptionMode` | string | Yes | Default exception handling: `protective` or `passthrough` |

> [!NOTE]
> **Verified on device**: The live `lspctl module list --json` output confirms these fields are parsed and reported per-module. For example, `io.github.s1ddhants1.swiftbackupprem` reports `"minApiVersion":101,"targetApiVersion":102,"staticScope":true,"autoHotReload":true`. Note also that `#` at the start of a line denotes a comment in all `*.list` metadata files.

### 2.2 `java_init.list`
Fully qualified class names extending `XposedModule` (one per line):
```text
com.example.mymodule.ModuleMain
com.example.mymodule.SystemHookEntry
```

> [!IMPORTANT]
> Per the wiki: "Java entry should now implement `io.github.libxposed.api.XposedModule`. Note that `XposedModule` no longer receives `XposedInterface` and `ModuleLoadedParam` in its constructor; the framework calls `attachFramework(XposedInterface)` automatically."

### 2.3 `native_init.list` (Optional for C++ native hooks)
Names of native `.so` libraries containing `native_init`:
```text
libnative_hook.so
```

### 2.4 `scope.list`
Default target application packages to hook (one per line):
```text
com.android.settings
com.target.application
```

---

## 3. AndroidManifest.xml (Modern Standard)

In modern LibXposed, module identification uses native Android attributes instead of `<meta-data>` tags:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:label="@string/app_name"
        android:description="@string/module_description"
        android:icon="@mipmap/ic_launcher"
        android:theme="@android:style/Theme.DeviceDefault"
        tools:ignore="MissingApplicationIcon">

        <!-- Module Configuration UI Activity -->
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- XposedProvider for receiving LSPosed framework service binder -->
        <!-- Note: When using libxposed:service, this is merged automatically by Gradle -->
        <provider
            android:name="io.github.libxposed.service.XposedProvider"
            android:authorities="${applicationId}.XposedService"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </application>
</manifest>
```

---

## 4. ProGuard / R8 Rules (`proguard-rules.pro`)

```proguard
# 1. Keep LibXposed Module Entry Classes
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void on*(...);
}

# 2. Keep XposedProvider
-keep class io.github.libxposed.service.XposedProvider { *; }

# 3. Preserve native hooks and JNI symbols
-keepclasseswithmembernames class * {
    native <methods>;
}

# 4. Don't warn on LibXposed API references
-dontwarn io.github.libxposed.api.**
```

---

## 5. Android Lint Integration (`libxposed:lint`)

LibXposed includes custom lint detectors:
1. **`XposedNewApi` (`SinceApiDetector`)**: Verifies that any method or property annotated with `@SinceApi` (e.g. API 102 features like `detach()` or `replaceHook()`) is guarded with `if (getApiVersion() >= API_102)` when `minApiVersion` is set to `101`.
2. **`XposedInternalApi` (`InternalApiDetector`)**: Warns if module code attempts to invoke `@InternalApi` framework methods (e.g. `attachFramework()`).

---

## 6. Legacy vs Modern Module Comparison

### Content Sharing APIs (from LSPosed Wiki)

| Name | API | Supported | Storage Location | Change Listener | Large Content |
| :--- | :--- | :--- | :--- | :--- | :--- |
| New XSharedPreferences | Legacy (ext) | ❌ Since v2.1.0 | `/data/misc/<random>/prefs/<module>` | ❌ | ❌ |
| XSharedPreferences | Legacy | ✅ Since v2.0.0 | Module app internal storage | ❌ | ❌ |
| Remote Preferences | Modern | ✅ Since v1.9.0 | LSPosed database | ✅ | ❌ |
| Remote Files | Modern | ✅ Since v1.9.0 | `/data/adb/lspd/modules/<user>/<module>` | ❌ | ✅ |

### Legacy Module Capabilities
Per the wiki, legacy modules (`targetApiVersion=93`) are still supported but with caveats:
- Legacy modules can still use `de.robv.android.xposed` APIs
- **Modules targeting API 102+ cannot call legacy APIs** (enforced at runtime)
- **Module apps are no longer hooked by themselves** with the modern API

---

## 7. Migration Guide: Legacy XposedBridge to Modern LibXposed

### Step 1: Migrate Configuration Files
- Move `assets/xposed_init` ➡️ `src/main/resources/META-INF/xposed/java_init.list`.
- Move `assets/native_init` ➡️ `src/main/resources/META-INF/xposed/native_init.list`.
- Create `src/main/resources/META-INF/xposed/module.prop` with `minApiVersion` and `targetApiVersion`.
- Move `xposedscope` meta-data / string arrays ➡️ `src/main/resources/META-INF/xposed/scope.list`.
- Remove all `<meta-data>` tags from AndroidManifest.xml related to Xposed.
- Use `android:label` for module name and `android:description` for module description.

### Step 2: Migrate Entry Class
- Change `implements IXposedHookLoadPackage` ➡️ `extends XposedModule`.
- Remove manual `XposedBridge.log(...)` ➡️ Use `log(priority, tag, msg)`.
- Replace `handleLoadPackage(XC_LoadPackage.LoadPackageParam)` ➡️ `onPackageReady(PackageReadyParam)`.
- Do NOT perform initialization in the constructor; wait for `onModuleLoaded()`.
- The framework calls `attachFramework()` automatically — do not call it manually.

### Step 3: Migrate Hooking Invocations
```java
// ❌ LEGACY (XposedBridge):
XposedHelpers.findAndHookMethod(
    "com.target.App",
    lpparam.classLoader,
    "login",
    String.class,
    new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.args[0] = "spoofed_user";
        }
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            param.setResult(true);
        }
    }
);

// ✅ MODERN (LibXposed):
Class<?> targetClass = Class.forName("com.target.App", true, param.getClassLoader());
Method loginMethod = targetClass.getDeclaredMethod("login", String.class);

hook(loginMethod).intercept(chain -> {
    // Modify argument for down-chain calls
    Object[] newArgs = new Object[]{ "spoofed_user" };

    // Call original / next hooker
    Object originalResult = chain.proceed(newArgs);

    // Override return value
    return true;
});
```

### Step 4: Migrate Data Sharing
- Replace `XSharedPreferences` ➡️ `getRemotePreferences("name")` in hooked targets.
- Module app writes via `XposedService.getRemotePreferences("name")`.
- Replace file-based data sharing ➡️ Remote Files API.

### Step 5: Migrate Scope Declaration
- Remove `<meta-data>` scope tags from AndroidManifest.xml.
- Create `scope.list` with one package name per line.
- For dynamic scope management, use `XposedService.requestScope()` API.

> [!NOTE]
> Per the wiki: "We no longer provide interfaces like `XposedHelpers` in the framework anymore. But we will offer official libraries for a more friendly development kit." See [libxposed/helper](https://github.com/libxposed/helper) for the reflection and matcher DSL library.

---

## 8. Targeting & Packaging Modules for LSPatch (Rootless)

Modules authored using modern LibXposed (API 101/102+) run on rootless **LSPatch** with zero modifications, provided they adhere to Android's application sandbox constraints.

### 8.1 Module Design Considerations for Rootless Environments

1. **Strict Target Process Scoping**:
   - LSPatch runs inside the target app's sandbox. It cannot hook `system_server`, `com.android.systemui`, or third-party apps simultaneously.
   - Always guard package entry in `onPackageReady`:
     ```kotlin
     override fun onPackageReady(param: PackageReadyParam) {
         if (!param.isFirstPackage) return
         if (param.packageName != "com.target.application") return
         // Hook application logic
     }
     ```

2. **No Root Filesystem Assumptions**:
   - Never write to or read from `/data/adb/`, `/data/misc/`, or root-owned directories.
   - Always use `getRemotePreferences()` or the Remote Files API (`openRemoteFile()`) which transparently serialize data across the LSPatch IPC bridge into SQLite (`lspatch-xposed-remote.db`).

3. **Isolated Process Handling**:
   - LSPatch skips isolated sub-processes (UIDs >= 90000, such as Chromium sandbox renderers) to prevent runtime crashes. Do not rely on hooks firing in isolated workers.

### 8.2 Building & Testing with `lspatch.jar` CLI

To test your compiled module against a target APK without requiring a rooted device:

```bash
# 1. Build your module release APK
./gradlew :app:assembleRelease

# 2. Patch target APK in Manager Mode (for live module development)
java -jar lspatch.jar --manager -l 2 -f com.target.app.apk

# 3. Or patch target APK in Standalone Integrated Mode (with embedded module)
java -jar lspatch.jar -m app/build/outputs/apk/release/app-release.apk -l 2 -f com.target.app.apk

# 4. Install patched APK to device/emulator via ADB
adb install -r com.target.app-*-lspatched.apk
```

For complete reference on LSPatch flags, signature bypass levels, and runtime architecture, see [LSPatch Rootless Patching Guide](./lspatch_rootless_patching.md).

