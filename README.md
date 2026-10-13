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

| You want to… | Read |
|---|---|
| Install prerequisites, run the demo, use the framework in your own project | [setup.md](docs/user_guide/setup.md) |
| Fix a failing setup, build or demo run | [troubleshooting.md](docs/user_guide/troubleshooting.md) |
| Learn what you can express with `@RDMA` (data, UI, effects, coroutines) | [features.md](docs/user_guide/features.md) |
| Run releases and operate the store | [releases.md](docs/user_guide/releases.md) |
| Contribute to the framework itself (build, test, debug) | [contribution.md](docs/user_guide/contribution.md) |

## Project modules

The framework modules are published to `mavenLocal`

### Compiler & Gradle plugins

| Module | Role |
|--------|------|
| `:rdma-kernel-compiler-plugin` | IR compiler plugin → generates C++ JNI/JSI glue + `rdma_manifest.json` + vtable injection |
| `:rdma-kernel-gradle-plugin` | Gradle wrapper that wires `:rdma-kernel-compiler-plugin` into kernel modules |
| `:rdma-plugin-compiler-plugin` | FIR compiler plugin → resolves `@RDMA` usages and rewrites plugin source to JS proxy calls |
| `:rdma-plugin-gradle-plugin` | Gradle wrapper that wires `:rdma-plugin-compiler-plugin` into plugin modules and generates the guest-side widget bridge |
| `:rdma-app-gradle-plugin` | Gradle plugin applied to the app: generates the user bridge + aggregate bridge and compiles the generated C++ into `librdma_user.so` |

### Runtime & types

| Module | Role |
|--------|------|
| `:rdma-annotation` | `@RDMA` annotation (KMP) |
| `:rdma-types` | Shared metadata model + `RdmaManifest` (`@Serializable` DTOs) |
| `:rdma-runtime` | Android AAR: generic Hermes runtime + JNI bridge + C++ glue (exported as a prefab) |
| `:rdma-runtime-ios` | iOS static framework: generic Hermes runtime + C ABI bridge + C++ core (`librdma_core.a`) |
| `:shared-runtime` | Shared JS runtime bundle (stdlib, coroutines, androidx-collection, compose-runtime) compiled to `.hbc`, loaded before the plugin bundles |

### Demo (standalone build)

`demo/` is a separate Gradle build (`./gradlew -p demo ...`) that consumes the published
framework from mavenLocal and keeps a minimal, self-contained sample — the same sample code
as `tests/stand/`, minus the integration-test fixtures:

| Module | Role |
|--------|------|
| `:kernel:internal` | Framework-owned `@RDMA` classes/widgets |
| `:kernel:user:alice` | Sample user `alice`'s kernel module |
| `:plugin:alice:counter` | Sample plugin of user `alice` (Kotlin/JS) |
| `:androidApp` | Android demo app |
| `:iosApp` | iOS demo app |

### Store (server side)

| Module | Role |
|--------|------|
| `:rdma-store` | JVM HTTP store: plugin registry + re-sync reconciliation + P1–P4 state machine; serves plugin/polyfill bundles (§7.2–7.4, §8) |

### Test fixtures (main build, not published)

The sample user code lives under `tests/stand/` (test fixtures in the main build).

| Module | Role |
|--------|------|
| `:tests:stand:kernel:internal` | Framework-owned `@RDMA` classes/widgets (standard functionality) |
| `:tests:stand:kernel:user:alice` | Sample user `alice`'s kernel module (`@RDMA` classes) |
| `:tests:stand:kernel:user:itest` | Integration-test fixture kernel module |
| `:tests:stand:plugin:alice:counter` | Sample plugin of user `alice` (Kotlin/JS), compiles to JS executed in Hermes |
| `:tests:stand:plugin:bob:services` | Sample plugin of user `bob` (Kotlin/JS) |
| `:tests:stand:plugin:itest:newplug` | Integration-test fixture plugin |
| `:tests:stand:androidApp` | Android test host — initializes Hermes, loads plugin JS |
| `:tests:stand:iosApp` | iOS test host — initializes Hermes, loads plugin `.hbc` |

Run it with `scripts/demo.sh` (publishes the framework, static-releases the demo kernel, then
builds the app).

### Tests

| Module | Role |
|--------|------|
| `:tests:rdma-tests` | JVM tests: compiler-plugin output (C++ generation) + versioning/release/polyfill machinery |
| `:tests:rdma-integration-tests` | Store state-machine tests + `e2e/plugin-lifecycle.sh` end-to-end suite |

The framework (the compiler plugins, runtimes, types, store and `:shared-runtime`) is
generic and knows nothing about user code. The `tests/stand/*` and `demo/*` modules are a
self-contained sample of *user* code: kernel modules declare `@RDMA` types/widgets, plugin
modules use them, and the app module applies the `rdma-app` plugin to build the bridge into
`librdma_user.so` and register it with the runtime through the `installUserBridge` hook.

## Repository layout (not Gradle modules)

- `scripts/` — build/setup/release scripts. `scripts/setup.sh` is the one-time setup entry
  point, `scripts/setup/*.sh` are the individual steps, `scripts/test.sh` runs the fast JVM
  tests, `scripts/demo.sh` builds the standalone demo, and `scripts/publish/` is a separate
  minimal Gradle build that publishes only the framework modules to `mavenLocal` (plus
  `scripts/publish/hermesc/`, which publishes the `hermesc` binary as a maven artifact).
- `tests/stand/` — the sample user code as test fixtures in the main build (`:tests:stand:*`).
- `demo/` — a standalone Gradle build of the sample, consuming the published framework from
  mavenLocal (mirrors an external consumer like `wb2`).
- `versions/` — kernel versioning state, generated by the release pipeline into the
  configurable `rdmaVersionsDir` (default `versions/`); it is gitignored, never committed.
  See [Versioning](docs/versioning.md).
- `third_party/` — external build inputs: `kotlin/` (compiler fork submodule), `hermes/`
  (pinned Hermes source checkout), `bin/hermesc` (built compiler).
- `docs/` — documentation (see below).

## Docs

User guides (`docs/user_guide/`):

- [Setup](docs/user_guide/setup.md)
- [Troubleshooting](docs/user_guide/troubleshooting.md)
- [How to use](docs/user_guide/how_to_use.md)
- [Features](docs/user_guide/features.md)
- [Releases & operations](docs/user_guide/releases.md)
- [Contributing](docs/user_guide/contribution.md)

Architecture & reference (`docs/`):

- [Architecture](docs/architecture.md)
- [Bridges](docs/bridges.md)
- [Modules](docs/modules_architecture.md)
- [Code generation](docs/codegen.md)
- [Boundary types](docs/types.md)
- [Versioning](docs/versioning.md)

Design intent (`docs/design/`):

- [Design concept](docs/design/ORIGINAL_DESIGN.md)
- [Versioning & compatibility](docs/design/compatibility.md)
- [iOS design](docs/design/ios_design.md)

# License

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
