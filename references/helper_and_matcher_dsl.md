# LibXposed Helper & Matcher DSL Specification

This document details the reflection utilities and fuzzy matcher DSL provided by `io.github.libxposed:helper` and `io.github.libxposed:helper-ktx`.

---

## 1. Overview & Architecture

When developing hooks for obfuscated or heavily minified applications (e.g. ProGuard, R8, DexGuard), method and class names are randomized across app versions (e.g. `a.b.c.a(Ljava/lang/String;)Z`).

The **LibXposed Helper** library solves this by providing:
1. **High-Performance `Reflector`**: Cached reflection parser supporting Dalvik descriptor syntax (`Lcom/example/MyClass;->method(I)V`).
2. **Type-Safe Matcher DSL**: Match classes, methods, constructors, and fields based on structural characteristics, parameter types, return types, and modifiers.
3. **Dex Analysis Engine (`@DexAnalysis`)**: Deep bytecode inspection that matches methods by referred constant strings, accessed fields, invoked callees, or raw Dalvik opcodes.

---

## 2. Kotlin Matcher DSL (`helper-ktx`)

### 2.1 Basic Usage
In your module's `onPackageReady`:

```kotlin
import io.github.libxposed.helper.ktx.buildHooks
import io.github.libxposed.helper.ktx.DexAnalysis

class MyModule : XposedModule() {
    @OptIn(DexAnalysis::class)
    override fun onPackageReady(param: PackageReadyParam) {
        val dexClassLoader = param.classLoader as? dalvik.system.BaseDexClassLoader ?: return
        val apkPath = param.applicationInfo.sourceDir

        buildHooks(dexClassLoader, apkPath) {
            // Find an obfuscated class that extends Activity and contains a specific string
            classes {
                superClass = "android.app.Activity".exactClass
                isPublic = true
                
                methods {
                    // Match a method that references a sensitive string and takes 2 parameters
                    referredStrings = +"CRITICAL_AUTH_TOKEN"
                    parameterCounts = 2
                    isFinal = false
                }.first().onMatch { method ->
                    log(Log.INFO, TAG, "Found target obfuscated method: ${method.name}")
                    
                    // Hook the discovered method
                    hook(method).intercept { chain ->
                        log(Log.INFO, TAG, "Auth method called with: ${chain.args[0]}")
                        chain.proceed()
                    }
                }
            }
        }
    }
}
```

---

## 3. Matcher Specifications

### 3.1 `ClassMatcher`
```kotlin
classes {
    name = "com.target.MyClass".exactClass // or "com.target.".prefix
    superClass = "java.lang.Object".exactClass
    containsInterfaces = +"java.io.Serializable".exactClass and +"java.lang.Runnable".exactClass
    isAbstract = false
    isInterface = false
    isFinal = false
    isPublic = true
}
```

### 3.2 `MethodMatcher`
```kotlin
methods {
    name = "calculateTotal".exact
    returnType = "java.lang.Double".exactClass
    isStatic = false
    isFinal = false
    isSynchronized = false
    isNative = false
    parameterCounts = 2
    
    // Exact parameter types
    parameters = conjunction(String::class.java, Int::class.javaPrimitiveType)
    
    // Or inspect specific parameter index
    parameters = String::class.java[0] and Int::class.javaPrimitiveType[1]
}
```

### 3.3 `FieldMatcher`
```kotlin
fields {
    name = "apiKey".exact
    type = String::class.java.exact
    isStatic = true
    isFinal = true
    isTransient = false
    isVolatile = false
}
```

### 3.4 Deep Dex Bytecode Analysis (`@DexAnalysis`)
Using the `@DexAnalysis` engine, you can match obfuscated methods by what they do internally:

```kotlin
@OptIn(DexAnalysis::class)
methods {
    // 1. Match methods referencing specific constant string literals
    referredStrings = +"AES/CBC/PKCS5Padding" or +"secret_key"

    // 2. Match methods accessing specific fields
    accessedFields = +firstField {
        type = "javax.crypto.Cipher".exactClass
    }

    // 3. Match methods invoking specific constructors or methods
    invokedMethods = +firstMethod {
        name = "doFinal".exact
    }

    // 4. Match bytecode opcode sequences
    containsOpcodes = byteArrayOf(0x70.toByte(), 0x10.toByte())
}
```

---

## 4. Lazy Sequences & Fallback Handling

Lazy sequences (`ClassLazySequence`, `MethodLazySequence`) provide fallback chains and lifecycle bindings:

```kotlin
methods {
    name = "primaryMethod".exact
}.first()
.substituteIfMiss {
    // If primary method is missing (e.g. In newer app version), fallback to secondary pattern
    methods {
        name = "fallbackMethod".exact
    }.first()
}
.onMiss {
    log(Log.WARN, TAG, "Method could not be found with any pattern!")
}
.onMatch { resolvedMethod ->
    hook(resolvedMethod).intercept { it.proceed() }
}
```

---

## 5. `Reflector` Low-Level Parser

The `Reflector` class provides high-speed, cached resolution of members using standard JVM/Smali signatures:

```java
Reflector reflector = new Reflector(param.getClassLoader());

// 1. Load class
Class<?> userClass = reflector.loadClass("com.example.User");

// 2. Load method by Smali signature (Class->name(ParamTypes)ReturnType)
Method getNameMethod = reflector.loadMethod("com.example.User->getName()Ljava/lang/String;");

// 3. Load method by Java-style signature
Method setAgeMethod = reflector.loadMethod("void com.example.User.setAge(int)");

// 4. Load field
Field tokenField = reflector.loadField("com.example.User->mToken:Ljava/lang/String;");
```
