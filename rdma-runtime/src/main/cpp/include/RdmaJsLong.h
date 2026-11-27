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

#include <jsi/jsi.h>
#include <cmath>
#include <cstdint>
#include <string>

#include "RdmaException.h"

// Kotlin/JS `Long` interop, shared by BOTH backends (JNI and C ABI) so the two
// never drift. Policy: docs/design/type_descriptors.md#long-policy — a Long
// crossing is always exact.
//
// A `kotlin.Long` on the plugin side is a `Long(low, high)` object, not a JS
// number, and it crosses the boundary as its two 32-bit halves into the kernel's
// 64-bit Long (`jlong` on Android, `Long` on iOS): full 64-bit fidelity in both
// directions, nothing is ever rounded. The guest bridge installs two converters
// on the global `RDMA` object (the same pattern as `listKind`/`mapKind`):
//
//   longToParts(value) -> [low, high] | null   (null = not a Long)
//   longFromParts(low, high) -> Long
namespace facebook {
namespace rdma {

[[noreturn]] inline void rdmaThrowTypeError(jsi::Runtime& rt, const std::string& message) {
    RdmaExceptionInfo info;
    info.originalExceptionType = "kardman.TypeError";
    info.message = message;
    throwJsException(rt, info);
}

// Reads the exact [low, high] halves of a Kotlin/JS `Long` object through the
// guest bridge's `RDMA.longToParts`. Returns false when `v` is not a Long (the
// hook reports null for foreign objects) or the bridge predates the converters.
inline bool rdmaJsLongParts(jsi::Runtime& rt, const jsi::Value& v, int64_t& out) {
    if (!v.isObject()) return false;
    jsi::Value rdmaVal = rt.global().getProperty(rt, "RDMA");
    if (!rdmaVal.isObject()) return false;
    jsi::Value hook = rdmaVal.asObject(rt).getProperty(rt, "longToParts");
    if (!hook.isObject() || !hook.asObject(rt).isFunction(rt)) return false;
    jsi::Value res = hook.asObject(rt).asFunction(rt).call(rt, v);
    if (res.isNull() || res.isUndefined() || !res.isObject()) return false;
    jsi::Object arr = res.asObject(rt);
    jsi::Value loVal = arr.getProperty(rt, "0");
    jsi::Value hiVal = arr.getProperty(rt, "1");
    if (!loVal.isNumber() || !hiVal.isNumber()) return false;
    int32_t lo = (int32_t)loVal.getNumber();
    int32_t hi = (int32_t)hiVal.getNumber();
    out = (int64_t)(((uint64_t)(uint32_t)hi << 32) | (uint32_t)lo);
    return true;
}

// Reads a boundary value as a 64-bit Long (docs/design/type_descriptors.md#long-policy):
// a Kotlin/JS `Long` object crosses as its exact halves. A plain JS number is NOT a
// Long — a raw `number` must never silently become a 64-bit Long (audit #21: the old
// numerical fallback could tautologically accept `LLONG_MAX` and hand-wave fidelity);
// someone crossing a raw number gets an honest type error.
inline long long rdmaCheckedJLong(jsi::Runtime& rt, const jsi::Value& v) {
    int64_t raw = 0;
    if (rdmaJsLongParts(rt, v, raw)) return (long long)raw;
    rdmaThrowTypeError(rt,
        "expected a Long at the boundary (the plugin's Long object, `Long(low, high)`), "
        "got a value of another shape — a plain JS number must not become a kotlin.Long; "
        "cross the plugin's Long object for 64-bit fidelity");
}

// Wraps a kernel Long back into the plugin's `Long(low, high)` object via the
// guest bridge's `RDMA.longFromParts`, so plugin-side Long arithmetic and
// formatting keep working and all 64 bits survive the crossing.
inline jsi::Value rdmaLongToJs(jsi::Runtime& rt, long long v) {
    jsi::Value rdmaVal = rt.global().getProperty(rt, "RDMA");
    if (rdmaVal.isObject()) {
        jsi::Value hook = rdmaVal.asObject(rt).getProperty(rt, "longFromParts");
        if (hook.isObject() && hook.asObject(rt).isFunction(rt)) {
            int32_t lo = (int32_t)(uint32_t)(uint64_t)v;
            int32_t hi = (int32_t)(uint32_t)((uint64_t)v >> 32);
            jsi::Value loJs((double)lo), hiJs((double)hi);
            return hook.asObject(rt).asFunction(rt).call(rt, loJs, hiJs);
        }
    }
    // A `Long` never crosses as a plain JS number: the plugin's `Long(low, high)`
    // object is the only faithful shape (docs/design/type_descriptors.md#long-policy).
    // A bridge without the converters is incompatible, not degraded: a silent number
    // used to round-trip here and only die later on the strict input channel
    // (`rdmaCheckedJLong`), far away from the cause.
    rdmaThrowTypeError(rt,
        "the plugin bridge predates the Long converters (RDMA.longFromParts is missing): "
        "rebuild the bundle with a current toolchain — a kotlin.Long cannot cross as a plain JS number");
}

} // namespace rdma
} // namespace facebook
