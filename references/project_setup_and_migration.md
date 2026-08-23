# Project Setup, Packaging & Migration Guide

This document covers Gradle build setup, packaging rules, metadata file definitions, Lint rules, and a step-by-step migration guide from legacy XposedBridge to modern LibXposed.

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

### 2.1 `module.prop`
Java properties format defining module capabilities:

```properties
# Minimal Xposed API version required (101 or 102)
minApiVersion=101

# Target Xposed API version
targetApiVersion=102

# If true, users cannot add arbitrary apps outside the declared scope in LSPosed Manager
staticScope=false

# If true, framework attempts automatic hot reload on APK update (API 102+)
autoHotReload=true
```

### 2.2 `java_init.list`
Fully qualified class names extending `XposedModule` (one per line):
```text
com.example.mymodule.ModuleMain
com.example.mymodule.SystemHookEntry
```

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

In modern LibXposed, module identification is integrated with native Android attributes instead of `<meta-data>` tags:

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
        <provider
            android:name="io.github.libxposed.service.XposedProvider"
            android:authorities="${applicationId}.xposed_provider"
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

## 6. Migration Guide: Legacy XposedBridge to Modern LibXposed

### Step 1: Migrate Configuration Files
- Move `assets/xposed_init` ➡️ `src/main/resources/META-INF/xposed/java_init.list`.
- Move `assets/native_init` ➡️ `src/main/resources/META-INF/xposed/native_init.list`.
- Create `src/main/resources/META-INF/xposed/module.prop`.
- Move `xposedscope` meta-data / string arrays ➡️ `src/main/resources/META-INF/xposed/scope.list`.

### Step 2: Migrate Entry Class
- Change `implements IXposedHookLoadPackage` ➡️ `extends XposedModule`.
- Remove manual `XposedBridge.log(...)` ➡️ Use `log(priority, tag, msg)`.
- Replace `handleLoadPackage(XC_LoadPackage.LoadPackageParam)` ➡️ `onPackageReady(PackageReadyParam)`.

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
- Replace `XSharedPreferences` ➡️ `getRemotePreferences("name")`.
- Module app writes via `XposedService.getRemotePreferences("name")`.
