package com.example.module;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Modern LibXposed module entry in Java.
 * Declared in: src/main/resources/META-INF/xposed/java_init.list
 */
public class ModuleMain extends XposedModule {
    private static final String TAG = "JavaXposedModule";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded in: " + param.getProcessName());
        log(Log.INFO, TAG, "Framework: " + getFrameworkName() + " (API " + getApiVersion() + ")");
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        log(Log.INFO, TAG, "Package loaded: " + param.getPackageName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;

        ClassLoader classLoader = param.getClassLoader();

        // 1. Remote Preferences & Live Listener
        SharedPreferences prefs = getRemotePreferences("main_settings");
        boolean isEnabled = prefs.getBoolean("enable_feature", false);
        log(Log.INFO, TAG, "enable_feature is: " + isEnabled);

        prefs.registerOnSharedPreferenceChangeListener((sp, key) -> {
            log(Log.INFO, TAG, "Config updated for key: " + key + " -> " + sp.getBoolean(key, false));
        });

        // 2. Remote Files
        try (ParcelFileDescriptor pfd = openRemoteFile("config.json")) {
            try (BufferedReader reader = new BufferedReader(new FileReader(pfd.getFileDescriptor()))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                log(Log.INFO, TAG, "Read remote config.json:\n" + content);
            }
        } catch (FileNotFoundException e) {
            log(Log.WARN, TAG, "config.json not found");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error reading remote file", e);
        }

        // 3. Interceptor Chain Hooks & Invokers
        try {
            Class<?> targetClass = Class.forName("com.target.app.SecurityService", true, classLoader);
            Method isRootedDevice = targetClass.getDeclaredMethod("isRootedDevice");
            Constructor<?> ctor = targetClass.getDeclaredConstructor();

            // Hook method
            hook(isRootedDevice)
                    .setId("root_check_hook")
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "Intercepted isRootedDevice check!");
                        // Bypass original check and return false
                        return false;
                    });

            // Hook Constructor
            hook(ctor)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "SecurityService instance initializing");
                        chain.proceed();
                        return null; // For void methods and constructors, return null
                    });

            // Invoker demonstration: call raw method bypassing hooks
            Object rawResult = getInvoker(isRootedDevice)
                    .setType(XposedInterface.Invoker.Type.ORIGIN)
                    .invoke(null);

        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, TAG, "Failed to resolve target methods", e);
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        log(Log.INFO, TAG, "onHotReloading: saving state...");
        Bundle state = new Bundle();
        state.putLong("reload_timestamp", System.currentTimeMillis());
        param.setSavedInstanceState(state);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        log(Log.INFO, TAG, "onHotReloaded: restoring state in " + param.getProcessName());

        // Replace old hooks atomically
        for (HookHandle handle : param.getOldHookHandles()) {
            if ("root_check_hook".equals(handle.getId())) {
                handle.replaceHook(chain -> {
                    log(Log.INFO, TAG, "Hot-reloaded root check interceptor!");
                    return false;
                });
            } else {
                handle.unhook();
            }
        }
    }
}
