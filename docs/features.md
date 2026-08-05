# Features

## Supported

### Types

| Kotlin type | Status | Notes |
|---|---|---|
| `String` | Supported | |
| `Int` | Supported | |
| `Long` | Supported | |
| `Float` | Supported | |
| `Double` | Supported | |
| `Boolean` | Supported | |
| `Unit` / `void` | Supported | Return type only |
| `@RDMA class` | Supported | As parameter and return type |
| Literals in constructor args | Supported | `"str"`, `42`, `true` |
| Variable refs in constructor args | Supported | `Person(p)`, `Person(x, 5)` |

### Constructors

- Primary constructor with any number of parameters
- Parameter types: all supported types (primitives + @RDMA classes)

### Properties

- Access via getter methods: `.name` → `getName()`
- Auto-transformed by plugin build script

### Methods

- Any method name, any number of parameters
- Return types: all supported types (primitives + @RDMA classes)
- `toString()` — always available (via Kotlin `Any`)

### Code generation

- C++ bridge generated automatically via KSP
- JNI methodID caching — `FindClass`/`GetMethodID` called once at init
- JVM object lifetime managed via `NativeState` destructor (`DeleteGlobalRef`)
- Multiple @RDMA classes in any files — detected automatically

### Plugin transformation

- Auto-detects @RDMA types via `rdma_classes.json` manifest
- Transforms constructor calls: `Person("str", 42)` → `js("RDMA.createPerson('str', 42)")`
- Transforms property access: `.name` → `.getName()`
- Method calls pass through unchanged (dynamic dispatch)

## Not yet supported

### Types
- `List<T>`, `Array<T>`, `Map<K,V>`
- Nullable types (`String?`)
- Custom value types / data classes (non-@RDMA)
- Enum classes

### Constructors
- Overloaded / secondary constructors
- Named parameters
- Default parameter values

### Properties
- Native JS property access (`.name` without getter) — uses `getName()` methods instead
- Mutable property setters (generated in C++ but not tested end-to-end)
- Lateinit / delegated properties

### Methods
- Overloaded methods (same name, different signatures)
- Extension functions
- Suspend / coroutine functions
- Default parameter values

### Plugin transformation
- Regex-based (not AST-based) — may fail on complex expressions
- Does not distinguish @RDMA type from local class with same name in non-kernel packages
- Inline string interpolation in constructor args

### Runtime
- Circular references across runtimes (JVM ↔ Hermes)
- JNI exception propagation to JS
- Async / Promise return types
- Object identity / `equals()` across runtimes (each call creates separate Handle)
- Batch operations / bulk transfers
