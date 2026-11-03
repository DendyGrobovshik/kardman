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
#include <memory>
#include <string>

namespace facebook {
namespace rdma {

// Serializable description of an exception crossing a runtime boundary.
// Strings only — never live jsi::Value / jobject — see
// docs/design/exception_propagation.md §8.
struct RdmaExceptionInfo {
    std::string originalExceptionType;
    std::string message;
    std::string stack;
    std::shared_ptr<RdmaExceptionInfo> cause;

    bool empty() const {
        return originalExceptionType.empty() && message.empty() && stack.empty() && !cause;
    }
};

// Reads an Error-like object (a real `Error`, or one produced by
// `makeJsErrorObject`/`makeJsCauseObject`) into a description. `cause` is
// expected to be a nested Error object, not a plain description.
inline RdmaExceptionInfo fromJsErrorObject(jsi::Runtime& rt, const jsi::Object& o) {
    RdmaExceptionInfo info;
    auto readString = [&](const char* key, const std::string& def = "") -> std::string {
        if (o.hasProperty(rt, key)) {
            jsi::Value v = o.getProperty(rt, key);
            if (v.isString()) {
                return v.getString(rt).utf8(rt);
            }
        }
        return def;
    };
    info.originalExceptionType = readString("name", "Error");
    info.message = readString("message");
    info.stack = readString("stack");
    if (o.hasProperty(rt, "cause")) {
        jsi::Value c = o.getProperty(rt, "cause");
        if (c.isObject()) {
            info.cause = std::make_shared<RdmaExceptionInfo>(fromJsErrorObject(rt, c.asObject(rt)));
        }
    }
    return info;
}

// Serializes a caught JS exception into a plain-string description. The JS Error
// object is not retained; everything is copied out before the stack unwinds.
// When the Error carries a nested `cause` Error (set by makeJsErrorObject for
// Native -> JS and reentrant JS -> JS rethrows) the serialized cause chain is
// rebuilt so a double crossing (host -> JS -> host) preserves `cause`.
inline RdmaExceptionInfo fromJSError(jsi::Runtime& rt, const jsi::JSError& e) {
    RdmaExceptionInfo info;
    info.message = e.getMessage();
    info.stack = e.getStack();
    info.originalExceptionType = "Error";
    try {
        if (e.value().isObject()) {
            jsi::Object obj = e.value().asObject(rt);
            if (obj.hasProperty(rt, "name")) {
                jsi::Value name = obj.getProperty(rt, "name");
                if (name.isString()) {
                    info.originalExceptionType = name.getString(rt).utf8(rt);
                }
            }
            if (obj.hasProperty(rt, "cause")) {
                jsi::Value cause = obj.getProperty(rt, "cause");
                if (cause.isObject()) {
                    info.cause = std::make_shared<RdmaExceptionInfo>(
                        fromJsErrorObject(rt, cause.asObject(rt)));
                }
            }
        }
    } catch (...) {
        // Best-effort extraction; never let classification hide the error.
    }
    return info;
}

// Reborn copy of a full description: the previous runtime's exception carried as
// the `cause` of the Error produced by makeJsErrorObject. Sets `message`/`name`,
// `stack` (the previous runtime's trace), and the nested `cause` chain. Uses the
// standard `Error.cause` field so Kotlin/JS `Throwable.cause` maps to it directly
// (Throwable is `@JsName("Error")`). Does not throw.
inline jsi::Object makeJsCauseObject(jsi::Runtime& rt, const RdmaExceptionInfo& info) {
    jsi::Object err = rt.global()
                          .getPropertyAsObject(rt, "Error")
                          .asFunction(rt)
                          .callAsConstructor(rt, jsi::String::createFromUtf8(rt, info.message))
                          .asObject(rt);
    err.setProperty(rt, "name", jsi::String::createFromUtf8(rt, info.originalExceptionType));
    if (!info.stack.empty()) {
        err.setProperty(rt, "stack", jsi::String::createFromUtf8(rt, info.stack));
    }
    if (info.cause) {
        err.setProperty(rt, "cause", makeJsCauseObject(rt, *info.cause));
    }
    return err;
}

// Reconstructs a JS Error from a description. The returned Error's own
// `message`/`name` mirror `info` (its `stack` is the JS stack captured by the
// `Error` constructor — the current runtime's frame). Its `cause` carries a
// reborn copy of the FULL description via makeJsCauseObject, so a rethrow back
// across the boundary (host -> JS -> host) reconstructs the end-to-end cause
// chain, one level per crossing. Does not throw.
inline jsi::Object makeJsErrorObject(jsi::Runtime& rt, const RdmaExceptionInfo& info) {
    jsi::Object err = rt.global()
                          .getPropertyAsObject(rt, "Error")
                          .asFunction(rt)
                          .callAsConstructor(rt, jsi::String::createFromUtf8(rt, info.message))
                          .asObject(rt);
    err.setProperty(rt, "name", jsi::String::createFromUtf8(rt, info.originalExceptionType));
    err.setProperty(rt, "cause", makeJsCauseObject(rt, info));
    return err;
}

// Reconstructs and throws a JS Error from a description: `message`/`name` mirror
// `info`, `cause` carries the reborn previous-runtime chain (used for Native -> JS
// and reentrant JS -> JS rethrows). Does not return.
[[noreturn]] inline void throwJsException(jsi::Runtime& rt, const RdmaExceptionInfo& info) {
    throw jsi::JSError(rt, makeJsErrorObject(rt, info));
}

} // namespace rdma
} // namespace facebook
