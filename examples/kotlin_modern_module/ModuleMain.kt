package com.example.module

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Invoker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.FileNotFoundException
import java.io.FileReader

/**
 * Modern LibXposed module entry in Kotlin.
 * Declared in: src/main/resources/META-INF/xposed/java_init.list
 */
class ModuleMain : XposedModule() {

    companion object {
        const val TAG = "ModernXposedModule"
    }

    // =========================================================================
    // 1. Lifecycle: Module Loaded into Process
    // =========================================================================
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Module loaded into process: ${param.processName}")
        log(Log.INFO, TAG, "Framework: $frameworkName v$frameworkVersion (Code: $frameworkVersionCode, API: $apiVersion)")

        val props = frameworkProperties
        val hasProp: (Long) -> Boolean = { (props and it) != 0L }
        log(Log.INFO, TAG, "Capabilities -> System: ${hasProp(PROP_CAP_SYSTEM)}, Remote: ${hasProp(PROP_CAP_REMOTE)}, API Protection: ${hasProp(PROP_RT_API_PROTECTION)}")
    }

    // =========================================================================
    // 2. Lifecycle: Android Q+ Default ClassLoader Available
    // =========================================================================
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "Package loaded: ${param.packageName}, firstPackage: ${param.isFirstPackage}")
    }

    // =========================================================================
    // 3. Lifecycle: System Server (Hooking OS Framework Services)
    // =========================================================================
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "System server starting. System ClassLoader: ${param.classLoader}")
    }

    // =========================================================================
    // 4. Primary Hook Entry Point: Application ClassLoader Ready
    // =========================================================================
    override fun onPackageReady(param: PackageReadyParam) {
        log(Log.INFO, TAG, "Package ready to run: ${param.packageName}")
        if (!param.isFirstPackage) return

        val appClassLoader = param.classLoader

        // ---------------------------------------------------------------------
        // Remote Preferences (Read-Only in Target) & Live Change Listener
        // ---------------------------------------------------------------------
        val prefs = getRemotePreferences("main_settings")
        val isFeatureActive = prefs.getBoolean("enable_feature", true)
        log(Log.INFO, TAG, "Initial config 'enable_feature': $isFeatureActive")

        prefs.registerOnSharedPreferenceChangeListener { sharedPreferences, key ->
            val updatedVal = sharedPreferences.getBoolean(key, false)
            log(Log.INFO, TAG, "Real-time config update -> $key: $updatedVal")
        }

        // ---------------------------------------------------------------------
        // Remote Files (Binary Blobs & Large Data)
        // ---------------------------------------------------------------------
        try {
            val content = openRemoteFile("custom_rules.json").use { pfd ->
                FileReader(pfd.fileDescriptor).readText()
            }
            log(Log.INFO, TAG, "Loaded remote rules:\n$content")
        } catch (_: FileNotFoundException) {
            log(Log.INFO, TAG, "Remote file custom_rules.json not found")
        }

        // ---------------------------------------------------------------------
        // Interceptor Chain Hooking
        // ---------------------------------------------------------------------
        try {
            val targetClass = Class.forName("com.target.app.AuthManager", true, appClassLoader)
            val authMethod = targetClass.getDeclaredMethod("authenticate", String::class.java, String::class.java)
            val authConstructor = targetClass.getDeclaredConstructor()

            // Hook Method with Interceptor Chain
            hook(authMethod)
                .setId("auth_method_hook")
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    log(Log.INFO, TAG, "Auth called with user: ${chain.getArg(0)}")

                    // 1. Proceed with original arguments
                    val originalResult = chain.proceed() as Boolean

                    // 2. Or proceed with modified arguments:
                    // val newArgs = arrayOf("spoofed_user", chain.getArg(1))
                    // val result = chain.proceed(newArgs) as Boolean

                    // 3. Or invoke raw method directly (bypassing all hooks) via Invoker:
                    val rawResult = getInvoker(authMethod)
                        .setType(Invoker.Type.ORIGIN)
                        .invoke(chain.thisObject, "admin", "token")

                    // Return spoofed/modified boolean
                    true
                }

            // Hook Constructor
            hook(authConstructor)
                .setPriority(PRIORITY_HIGHEST)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    log(Log.INFO, TAG, "Instantiating AuthManager...")
                    chain.proceed()
                    // Return null/Unit for constructors
                }

        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to initialize hooks in onPackageReady", t)
        }
    }

    // =========================================================================
    // 5. Hot Reloading: Old Generation Retirement (Runs in Old Bytecode)
    // =========================================================================
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "Preparing old module generation for hot reload...")

        // Save classloader-neutral state for the incoming new generation
        val state = Bundle().apply {
            putLong("hot_reload_time", System.currentTimeMillis())
        }
        param.setSavedInstanceState(state)

        // Return true to allow hot reload
        return true
    }

    // =========================================================================
    // 6. Hot Reloading: New Generation Activation (Runs in New Bytecode)
    // =========================================================================
    override fun onHotReloaded(param: HotReloadedParam) {
        log(Log.INFO, TAG, "New module generation activated in ${param.processName}!")

        val state = param.savedInstanceState as? Bundle
        val lastReloadTime = state?.getLong("hot_reload_time") ?: 0L
        log(Log.INFO, TAG, "State restored. Previous reload timestamp: $lastReloadTime")

        // Atomically replace old hooks
        for (handle in param.oldHookHandles) {
            if ("auth_method_hook" == handle.id) {
                handle.replaceHook { chain ->
                    log(Log.INFO, TAG, "Updated hot-reloaded auth interceptor executing!")
                    chain.proceed()
                }
            } else {
                handle.unhook()
            }
        }
    }
}
