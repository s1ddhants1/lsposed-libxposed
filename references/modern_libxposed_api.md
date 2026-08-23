# Modern LibXposed API Specification & Guide

This document provides a comprehensive technical reference for the modern **LibXposed** framework (API 101 / 102+), which replaces the legacy `de.robv.android.xposed` (XposedBridge) API with a high-performance, modular, interceptor-chain-based hooking architecture.

---

## 1. Core Architecture Overview

### Key Differences from Legacy Xposed
| Feature | Legacy Xposed / EdXposed | Modern LibXposed (LSPosed) |
| :--- | :--- | :--- |
| **API Versioning** | API 54..93 | API 101, 102+ |
| **Hooking Model** | `beforeHookedMethod` / `afterHookedMethod` callback callbacks | **OkHttp-style Interceptor Chain** (`Hooker` + `Chain`) |
| **Module Entry Config** | `assets/xposed_init` + `AndroidManifest.xml` meta-data | `META-INF/xposed/java_init.list`, `module.prop`, `scope.list` |
| **Zygote Hooking** | Injected into Zygote / all processes | **Process-isolated**, loaded only inside scoped target apps |
| **Caller Invocation** | `XposedBridge.invokeOriginalMethod(...)` (slow reflection) | **Invoker Subsystem** (`Invoker`, `CtorInvoker`, `invokeSpecial`, `newInstanceSpecial`) |
| **Deoptimization** | None (inlined methods silently fail to hook) | `deoptimize(Executable)` runtime ART deoptimization |
| **Inter-Process Data** | `XSharedPreferences` (world-readable file hack) | **Remote Preferences & Remote Files** (LSPosed daemon IPC) |
| **Hot Reload** | Requires killing/restarting target apps | **Live Hot Reload** without restarting target processes (API 102) |
| **Resource Hooks** | `IXposedHookInitPackageResources` (deprecated/removed) | Removed (unstable across ART versions) |

---

## 2. API Level Matrix & Capabilities

### API Constants (`XposedInterface`)
```java
public interface XposedInterface {
    int API_101 = 101; // First modern API version. Process-level scoping, interceptor chains.
    int API_102 = 102; // Hot reload, hook IDs, atomic hook replacement, detach API.
    int LIB_API = API_102; // Library compile-time constant.

    // Framework Property Flags (queried via getFrameworkProperties())
    long PROP_CAP_SYSTEM = 1L;          // Capability to hook system_server & system processes
    long PROP_CAP_REMOTE = 1L << 1;     // Provides Remote Preferences & Remote Files
    long PROP_RT_API_PROTECTION = 1L << 2; // Disallows accessing Xposed API via reflection or dynamic classloaders

    // Hook Priority Constants
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = Integer.MIN_VALUE;
}
```

---

## 3. The Interceptor Chain Hooking Model

Hooks in LibXposed operate as an onion/interceptor pipeline similar to OkHttp network interceptors.

```
Caller ---> [ Hooker A (Priority: 100) ]
                 │ chain.proceed()
                 ▼
            [ Hooker B (Priority: 50) ]
                 │ chain.proceed()
                 ▼
            [ Original Executable / Method ]
                 │ returns value
                 ▲
            [ Hooker B post-processing ]
                 ▲
            [ Hooker A post-processing ] ---> Caller receives final result
```

### The `Hooker` Interface
```java
@FunctionalInterface
public interface Hooker {
    /**
     * Intercepts a method or constructor call.
     * @param chain The interceptor chain for this execution
     * @return The result to return to the caller or next interceptor
     * @throws Throwable Any exception to propagate to the caller or higher interceptors
     */
    Object intercept(@NonNull Chain chain) throws Throwable;
}
```

### The `Chain` Interface
```java
public interface Chain {
    @NonNull Executable getExecutable();  // The Method or Constructor being intercepted
    @Nullable Object getThisObject();    // The 'this' instance, or null for static methods
    @NonNull List<Object> getArgs();      // Immutable list of arguments
    Object getArg(int index);            // Convenience argument getter with bounds check

    // 1. Proceed with unmodified context
    Object proceed() throws Throwable;

    // 2. Proceed with modified arguments
    Object proceed(@NonNull Object[] args) throws Throwable;

    // 3. Proceed with different 'this' instance (non-static only)
    Object proceedWith(@NonNull Object thisObject) throws Throwable;

    // 4. Proceed with different 'this' instance and modified arguments
    Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable;
}
```

> [!IMPORTANT]
> **Chain Invariants**:
> 1. `Chain` instances are **not thread-safe** and **must not be cached** outside the `intercept()` invocation.
> 2. For `void` methods and constructors, return values from `intercept` are ignored, but in Java you must return `null` (or in Kotlin `return@intercept Unit` / `null`).
> 3. If an interceptor does not call `chain.proceed()`, the original method is skipped (short-circuited), returning the value supplied by the interceptor.

---

## 4. `HookBuilder` & Hook Configuration

Hooks are registered via `hook(Executable)` or `hookClassInitializer(Class<?>)`, returning a fluent `HookBuilder`:

```java
public interface HookBuilder {
    // Set execution priority (higher runs earlier in chain)
    HookBuilder setPriority(int priority);

    // Set exception handling behavior
    HookBuilder setExceptionMode(@NonNull ExceptionMode mode);

    // Assign unique ID for atomic replacement (API 102+)
    @SinceApi(API_102)
    HookBuilder setId(@Nullable String id);

    // Build and activate the hook
    @NonNull
    HookHandle intercept(@NonNull Hooker hooker);
}
```

### Exception Handling Modes (`ExceptionMode`)
1. `DEFAULT`: Follows `module.prop` exception mode setting (defaults to `PROTECTIVE`).
2. `PROTECTIVE`: Catches and logs any unhandled exceptions thrown by the **hooker** code.
   - If exception occurs *before* `chain.proceed()`, the framework bypasses the hooker and executes the rest of the chain seamlessly.
   - If exception occurs *after* `chain.proceed()`, the result of `proceed()` is preserved and returned.
   - Exceptions thrown by target application code inside `proceed()` are **always propagated**.
3. `PASSTHROUGH`: Directly propagates all exceptions thrown by the hooker to the caller. Recommended during active development and debugging.

### Hook Identifiers & `HookHandle` (API 102+)
```java
public interface HookHandle {
    @NonNull Executable getExecutable();
    void unhook(); // Idempotent: safe to call multiple times

    @SinceApi(API_102)
    @Nullable String getId();

    @SinceApi(API_102)
    @NonNull HookHandle replaceHook(@NonNull Hooker hooker);
}
```

- **Hook ID**: When an ID is set (e.g., `.setId("unique_hook_id")`), registering another hook with the same ID on the same executable in the same module will **atomically replace** the previous hook.
- **`replaceHook(Hooker)`**: Atomically replaces an installed hook with a new hook implementation while preserving its priority, exception mode, and executable binding.

---

## 5. Invoker Subsystem (`getInvoker`)

LibXposed provides high-performance invokers that bypass standard Java reflection access checks and security managers.

### Method Invoker (`Invoker<?, Method>`)
```java
Method targetMethod = targetClass.getDeclaredMethod("calculate", int.class);
Invoker<?, Method> invoker = getInvoker(targetMethod);

// 1. Invoke raw original method (bypassing ALL hooks)
invoker.setType(Invoker.Type.ORIGIN);
Object rawResult = invoker.invoke(instance, 42);

// 2. Invoke through partial hook chain (hooks with priority <= 50)
invoker.setType(new Invoker.Type.Chain(50));
Object partialResult = invoker.invoke(instance, 42);

// 3. Non-virtual special invocation (bypasses subclass overrides, like super.xxx())
Object specialResult = invoker.invokeSpecial(subclassInstance, 42);
```

### Constructor Invoker (`CtorInvoker<T>`)
```java
Constructor<MyService> ctor = MyService.class.getDeclaredConstructor(Context.class);
CtorInvoker<MyService> ctorInvoker = getInvoker(ctor);

// Instantiate bypassing all hooks:
MyService svc1 = ctorInvoker.setType(Invoker.Type.ORIGIN).newInstance(context);

// Special instantiation: create subclass instance initialized with parent constructor:
SubService subSvc = ctorInvoker.newInstanceSpecial(SubService.class, context);
```

---

## 6. Method Deoptimization (`deoptimize`)

In modern Android ART, the JIT/AOT compiler aggressively inlines short methods into their callers. When a callee method is inlined, hooking the callee will have **no effect** because callers execute the inlined bytecode directly.

```java
// Force ART runtime to deoptimize a method, preventing it from inlining callees
boolean success = deoptimize(callerMethod);
```

> [!TIP]
> When hooking small utility methods (e.g., `isPremium()`, `getFlag()`), if your hook is never invoked, use `deoptimize(callingMethod)` on the calling methods, or inspect the app with [DexKit](https://github.com/LuckyPray/DexKit) to find all caller executables.

---

## 7. Static Initializer Hooking (`hookClassInitializer`)

LibXposed allows intercepting the `<clinit>` static block of a class before class initialization completes:

```java
hookClassInitializer(targetClass).intercept(chain -> {
    log(Log.INFO, TAG, "Class static initializer <clinit> executing: " + targetClass.getName());
    
    // In <clinit> hooks:
    // - chain.getThisObject() is ALWAYS null
    // - chain.getArgs() is an empty list
    // - chain.proceed() executes static initializers and returns null
    chain.proceed();
    
    return null;
});
```

---

## 8. Detach API (`XposedInterfaceWrapper.detach()`)

API 102 introduces `detach()` to unregister the current module entry from receiving subsequent lifecycle callbacks in the target process.

```java
public class MyConditionalEntry extends XposedModule {
    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.target.app")) {
            // Not running in target app - stop all future callbacks for this entry
            detach();
            return;
        }
        
        // Install hooks...
    }
}
```

Use Cases:
- Multi-package modules where specific entry classes only care about one package.
- Completing one-time initialization, allowing the module's callback overhead to drop to zero.
- Releasing memory when paired with hook cleanup so the module classloader can be garbage-collected.
