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
#define LOG_TAG "RdmaProxyCore"

#include "RdmaProxyCore.h"
#include "RdmaLog.h"

#include <array>
#include <unordered_map>
#include <unordered_set>

namespace facebook {
namespace rdma {

namespace {

constexpr size_t kProtoCount = static_cast<size_t>(RdmaProtoKind::Count);

// Kotlin/JS (`-Xir-minimized-member-names=false`) spells members as
// `<base>_<hash>_k$`; unmangled members (`toString`, `equals`, `iterator`'s plain
// alias, …) keep their name. Returns the base name.
std::string baseNameOf(const std::string& name) {
    static const std::string kSuffix = "_k$";
    if (name.size() <= kSuffix.size() || name.compare(name.size() - kSuffix.size(), kSuffix.size(), kSuffix) != 0) {
        return name;
    }
    std::string stem = name.substr(0, name.size() - kSuffix.size());
    size_t us = stem.rfind('_');
    if (us == std::string::npos || us == 0) return stem;
    return stem.substr(0, us);
}

// base name of a stdlib member -> plain name of the host function that implements it.
using AliasTable = std::unordered_map<std::string, std::string>;

const AliasTable& listAliases() {
    static const AliasTable t = {
        {"get_size", "sizeImpl"}, {"get", "get"}, {"set", "set"}, {"add", "add"},
        {"addAll", "addAll"}, {"removeAt", "removeAt"}, {"remove", "remove"},
        {"removeRange", "removeRange"}, {"removeAll", "removeAll"}, {"retainAll", "retainAll"},
        {"clear", "clear"}, {"contains", "contains"}, {"containsAll", "containsAll"},
        {"indexOf", "indexOf"}, {"lastIndexOf", "lastIndexOf"}, {"isEmpty", "isEmpty"},
        {"iterator", "iterator"}, {"subList", "subList"}, {"toArray", "toArray"},
        {"toString", "toString"}, {"equals", "equals"}, {"hashCode", "hashCode"},
    };
    return t;
}

const AliasTable& mapAliases() {
    static const AliasTable t = {
        {"get_size", "sizeImpl"}, {"isEmpty", "isEmpty"}, {"get", "get"}, {"put", "set"},
        {"remove", "remove"}, {"clear", "clear"}, {"containsKey", "containsKey"},
        {"containsValue", "containsValue"}, {"putAll", "putAll"},
        {"get_keys", "keys"}, {"get_values", "values"}, {"get_entries", "entries"},
        {"createKeysView", "keys"}, {"createValuesView", "values"},
        {"checkIsMutable", "checkIsMutable"},
        {"toString", "toString"}, {"equals", "equals"}, {"hashCode", "hashCode"},
    };
    return t;
}

const AliasTable& viewAliases() {
    static const AliasTable t = {
        {"get_size", "sizeImpl"}, {"isEmpty", "isEmpty"}, {"contains", "contains"},
        {"containsAll", "containsAll"}, {"iterator", "iterator"}, {"remove", "remove"},
        {"clear", "clear"}, {"add", "add"}, {"addAll", "addAll"},
        {"checkIsMutable", "checkIsMutable"}, {"toString", "toString"},
    };
    return t;
}

const AliasTable& iteratorAliases() {
    static const AliasTable t = {{"hasNext", "hasNext"}, {"next", "next"}, {"remove", "remove"}};
    return t;
}

const AliasTable& entryAliases() {
    static const AliasTable t = {
        {"get_key", "getKey"}, {"get_value", "getValue"}, {"setValue", "setValue"},
        {"equals", "equals"}, {"hashCode", "hashCode"}, {"toString", "toString"},
    };
    return t;
}

// Own members of a stdlib prototype that may stay unshadowed: they do not read the
// JS backing storage (or are outside the contract, like `asJsArrayView`).
const std::unordered_set<std::string>& safeUnshadowed() {
    static const std::unordered_set<std::string> s = {
        "constructor", "build", "trimToSize", "ensureCapacity", "checkIsMutable",
        "asJsArrayView", "asJsReadonlyArrayView", "get_internalMap", "get_backing",
        "get_modCount", "set_modCount",
        // Accessors of the stdlib list iterator's private cursor fields (`index`, `last`):
        // only that iterator's own methods read them, and the snapshot iterator shadows
        // every one of those (hasNext/next/remove/previous/…).
        "get_index", "set_index", "get_last", "set_last",
    };
    return s;
}

// One-time JS helpers (snapshot iterators, entries). Evaluated once per runtime.
const char* kHelpersSource = R"JS((function (iterParent, entryParent) {
  function eq(a, b) {
    return a === b || (a != null && typeof a.equals === 'function' && a.equals(b));
  }
  function hash(x) {
    if (x == null) return 0;
    if (typeof x.hashCode === 'function') return x.hashCode() | 0;
    if (typeof x === 'string') {
      var h = 0;
      for (var i = 0; i < x.length; i++) h = (Math.imul(31, h) + x.charCodeAt(i)) | 0;
      return h;
    }
    if (typeof x === 'boolean') return x ? 1231 : 1237;
    if (typeof x === 'number') return (x | 0) === x ? x : 0;
    return 0;
  }
  var iterProto = Object.create(iterParent);
  iterProto.hasNext = function () { return this.$i < this.$a.length; };
  iterProto.next = function () {
    var i = this.$i;
    if (i >= this.$a.length) throw new Error('NoSuchElementException');
    this.$l = i;
    this.$i = i + 1;
    return this.$a[i];
  };
  iterProto.remove = function () {
    var l = this.$l;
    if (l < 0) throw new Error('IllegalStateException: next() has not been called');
    this.$r(this.$o, l, this.$a);
    this.$a.splice(l, 1);
    this.$i = l;
    this.$l = -1;
  };
  var entryProto = Object.create(entryParent);
  entryProto.getKey = function () { return this.$k; };
  entryProto.getValue = function () { return this.$v; };
  entryProto.setValue = function (v) {
    var old = this.$v;
    this.$m.set(this.$k, v);
    this.$v = v;
    return old;
  };
  entryProto.equals = function (o) {
    if (o == null || typeof o.$keyOf !== 'function') return false;
    return eq(this.$k, o.$keyOf()) && eq(this.$v, o.$valueOf());
  };
  entryProto.hashCode = function () { return hash(this.$k) ^ hash(this.$v); };
  entryProto.toString = function () { return String(this.$k) + '=' + String(this.$v); };
  entryProto.$keyOf = function () { return this.$k; };
  entryProto.$valueOf = function () { return this.$v; };
  var removers = [
    function (o, i, a) { o.removeAt(i); },
    function (o, i, a) { o.remove(a[i]); },
    function (o, i, a) { o.remove(a[i]); },
    function (o, i, a) { o.remove(a[i]); }
  ];
  return {
    iterProto: iterProto,
    entryProto: entryProto,
    makeIter: function (kind, a, o) {
      var it = Object.create(iterProto);
      it.$a = a; it.$i = 0; it.$l = -1; it.$o = o; it.$r = removers[kind];
      return it;
    },
    makeEntries: function (m, flat) {
      var out = new Array(flat.length >> 1);
      for (var i = 0, j = 0; i < flat.length; i += 2, j++) {
        var e = Object.create(entryProto);
        e.$m = m; e.$k = flat[i]; e.$v = flat[i + 1];
        out[j] = e;
      }
      return out;
    }
  };
}))JS";

struct Registry {
    jsi::Runtime* rt = nullptr;

    // Stdlib shapes, discovered from the guest bridge (`RDMA.collectionPrototypes`).
    bool modern = false;
    std::unique_ptr<jsi::Object> stdList, stdIterator, stdMap, stdKeys, stdValues, stdEntries, stdEntry;
    std::vector<std::string> listNames, iteratorNames, mapNames, viewNames, entryNames;
    std::string listCheckIsMutable; // mangled name of the stdlib ArrayList.checkIsMutable
    std::string mapCheckIsMutable;  // mangled name of the stdlib LinkedHashMap.checkIsMutable

    std::array<std::unique_ptr<jsi::Object>, kProtoCount> protos;

    // JS helpers (built together with the prototypes, rebuilt on discovery).
    std::unique_ptr<jsi::Object> helpers;
    std::unique_ptr<jsi::Function> makeIter, makeEntries;

    // Cached `Object.*` functions.
    std::unique_ptr<jsi::Function> getPrototypeOf, getOwnPropertyNames, defineProperty;
};

// Intentionally leaked: the runtime lives for the whole process, and a function-local
// static holding `jsi::Object`s would be destroyed after the runtime at exit.
Registry* g_registry = nullptr;

Registry& registry(jsi::Runtime& rt) {
    if (!g_registry || g_registry->rt != &rt) {
        g_registry = new Registry();
        g_registry->rt = &rt;
        auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
        g_registry->getPrototypeOf = std::make_unique<jsi::Function>(objectCtor.getPropertyAsFunction(rt, "getPrototypeOf"));
        g_registry->getOwnPropertyNames = std::make_unique<jsi::Function>(objectCtor.getPropertyAsFunction(rt, "getOwnPropertyNames"));
        g_registry->defineProperty = std::make_unique<jsi::Function>(objectCtor.getPropertyAsFunction(rt, "defineProperty"));
    }
    return *g_registry;
}

std::vector<std::string> toStrings(jsi::Runtime& rt, const jsi::Value& v) {
    std::vector<std::string> out;
    if (!v.isObject() || !v.getObject(rt).isArray(rt)) return out;
    jsi::Array arr = v.getObject(rt).getArray(rt);
    size_t n = arr.size(rt);
    out.reserve(n);
    for (size_t i = 0; i < n; i++) {
        jsi::Value e = arr.getValueAtIndex(rt, i);
        if (e.isString()) out.push_back(e.getString(rt).utf8(rt));
    }
    return out;
}

// Names of every member on the prototype chain of `proto`, stopping at Object.prototype.
std::vector<std::string> chainNames(jsi::Runtime& rt, Registry& r, const jsi::Object& proto) {
    std::vector<std::string> out;
    std::unordered_set<std::string> seen;
    jsi::Value cur(rt, proto);
    for (int depth = 0; depth < 16 && cur.isObject(); depth++) {
        jsi::Object o = cur.getObject(rt);
        jsi::Value next = r.getPrototypeOf->call(rt, jsi::Value(rt, o));
        if (next.isNull()) break; // `o` is Object.prototype
        for (auto& n : toStrings(rt, r.getOwnPropertyNames->call(rt, jsi::Value(rt, o)))) {
            if (seen.insert(n).second) out.push_back(n);
        }
        cur = std::move(next);
    }
    return out;
}

std::vector<std::string> ownNames(jsi::Runtime& rt, Registry& r, const jsi::Object& o) {
    return toStrings(rt, r.getOwnPropertyNames->call(rt, jsi::Value(rt, o)));
}

std::unique_ptr<jsi::Object> objectField(jsi::Runtime& rt, const jsi::Object& o, const char* name) {
    jsi::Value v = o.getProperty(rt, name);
    if (!v.isObject()) return nullptr;
    return std::make_unique<jsi::Object>(v.getObject(rt));
}

std::string findMangled(const std::vector<std::string>& names, const std::string& base) {
    for (const auto& n : names) {
        if (baseNameOf(n) == base && n != base) return n;
    }
    return "";
}

void resetBuilt(Registry& r) {
    for (auto& p : r.protos) p.reset();
    r.helpers.reset();
    r.makeIter.reset();
    r.makeEntries.reset();
}

// Reads the stdlib shapes from the guest bridge. Without the hook (only pre-rework
// bundles loaded) the legacy name lists are used and prototypes have no stdlib parent.
void discover(jsi::Runtime& rt, Registry& r) {
    if (r.modern) return;
    jsi::Value rdmaV = rt.global().getProperty(rt, "RDMA");
    if (!rdmaV.isObject()) return;
    jsi::Object rdma = rdmaV.getObject(rt);
    jsi::Value hook = rdma.getProperty(rt, "collectionPrototypes");
    if (hook.isObject() && hook.getObject(rt).isFunction(rt)) {
        jsi::Value res = hook.getObject(rt).getFunction(rt).call(rt);
        if (res.isObject()) {
            jsi::Object shapes = res.getObject(rt);
            r.stdList = objectField(rt, shapes, "list");
            r.stdIterator = objectField(rt, shapes, "iterator");
            r.stdMap = objectField(rt, shapes, "map");
            r.stdKeys = objectField(rt, shapes, "keys");
            r.stdValues = objectField(rt, shapes, "values");
            r.stdEntries = objectField(rt, shapes, "entries");
            r.stdEntry = objectField(rt, shapes, "entry");
            if (r.stdList && r.stdIterator && r.stdMap && r.stdKeys && r.stdValues && r.stdEntries && r.stdEntry) {
                r.listNames = chainNames(rt, r, *r.stdList);
                r.iteratorNames = chainNames(rt, r, *r.stdIterator);
                r.mapNames = chainNames(rt, r, *r.stdMap);
                r.viewNames = chainNames(rt, r, *r.stdKeys);
                for (auto& n : chainNames(rt, r, *r.stdValues)) r.viewNames.push_back(n);
                for (auto& n : chainNames(rt, r, *r.stdEntries)) r.viewNames.push_back(n);
                r.entryNames = chainNames(rt, r, *r.stdEntry);
                r.listCheckIsMutable = findMangled(r.listNames, "checkIsMutable");
                r.mapCheckIsMutable = findMangled(r.mapNames, "checkIsMutable");
                r.modern = true;
                resetBuilt(r); // prototypes built in legacy mode are superseded
                return;
            }
        }
    }
    if (r.listNames.empty()) {
        jsi::Value ln = rdma.getProperty(rt, "listMethodNames");
        if (ln.isObject() && ln.getObject(rt).isFunction(rt)) {
            r.listNames = toStrings(rt, ln.getObject(rt).getFunction(rt).call(rt));
            r.iteratorNames = r.listNames; // the legacy list includes the iterator sample
        }
    }
    if (r.mapNames.empty()) {
        jsi::Value mn = rdma.getProperty(rt, "mapMethodNames");
        if (mn.isObject() && mn.getObject(rt).isFunction(rt)) {
            r.mapNames = toStrings(rt, mn.getObject(rt).getFunction(rt).call(rt));
        }
    }
}

void installAliases(jsi::Runtime& rt, jsi::Object& proto, const std::vector<std::string>& names, const AliasTable& table) {
    for (const auto& n : names) {
        auto it = table.find(baseNameOf(n));
        if (it == table.end() || n == it->second) continue;
        jsi::Value target = proto.getProperty(rt, it->second.c_str());
        if (!target.isObject() || !target.getObject(rt).isFunction(rt)) continue;
        proto.setProperty(rt, n.c_str(), std::move(target));
    }
}

// Every own member of `stdProto` must be shadowed on `proto` (or be known-safe):
// otherwise a stdlib method could read the JS backing storage that a proxy does
// not have (docs/design/runtime_rework.md §3.1).
void guardShadowing(jsi::Runtime& rt, Registry& r, const jsi::Object& proto, const jsi::Object& stdProto, const char* what) {
    for (const auto& n : ownNames(rt, r, stdProto)) {
        if (safeUnshadowed().count(baseNameOf(n))) continue;
        // Not shadowed = the lookup on our prototype still finds the stdlib function.
        jsi::Value mine = proto.getProperty(rt, n.c_str());
        jsi::Value theirs = stdProto.getProperty(rt, n.c_str());
        if (mine.isObject() && theirs.isObject() &&
            jsi::Object::strictEquals(rt, mine.getObject(rt), theirs.getObject(rt))) {
            LOGE("%s proxy prototype does not shadow stdlib member '%s' — it may read storage the proxy does not have", what, n.c_str());
        }
    }
}

void defineSizeAccessor(jsi::Runtime& rt, Registry& r, jsi::Object& proto) {
    auto getter = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "size"), 0,
        [](jsi::Runtime& rr, const jsi::Value& thisVal, const jsi::Value*, size_t) -> jsi::Value {
            jsi::Object self = thisVal.getObject(rr);
            return self.getPropertyAsFunction(rr, "sizeImpl").callWithThis(rr, self, nullptr, 0);
        });
    jsi::Object desc(rt);
    desc.setProperty(rt, "get", std::move(getter));
    desc.setProperty(rt, "configurable", true);
    r.defineProperty->call(rt, jsi::Value(rt, proto), jsi::String::createFromAscii(rt, "size"), jsi::Value(rt, desc));
}

jsi::Value parentFor(jsi::Runtime& rt, Registry& r, RdmaProtoKind kind) {
    if (!r.modern) return rt.global().getPropertyAsObject(rt, "Object").getProperty(rt, "prototype");
    switch (kind) {
        case RdmaProtoKind::List:
        case RdmaProtoKind::StateList:
            return jsi::Value(rt, *r.stdList);
        case RdmaProtoKind::Map:
        case RdmaProtoKind::StateMap:
            return jsi::Value(rt, *r.stdMap);
        // Views inherit the abstract collection layer (AbstractMutableSet /
        // AbstractMutableCollection), whose members are generic over size/iterator.
        case RdmaProtoKind::KeysView:
            return r.getPrototypeOf->call(rt, jsi::Value(rt, *r.stdKeys));
        case RdmaProtoKind::ValuesView:
            return r.getPrototypeOf->call(rt, jsi::Value(rt, *r.stdValues));
        case RdmaProtoKind::EntriesView:
            return r.getPrototypeOf->call(rt, jsi::Value(rt, *r.stdEntries));
        default:
            return jsi::Value::null();
    }
}

void ensureHelpers(jsi::Runtime& rt, Registry& r) {
    if (r.helpers) return;
    discover(rt, r);
    jsi::Value objectProto = rt.global().getPropertyAsObject(rt, "Object").getProperty(rt, "prototype");
    jsi::Value iterParent = (r.modern && r.stdIterator) ? jsi::Value(rt, *r.stdIterator) : jsi::Value(rt, objectProto);
    jsi::Value entryParent = (r.modern && r.stdEntry) ? jsi::Value(rt, *r.stdEntry) : jsi::Value(rt, objectProto);
    jsi::Value factory = rt.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(kHelpersSource), "<rdma-proxy-helpers>");
    jsi::Value made = factory.getObject(rt).getFunction(rt).call(rt, iterParent, entryParent);
    r.helpers = std::make_unique<jsi::Object>(made.getObject(rt));
    r.makeIter = std::make_unique<jsi::Function>(r.helpers->getPropertyAsFunction(rt, "makeIter"));
    r.makeEntries = std::make_unique<jsi::Function>(r.helpers->getPropertyAsFunction(rt, "makeEntries"));
    jsi::Object iterProto = r.helpers->getPropertyAsObject(rt, "iterProto");
    installAliases(rt, iterProto, r.iteratorNames, iteratorAliases());
    jsi::Object entryProto = r.helpers->getPropertyAsObject(rt, "entryProto");
    installAliases(rt, entryProto, r.entryNames, entryAliases());
    if (r.modern) {
        guardShadowing(rt, r, iterProto, *r.stdIterator, "iterator");
        guardShadowing(rt, r, entryProto, *r.stdEntry, "map entry");
    }
}

std::unique_ptr<jsi::Object> buildProto(jsi::Runtime& rt, Registry& r, RdmaProtoKind kind) {
    jsi::Object proto = jsi::Object::create(rt, parentFor(rt, r, kind));
    for (auto& m : rdmaPlatformProtoMethods(rt, kind)) {
        proto.setProperty(rt, m.name.c_str(),
            jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, m.name), m.arity, std::move(m.fn)));
    }
    switch (kind) {
        case RdmaProtoKind::List:
        case RdmaProtoKind::StateList:
            installAliases(rt, proto, r.listNames, listAliases());
            defineSizeAccessor(rt, r, proto);
            if (r.modern) guardShadowing(rt, r, proto, *r.stdList, "list");
            break;
        case RdmaProtoKind::Map:
        case RdmaProtoKind::StateMap: {
            installAliases(rt, proto, r.mapNames, mapAliases());
            defineSizeAccessor(rt, r, proto);
            if (r.modern) {
                // LinkedHashMap.prototype and its parent HashMap.prototype both own storage members.
                guardShadowing(rt, r, proto, *r.stdMap, "map");
                jsi::Value hashMapProto = r.getPrototypeOf->call(rt, jsi::Value(rt, *r.stdMap));
                if (hashMapProto.isObject()) guardShadowing(rt, r, proto, hashMapProto.getObject(rt), "map");
            }
            break;
        }
        case RdmaProtoKind::KeysView:
        case RdmaProtoKind::ValuesView:
        case RdmaProtoKind::EntriesView:
            installAliases(rt, proto, r.viewNames, viewAliases());
            defineSizeAccessor(rt, r, proto);
            break;
        default:
            break;
    }
    return std::make_unique<jsi::Object>(std::move(proto));
}

} // namespace

const jsi::Object& rdmaProto(jsi::Runtime& rt, RdmaProtoKind kind) {
    Registry& r = registry(rt);
    discover(rt, r); // no-op once modern; cheap legacy re-check otherwise
    auto& slot = r.protos[static_cast<size_t>(kind)];
    if (!slot) slot = buildProto(rt, r, kind);
    return *slot;
}

jsi::Object rdmaNewProxy(jsi::Runtime& rt, RdmaProtoKind kind, std::shared_ptr<jsi::NativeState> state) {
    jsi::Object obj = jsi::Object::create(rt, jsi::Value(rt, rdmaProto(rt, kind)));
    obj.setNativeState(rt, std::move(state));
    return obj;
}

void rdmaAdoptProxy(jsi::Runtime& rt, jsi::Object& obj, RdmaProtoKind kind, std::shared_ptr<jsi::NativeState> state) {
    obj.setNativeState(rt, std::move(state));
    obj.setPrototype(rt, jsi::Value(rt, rdmaProto(rt, kind)));
    // Drop the stale JS backing storage so it no longer pins its elements. A
    // read-only plugin map keeps its internals: the stdlib read-only check needs them.
    switch (kind) {
        case RdmaProtoKind::List:
        case RdmaProtoKind::StateList:
            if (obj.hasProperty(rt, "array_1")) obj.setProperty(rt, "array_1", jsi::Value::undefined());
            break;
        case RdmaProtoKind::Map:
        case RdmaProtoKind::StateMap: {
            Registry& r = registry(rt);
            bool readOnly = false;
            if (!r.mapCheckIsMutable.empty()) {
                // Ask the stdlib (still reachable on the parent prototype) whether it is read-only.
                jsi::Value check = r.stdMap ? r.stdMap->getProperty(rt, r.mapCheckIsMutable.c_str()) : jsi::Value::undefined();
                if (check.isObject() && check.getObject(rt).isFunction(rt)) {
                    try {
                        check.getObject(rt).getFunction(rt).callWithThis(rt, obj, nullptr, 0);
                    } catch (const jsi::JSError&) {
                        readOnly = true;
                    }
                }
            }
            if (!readOnly && obj.hasProperty(rt, "internalMap_1")) {
                obj.setProperty(rt, "internalMap_1", jsi::Value::undefined());
            }
            break;
        }
        default:
            break;
    }
}

void rdmaCheckListMutable(jsi::Runtime& rt, const jsi::Object& self) {
    Registry& r = registry(rt);
    if (r.listCheckIsMutable.empty()) return;
    // The stdlib ArrayList.checkIsMutable reads `isReadOnly_1`, which an adopted
    // read-only list keeps and a kernel list does not have.
    jsi::Value fn = self.getProperty(rt, r.listCheckIsMutable.c_str());
    if (fn.isObject() && fn.getObject(rt).isFunction(rt)) {
        fn.getObject(rt).getFunction(rt).callWithThis(rt, self, nullptr, 0);
    }
}

void rdmaCheckMapMutable(jsi::Runtime& rt, const jsi::Object& self) {
    Registry& r = registry(rt);
    if (r.mapCheckIsMutable.empty() || !r.stdMap) return;
    // Only a read-only adopted map still carries its stdlib internals.
    jsi::Value internals = self.getProperty(rt, "internalMap_1");
    if (!internals.isObject()) return;
    jsi::Value fn = r.stdMap->getProperty(rt, r.mapCheckIsMutable.c_str());
    if (fn.isObject() && fn.getObject(rt).isFunction(rt)) {
        fn.getObject(rt).getFunction(rt).callWithThis(rt, self, nullptr, 0);
    }
}

jsi::Object rdmaSnapshotIterator(jsi::Runtime& rt, RdmaIterKind kind, const jsi::Object& owner, jsi::Array snapshot) {
    Registry& r = registry(rt);
    ensureHelpers(rt, r);
    jsi::Value it = r.makeIter->call(rt, jsi::Value(static_cast<double>(kind)), jsi::Value(rt, snapshot), jsi::Value(rt, owner));
    return it.getObject(rt);
}

jsi::Array rdmaMakeEntries(jsi::Runtime& rt, const jsi::Object& map, const jsi::Array& flat) {
    Registry& r = registry(rt);
    ensureHelpers(rt, r);
    jsi::Value out = r.makeEntries->call(rt, jsi::Value(rt, map), jsi::Value(rt, flat));
    return out.getObject(rt).getArray(rt);
}

bool rdmaInheritedEquals(jsi::Runtime& rt, RdmaProtoKind kind, const jsi::Object& self, const jsi::Value& other) {
    Registry& r = registry(rt);
    if (!r.modern) return false;
    const jsi::Object* std = nullptr;
    switch (kind) {
        case RdmaProtoKind::List:
        case RdmaProtoKind::StateList:
            std = r.stdList.get();
            break;
        case RdmaProtoKind::Map:
        case RdmaProtoKind::StateMap:
            std = r.stdMap.get();
            break;
        default:
            return false;
    }
    if (!std) return false;
    jsi::Value eqFn = std->getProperty(rt, "equals");
    if (!eqFn.isObject() || !eqFn.getObject(rt).isFunction(rt)) return false;
    jsi::Value res = eqFn.getObject(rt).getFunction(rt).callWithThis(rt, self, other);
    return res.isBool() && res.getBool();
}

jsi::Object rdmaLazyProto(jsi::Runtime& rt, RdmaMethodResolver resolve) {
    static const char* kSource = R"JS((function (resolve) {
  var proto;
  var trap = new Proxy({}, {
    get: function (target, name) {
      if (typeof name !== 'string') return undefined;
      var fn = resolve(name);
      Object.defineProperty(proto, name, { value: fn, writable: true, configurable: true });
      return fn;
    }
  });
  proto = Object.create(trap);
  return proto;
}))JS";
    auto resolver = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "resolve"), 1,
        [resolve = std::move(resolve)](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) return jsi::Value::undefined();
            return resolve(r, args[0].getString(r).utf8(r));
        });
    jsi::Value factory = rt.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(kSource), "<rdma-lazy-proto>");
    return factory.getObject(rt).getFunction(rt).call(rt, jsi::Value(rt, resolver)).getObject(rt);
}

bool rdmaHasStdlibPrototypes(jsi::Runtime& rt) {
    Registry& r = registry(rt);
    discover(rt, r);
    return r.modern;
}

} // namespace rdma
} // namespace facebook
