# Kardman

Kardman - framework that allows dynamically load code and UI logic into mobile application.

It connects Kotlin(kernel) and JS(plugin) runtimes in an effective way. 
There is no naive manual serialization, `@RDMA` objects always located in kernel memory, plugin uses this object as usual, however it works only as proxy.
It also proxies compose UI logic, so dynamically loaded UI logic modify compose tree in kernel, so you achieve native speed in some use cases.

## How to use

1. Create a class in `kernel` (shipped in app):
   ```kotlin
   @RDMA
   class MyType(val name: String, val value: Int)
   ```

2. Use it in `plugin` (dynamically loaded):
   ```kotlin
   import com.example.kernel.MyType

   val x = MyType("hello", 42)
   println(x.name)
   ```

Regular kotlin code, no need for manual serialization/deserialization. For android it creates `MyType` object in JVM memory and call its methods via proxies by JSI and JNI.

## `@Compose` UI usecase

1. Kernel might provide some basic components
```kotlin
@Composable
@RDMA
fun Spacer(width: Double, height: Double) {
    M3Spacer(Modifier.size(width.dp, height.dp))
}

@Composable
@RDMA
fun Text(text: String) {
   M3Text(text)
}
```

2. Plugin naturally use it
```kotlin
@Composable
fun MyCard() {
    Text("Title")
    M3Spacer(0.0, 8.0)
    Text("Description")
}
```

## Service usecase

1. Define any service in kernel
```kotlin
@RDMA
class HttpClient {
    fun send(request: String, onResponse: (String) -> Unit) { ... } // uses IO thread
}
```

2. And easily use it in plugin offloading all heavy network work to IO thread
```kotlin
@Composable
fun ProductTitle() {
    val title = remember { mutableStateOf("") }

    remember {
        HttpClient().send("GET https://api/product/123") { response ->
            val name = runCatching {
                json.parseToJsonElement(response).jsonObject["name"]!!.jsonPrimitive.content
            }.getOrDefault("")
            title.value = name
        }
    }

    Text(title.value)
}
```

## Open `@RDMA` class

1. Kernel defines open class
```kotlin
@RDMA
open class Animal(val name: String) {
    open fun speak(): String = "..."
    open fun speakTwice(): String = "${speak()} ${speak()}"
    fun upperName(): String = name.uppercase()
}
```

2. Plugin inherits and override
```kotlin
class Dog(name: String) : Animal(name) {
    override fun speak(): String = "Woof!"
}
```

While working with `Dog` object overridden method will be called from both plugin and kernel.

## Getting started

Prerequisites, running the demo and how to wire the framework into your own
project are in the [user guide](docs/user_guide.md).

## Project modules

| Module | Role |
|--------|------|
| `:rdma-annotation` | `@RDMA` annotation (KMP) |
| `:rdma-types` | Shared metadata model + `RdmaManifest` (`@Serializable` DTOs) |
| `:kernel` | @RDMA annotated classes + @Composable UI widgets (JVM + material3) |
| `:rdma-kernel-compiler-plugin` | IR compiler plugin → generates C++ JNI/JSI glue + `rdma_manifest.json` + vtable injection |
| `:rdma-kernel-gradle-plugin` | Gradle wrapper that wires `:rdma-kernel-compiler-plugin` into the kernel module |
| `:rdma-plugin-compiler-plugin` | FIR compiler plugin → resolves @RDMA usages and rewrites plugin source to JS proxy calls |
| `:rdma-plugin-gradle-plugin` | Gradle wrapper that wires `:rdma-plugin-compiler-plugin` into the plugin module's JVM resolve compilation and generates the guest-side widget bridge |
| `:rdma-app-gradle-plugin` | Gradle plugin applied to the app: generates the user bridge + compiles the generated C++ into `librdma_user.so` |
| `:rdma-runtime-android` | Android AAR: generic Hermes runtime + JNI bridge + C++ glue (exported as a prefab) |
| `:plugin` | Demo plugin (Kotlin/JS), compiles to JS executed in Hermes |
| `:androidApp` | Android app — initializes Hermes, loads plugin JS |
| `:rdma-tests` | JVM tests asserting on generated C++ output |

The framework (all modules except `:kernel`, `:plugin`, `:androidApp`) is generic and
knows nothing about user code. `:kernel`/`:plugin`/`:androidApp` are a self-contained
sample of *user* code: `:kernel` declares the `@RDMA` types/widgets, `:plugin` uses them,
and `:androidApp` applies the `rdma-app` plugin to build the bridge into `librdma_user.so`
and register it with the runtime through the `installUserBridge` hook.

## Limitations

#TODO

## Docs

- [User guide](docs/user_guide.md)
- [Features](docs/features.md)
- [Architecture](docs/architecture.md)
- [Modules](docs/modules_architecture.md)
- [Code generation](docs/codegen.md)
- [Contributing](docs/contribution.md)
- [Design concept](docs/ORIGINAL_DESIGN.md)

# Лицензия / License

Licensed with Apache 2.0. See details in [LICENSE](LICENSE).

Copyright 2026 DendyGrobovshik

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
