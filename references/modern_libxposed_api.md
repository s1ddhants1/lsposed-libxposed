# Modern LibXposed API Specification & Guide

This document provides a comprehensive technical reference for the modern **LibXposed** framework (API 101 / 102+), which replaces the legacy `de.robv.android.xposed` (XposedBridge) API with a high-performance, modular, interceptor-chain-based hooking architecture.

> [!NOTE]
> **Source**: All API signatures and javadoc descriptions in this document are sourced directly from the [libxposed/api](https://github.com/libxposed/api) repository (`XposedInterface.java`, `XposedModule.java`, `XposedInterfaceWrapper.java`, `XposedModuleInterface.java`) and verified against a live LSPosed 2.2.0-it installation (API 102, version code 7866).

---

## 1. Core Architecture Overview

### Key Differences from Legacy Xposed
| Feature | Legacy Xposed / EdXposed | Modern LibXposed (LSPosed) |
| :--- | :--- | :--- |
| **API Versioning** | API 54..93 | API 101, 102+ |
| **Hooking Model** | `beforeHookedMethod` / `afterHookedMethod` callbacks | **OkHttp-style Interceptor Chain** (`Hooker` + `Chain`) |
| **Module Entry Config** | `assets/xposed_init` + `AndroidManifest.xml` meta-data | `META-INF/xposed/java_init.list`, `module.prop`, `scope.list` |
| **Zygote Hooking** | Injected into Zygote / all processes | **Process-isolated**, loaded only inside scoped target apps |
| **Caller Invocation** | `XposedBridge.invokeOriginalMethod(...)` (slow reflection) | **Invoker Subsystem** (`Invoker<T,U>`, `CtorInvoker<T>`, `invokeSpecial`, `newInstanceSpecial`) |
| **Deoptimization** | None (inlined methods silently fail to hook) | `deoptimize(Executable)` runtime ART deoptimization |
| **Inter-Process Data** | `XSharedPreferences` (world-readable file hack) | **Remote Preferences & Remote Files** (LSPosed daemon IPC) |
| **Hot Reload** | Requires killing/restarting target apps | **Live Hot Reload** without restarting target processes (API 102) |
| **Resource Hooks** | `IXposedHookInitPackageResources` (deprecated/removed) | Removed (unstable across ART versions) |
| **Legacy API Access** | Always available | Modules targeting API 102+ **cannot** call legacy `de.robv.android.xposed` APIs |
| **Module Self-Hooking** | Module apps are hooked by themselves | **Module apps are no longer hooked by themselves** |

---

## 2. API Level Matrix & Capabilities

### API Constants (`XposedInterface`)
```java
public interface XposedInterface {
    /**
     * API version 101.
     * - Modules cannot be injected into zygote; they are only loaded within scoped processes.
     * - This is the first modern API version.
     */
    int API_101 = 101;

    /**
     * API version 102.
     * New features:
     * - Hot reload allows modules to be updated without restarting the process.
     * - Module entries can stop receiving subsequent lifecycle callbacks (detach()).
     * - Hooks can be atomically replaced by API or same id.
     * Behavior change: Modules targeting 102+ cannot call legacy de.robv.android.xposed APIs.
     */
    int API_102 = 102;

    /**
     * The API version of this *library* (compile-time constant).
     * Modules should use getApiVersion() to check the runtime API version.
     */
    int LIB_API = API_102;

    // Framework Property Flags (queried via getFrameworkProperties())
    long PROP_CAP_SYSTEM = 1L;          // Capability to hook system_server & system processes
    long PROP_CAP_REMOTE = 1L << 1;     // Provides Remote Preferences & Remote Files
    long PROP_RT_API_PROTECTION = 1L << 2; // Disallows accessing Xposed API via reflection or dynamic classloaders
    // Note: Properties with prefix PROP_RT_ may change among launches.

    // Hook Priority Constants
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;  // Execute at the beginning of the chain
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = Integer.MIN_VALUE;   // Execute at the end of the chain
}
```

### Runtime API Queries
```java
// Available on XposedInterface (inside hooked targets) and XposedService (in module app)
int getApiVersion();            // Runtime API version (may differ from LIB_API)
String getFrameworkName();       // e.g. "LSPosed"
String getFrameworkVersion();    // e.g. "2.2.0-it"
long getFrameworkVersionCode();  // e.g. 7866
long getFrameworkProperties();   // Bitmask of PROP_* constants
ApplicationInfo getModuleApplicationInfo(); // Module's own ApplicationInfo
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
// Note: NOT annotated with @FunctionalInterface in the source, but effectively a SAM interface
public interface Hooker {
    /**
     * Intercepts a method / constructor call.
     * @param chain The interceptor chain for the call
     * @return The result to be returned from the interceptor. If the hooker does not want to
     *         change the result, it should call chain.proceed() and return its result.
     *         For void methods and constructors, the return value is ignored by the framework.
     * @throws Throwable Throw any exception from the interceptor. The exception will propagate
     *                   to the caller if not caught by any interceptor.
     */
    Object intercept(@NonNull Chain chain) throws Throwable;
}
```

### The `Chain` Interface
```java
/**
 * Interceptor chain for a method or constructor.
 * Chain objects cannot be shared among threads or reused after Hooker.intercept(Chain) ends.
 */
public interface Chain {
    @NonNull Executable getExecutable();  // The Method or Constructor being intercepted
    @Nullable Object getThisObject();     // The 'this' instance, or null for static methods
    @NonNull List<Object> getArgs();      // Immutable list of arguments
    Object getArg(int index);             // Argument getter (throws IndexOutOfBoundsException, ClassCastException)

    // 1. Proceed with unmodified context
    Object proceed() throws Throwable;

    // 2. Proceed with modified arguments (same 'this')
    Object proceed(@NonNull Object[] args) throws Throwable;

    // 3. Proceed with different 'this' instance (same args)
    // Static method interceptors should NOT call this method.
    // Does NOT change which executable the chain belongs to or select a different override.
    // When the chain reaches its end, the framework invokes the original implementation of
    // getExecutable() on thisObject — it does NOT dispatch to a subclass override.
    Object proceedWith(@NonNull Object thisObject) throws Throwable;

    // 4. Proceed with different 'this' instance AND modified arguments
    // Same semantics as proceedWith(Object) regarding dispatch and chain identity.
    Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable;
}
```

> [!IMPORTANT]
> **Chain Invariants**:
> 1. `Chain` instances are **not thread-safe** and **must not be cached** outside the `intercept()` invocation.
> 2. For `void` methods and constructors, return values from `intercept` are **ignored by the framework**, but Java syntax requires returning something (use `null`).
> 3. If an interceptor does not call `chain.proceed()`, the original method is skipped (short-circuited), returning the value supplied by the interceptor.
> 4. `proceedWith(thisObject)` does NOT perform virtual dispatch — if `getExecutable()` is `Parent.foo`, calling with a `Child` instance still invokes `Parent.foo`'s original implementation on that object. Use an `Invoker` for virtual dispatch.

---

## 4. `HookBuilder` & Hook Configuration

Hooks are registered via `hook(Executable)` or `hookClassInitializer(Class<?>)`, returning a fluent `HookBuilder`:

```java
public interface HookBuilder {
    // Set execution priority (higher runs earlier in chain)
    HookBuilder setPriority(int priority);

    // Set exception handling behavior (default is ExceptionMode.DEFAULT)
    HookBuilder setExceptionMode(@NonNull ExceptionMode mode);

    // Assign unique ID for atomic replacement (API 102+)
    // A new hook with the same id in the same module on the same executable will
    // atomically replace the old one. Hook ids are isolated between modules.
    // The hook chain is snapshot based — replacing a hook while a call is running
    // does not affect that in-flight call.
    @SinceApi(API_102)
    HookBuilder setId(@Nullable String id);

    // Build and activate the hook
    // @throws IllegalArgumentException if origin is framework internal or Constructor.newInstance
    // @throws HookFailedError if hook fails due to framework internal error
    @NonNull
    HookHandle intercept(@NonNull Hooker hooker);
}
```

### Exception Handling Modes (`ExceptionMode`)
1. `DEFAULT`: Follows the global exception mode configured in `module.prop` (defaults to `PROTECTIVE` if not specified).
2. `PROTECTIVE`: Catches and logs any unhandled exceptions thrown by the **hooker** code.
   - If exception occurs *before* `chain.proceed()`, the framework bypasses the hooker and executes the rest of the chain seamlessly.
   - If exception occurs *after* `chain.proceed()`, the result of `proceed()` is preserved and returned.
   - Exceptions thrown by target application code inside `proceed()` are **always propagated**.
3. `PASSTHROUGH`: Directly propagates all exceptions thrown by the hooker to the caller. Recommended during active development and debugging, as it helps find and fix errors in hooks.

### Hook Identifiers & `HookHandle` (API 102+)
```java
public interface HookHandle {
    @NonNull Executable getExecutable();
    void unhook(); // Idempotent: safe to call multiple times

    @SinceApi(API_102)
    @Nullable String getId();

    /**
     * Atomically replaces this hook with a new hooker and returns the new hook handle.
     * The replacement keeps the executable, priority, exception handling mode, and id of this hook.
     * After a successful replacement, THIS handle is no longer valid.
     *
     * The hook chain is snapshot based — replacing a hook while a call is running does not
     * affect that in-flight call.
     *
     * @throws IllegalStateException if this hook handle is no longer valid
     * @throws HookFailedError if replacement fails due to framework internal error
     */
    @SinceApi(API_102)
    @NonNull
    HookHandle replaceHook(@NonNull Hooker hooker);
}
```

- **Hook ID**: When an ID is set (e.g., `.setId("unique_hook_id")`), registering another hook with the same ID on the same executable in the same module will **atomically replace** the previous hook.
- **`replaceHook(Hooker)`**: Handle-based form of replacement. Can also replace a hook **without** an ID. Useful during hot reloading when new code receives old hook handles from `HotReloadedParam.getOldHookHandles()`.

---

## 5. Invoker Subsystem (`getInvoker`)

LibXposed provides high-performance invokers that bypass standard Java reflection access checks and security managers.

### Invoker Type Hierarchy
```java
// Sealed interface hierarchy for invoker types
sealed interface Type permits Type.Origin, Type.Chain {
    // Convenience constant for Origin
    Origin ORIGIN = new Origin();

    // Invokes the original executable, skipping ALL hooks
    record Origin() implements Type {}

    // Invokes starting from the middle of the hook chain,
    // skipping hooks with priority higher than maxPriority
    record Chain(int maxPriority) implements Type {
        // Invoking with full hook chain
        public static final Chain FULL = new Chain(PRIORITY_HIGHEST);
    }
}
```

### Method Invoker (`Invoker<?, Method>`)
```java
Method targetMethod = targetClass.getDeclaredMethod("calculate", int.class);
Invoker<?, Method> invoker = getInvoker(targetMethod);

// Default type is Type.Chain.FULL (invokes through full hook chain)

// 1. Invoke raw original method (bypassing ALL hooks)
invoker.setType(Invoker.Type.ORIGIN);
Object rawResult = invoker.invoke(instance, 42);

// 2. Invoke through partial hook chain (hooks with priority <= 50)
invoker.setType(new Invoker.Type.Chain(50));
Object partialResult = invoker.invoke(instance, 42);

// 3. Non-virtual special invocation (bypasses subclass overrides, like super.xxx())
// Useful when you need to call super.xxx() in a hooked constructor
Object specialResult = invoker.invokeSpecial(subclassInstance, 42);
```

### Constructor Invoker (`CtorInvoker<T>`)
```java
Constructor<MyService> ctor = MyService.class.getDeclaredConstructor(Context.class);
CtorInvoker<MyService> ctorInvoker = getInvoker(ctor);

// Instantiate bypassing all hooks:
MyService svc1 = ctorInvoker.setType(Invoker.Type.ORIGIN).newInstance(context);

// Special instantiation: create subclass instance initialized with parent constructor.
// WARNING: This leaves the subclass in an invalid state where its own constructor
// was NOT called and subclass fields are NOT initialized.
SubService subSvc = ctorInvoker.newInstanceSpecial(SubService.class, context);
```

> [!WARNING]
> **`invoke` / `invokeSpecial` throw checked exceptions**: `InvocationTargetException`, `IllegalArgumentException`, `IllegalAccessException`. `newInstance` additionally throws `InstantiationException`.

---

## 6. Method Deoptimization (`deoptimize`)

In modern Android ART, the JIT/AOT compiler aggressively inlines short methods into their callers. When a callee method is inlined, hooking the callee will have **no effect** because callers execute the inlined bytecode directly.

```java
// Force ART runtime to deoptimize a method, preventing it from inlining callees
boolean success = deoptimize(callerMethod);
```

> [!TIP]
> When hooking small utility methods (e.g., `isPremium()`, `getFlag()`), if your hook is never invoked, use `deoptimize(callingMethod)` on the calling methods. You can search all callers using [DexKit](https://github.com/LuckyPray/DexKit). Per the source javadoc: if you're not sure you've found all callers, it's better to change the hook point or deoptimize the whole app manually (by reinstalling the app without uninstalling).

---

## 7. Static Initializer Hooking (`hookClassInitializer`)

LibXposed allows intercepting the `<clinit>` static block of a class before class initialization completes:

```java
hookClassInitializer(targetClass).intercept(chain -> {
    log(Log.INFO, TAG, "Class static initializer <clinit> executing: " + targetClass.getName());

    // Per source javadoc, in <clinit> hooks:
    // - chain.getExecutable() returns a synthetic Method representing the static initializer
    // - chain.getThisObject() always returns null
    // - chain.getArgs() returns an empty list
    // - chain.proceed() returns null
    chain.proceed();

    return null;
});
```

> [!IMPORTANT]
> If the class is **already initialized** when `hookClassInitializer` is called, the hook will **never be called**.

---

## 8. Detach API (`XposedInterfaceWrapper.detach()`)

API 102 introduces `detach()` on `XposedInterfaceWrapper` (the base class of `XposedModule`) to stop all subsequent lifecycle callbacks for the **current module entry** in the current process.

```java
/**
 * After detach(), the framework removes its reference to this entry instance.
 * No further lifecycle callbacks (onPackageLoaded, onHotReloading, etc.) will be invoked.
 * Only lifecycle callbacks are affected; all XposedInterface APIs remain fully functional.
 *
 * If the module declares multiple entry classes, only the entry that calls this
 * method is affected. Other entries continue receiving callbacks normally.
 *
 * This method is idempotent — safe to call multiple times.
 */
@SinceApi(API_102)
public final void detach();
```

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

> [!WARNING]
> If the module expects its classloader to become collectible after detaching, it must also remove module-owned references: installed hooks, Java threads, callbacks held by system/app objects. If native code is still running after all Java references are cleared, the runtime unloading of native libraries may crash the process — this is a module lifecycle bug.

---

## 9. Logging API

LibXposed provides structured logging via `XposedInterface`:

```java
// Basic log message
void log(int priority, @Nullable String tag, @NonNull String msg);

// Log with exception
void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr);

// Example usage (replaces legacy XposedBridge.log()):
log(Log.INFO, "MyModule", "Hooking target class");
log(Log.ERROR, "MyModule", "Hook failed", exception);
```

Logs are written to the Xposed log and can be viewed via `lspctl` or the LSPosed Manager's log viewer.
