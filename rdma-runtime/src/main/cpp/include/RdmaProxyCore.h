/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

// Platform-agnostic core of the JS-side object model (docs/design/runtime_rework.md §3).
//
// Every JS proxy over a kernel value is a plain JS object carrying a NativeState
// (the platform handle + its descriptor) whose prototype is a SHARED, per-runtime
// prototype holding host functions. Collection prototypes sit between the object and
// the Kotlin/JS stdlib prototype, so a kernel list *is* a Kotlin `ArrayList`:
//
//   proxy ─▶ NativeListProto (host fns, mangled + plain names, `size`)
//                 └─▶ ArrayList.prototype (storage methods shadowed) ─▶ AbstractMutableList …
//
// Prototypes are built once per runtime from method tables the platform provides
// (`rdmaPlatformProtoMethods`). Nothing per object but `Object.create` + NativeState.

#include <jsi/jsi.h>

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace facebook {
namespace rdma {

// Tag of every NativeState the runtime attaches to a JS object. A type check is a
// field compare + static_pointer_cast — no RTTI on the hot path.
enum class RdmaStateKind : uint8_t {
    Object = 1,   // an @RDMA object handle (generated <X>NativeState)
    List = 2,
    Map = 3,
    MapView = 4,  // keys / values / entries of a native map
    State = 5,    // Compose MutableState proxy
    Composer = 6, // Compose Composer proxy
    Scope = 7,    // Compose ScopeUpdateScope proxy
    Other = 15,   // runtime-internal states without a dedicated kind
};

class RdmaTaggedState : public jsi::NativeState {
public:
    explicit RdmaTaggedState(RdmaStateKind kind) : kind_(kind) {}
    RdmaStateKind kind() const { return kind_; }

private:
    RdmaStateKind kind_;
};

// Every NativeState set by this runtime derives from RdmaTaggedState (plugin code
// cannot set native state), so the static cast below is sound.
inline std::shared_ptr<RdmaTaggedState> rdmaTaggedStateOf(jsi::Runtime& rt, const jsi::Object& obj) {
    if (!obj.hasNativeState(rt)) return nullptr;
    return std::static_pointer_cast<RdmaTaggedState>(obj.getNativeState(rt));
}

inline std::shared_ptr<RdmaTaggedState> rdmaTaggedStateOf(jsi::Runtime& rt, const jsi::Value& v) {
    if (!v.isObject()) return nullptr;
    return rdmaTaggedStateOf(rt, v.getObject(rt));
}

template <class T>
inline std::shared_ptr<T> rdmaStateAs(jsi::Runtime& rt, const jsi::Value& v, RdmaStateKind kind) {
    auto s = rdmaTaggedStateOf(rt, v);
    if (!s || s->kind() != kind) return nullptr;
    return std::static_pointer_cast<T>(s);
}

template <class T>
inline std::shared_ptr<T> rdmaStateAs(jsi::Runtime& rt, const jsi::Object& o, RdmaStateKind kind) {
    auto s = rdmaTaggedStateOf(rt, o);
    if (!s || s->kind() != kind) return nullptr;
    return std::static_pointer_cast<T>(s);
}

// Argument `i` of a host function call, or `undefined` when it was not passed.
inline const jsi::Value& rdmaArg(const jsi::Value* args, size_t count, size_t i) {
    static const jsi::Value kUndefined;
    return i < count ? args[i] : kUndefined;
}

// The shared prototypes.
enum class RdmaProtoKind : uint8_t {
    List,        // kernel/morphed list
    StateList,   // kernel SnapshotStateList (registers composition reads)
    Map,
    StateMap,
    KeysView,
    ValuesView,
    EntriesView,
    Count,
};

// A host function installed on a shared prototype. `name` is the plain name (what
// pre-rework `dynamic` bundles call); the core additionally aliases every
// compile-mangled stdlib spelling that maps to it (see the alias tables in
// RdmaProxyCore.cpp).
struct RdmaProtoMethod {
    std::string name;
    unsigned arity;
    jsi::HostFunctionType fn;
};

// ---- implemented by the platform (JNI: ListHandle.cpp/MapHandle.cpp; C ABI: RdmaComposeCAbi.cpp)

// Method table of a prototype kind. Every function reads its state from `this`.
std::vector<RdmaProtoMethod> rdmaPlatformProtoMethods(jsi::Runtime& rt, RdmaProtoKind kind);

// ---- provided by the core

// The shared prototype of `kind` (built on first use, cached for the runtime).
const jsi::Object& rdmaProto(jsi::Runtime& rt, RdmaProtoKind kind);

// A fresh proxy: `Object.create(proto(kind))` + native state.
jsi::Object rdmaNewProxy(jsi::Runtime& rt, RdmaProtoKind kind, std::shared_ptr<jsi::NativeState> state);

// Turns an existing plugin-created Kotlin collection into a proxy in place
// (identity preserved): attaches the state, swaps the prototype and drops the stale
// JS backing storage so it no longer pins its elements.
void rdmaAdoptProxy(jsi::Runtime& rt, jsi::Object& obj, RdmaProtoKind kind, std::shared_ptr<jsi::NativeState> state);

// Calls the stdlib `checkIsMutable` of a (possibly read-only) plugin-created list
// that was adopted as a proxy; a kernel-created list has no read-only flag and
// passes. Throws the Kotlin exception into JS when the list is read-only.
void rdmaCheckListMutable(jsi::Runtime& rt, const jsi::Object& self);

// Same for maps: a read-only plugin-created map keeps its stdlib internals and is
// checked by them; any other map passes.
void rdmaCheckMapMutable(jsi::Runtime& rt, const jsi::Object& self);

// Snapshot iteration (docs/design/runtime_rework.md §3.6): wraps an array of
// already-converted elements into a Kotlin-compatible MutableIterator. `remover`
// is called as `remover(owner, index, snapshot)` by `iterator.remove()`.
enum class RdmaIterKind : uint8_t { List, Keys, Values, Entries };
jsi::Object rdmaSnapshotIterator(jsi::Runtime& rt, RdmaIterKind kind, const jsi::Object& owner, jsi::Array snapshot);

// Turns a flat `[k0, v0, k1, v1, …]` snapshot into an array of Map.Entry-compatible
// objects. `map` is the owning map proxy (`setValue` writes through its plain `set`).
jsi::Array rdmaMakeEntries(jsi::Runtime& rt, const jsi::Object& map, const jsi::Array& flat);

// Structural equality fallback: the inherited Kotlin `equals` of the stdlib
// prototype (used when the other side is not a native proxy). Returns false when the
// stdlib prototypes are unknown (legacy bundles only).
bool rdmaInheritedEquals(jsi::Runtime& rt, RdmaProtoKind kind, const jsi::Object& self, const jsi::Value& other);

// A shared prototype for a protocol proxy whose method names are compile-mangled by
// the plugin toolchain and not known in advance (the Compose `Composer`,
// `MutableState`, `ScopeUpdateScope`). Behind it sits an ES6 Proxy trap: the first
// lookup of a name calls `resolve(name)` ONCE and caches the result (a host function,
// or `undefined`) on the prototype, so every later call is an ordinary prototype hit —
// no per-call name matching, no per-call function allocation
// (docs/design/runtime_rework.md §3.8). Host functions read their state from `this`.
using RdmaMethodResolver = std::function<jsi::Value(jsi::Runtime&, const std::string&)>;
jsi::Object rdmaLazyProto(jsi::Runtime& rt, RdmaMethodResolver resolve);

// True when the stdlib prototypes were discovered (a current guest bridge is
// loaded); false in the legacy fallback where proxies are plain objects.
bool rdmaHasStdlibPrototypes(jsi::Runtime& rt);

} // namespace rdma
} // namespace facebook
