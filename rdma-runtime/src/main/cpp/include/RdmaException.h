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

// Deserializes the object produced by `toJsObject`
// ({ originalExceptionType, message, stack, cause }, recursive).
inline RdmaExceptionInfo fromJsObject(jsi::Runtime& rt, const jsi::Object& o) {
    RdmaExceptionInfo info;
    auto readString = [&](const char* key) -> std::string {
        if (o.hasProperty(rt, key)) {
            jsi::Value v = o.getProperty(rt, key);
            if (v.isString()) {
                return v.getString(rt).utf8(rt);
            }
        }
        return "";
    };
    info.originalExceptionType = readString("originalExceptionType");
    info.message = readString("message");
    info.stack = readString("stack");
    if (o.hasProperty(rt, "cause")) {
        jsi::Value c = o.getProperty(rt, "cause");
        if (c.isObject()) {
            info.cause = std::make_shared<RdmaExceptionInfo>(fromJsObject(rt, c.asObject(rt)));
        }
    }
    return info;
}

// Serializes a caught JS exception into a plain-string description. The JS Error
// object is not retained; everything is copied out before the stack unwinds.
// When the Error carries the `__rdmaCause` field (set by throwJsException for
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
            if (obj.hasProperty(rt, "__rdmaCause")) {
                jsi::Value cause = obj.getProperty(rt, "__rdmaCause");
                if (cause.isObject()) {
                    info.cause = std::make_shared<RdmaExceptionInfo>(
                        fromJsObject(rt, cause.asObject(rt)));
                }
            }
        }
    } catch (...) {
        // Best-effort extraction; never let classification hide the error.
    }
    return info;
}

// Serializes the description into a JS object
// { originalExceptionType, message, stack, cause } (recursive).
inline jsi::Object toJsObject(jsi::Runtime& rt, const RdmaExceptionInfo& info) {
    jsi::Object o(rt);
    o.setProperty(rt, "originalExceptionType",
                  jsi::String::createFromUtf8(rt, info.originalExceptionType));
    o.setProperty(rt, "message", jsi::String::createFromUtf8(rt, info.message));
    o.setProperty(rt, "stack", jsi::String::createFromUtf8(rt, info.stack));
    if (info.cause) {
        o.setProperty(rt, "cause", toJsObject(rt, *info.cause));
    }
    return o;
}

// Reconstructs and throws a JS Error from a description: `message`/`name` are set,
// `__rdmaCause` carries the serialized chain (used for Native -> JS and reentrant
// JS -> JS rethrows). Does not return.
[[noreturn]] inline void throwJsException(jsi::Runtime& rt, const RdmaExceptionInfo& info) {
    jsi::Object err = rt.global()
                          .getPropertyAsObject(rt, "Error")
                          .asFunction(rt)
                          .callAsConstructor(rt, jsi::String::createFromUtf8(rt, info.message))
                          .asObject(rt);
    err.setProperty(rt, "name", jsi::String::createFromUtf8(rt, info.originalExceptionType));
    err.setProperty(rt, "__rdmaCause", toJsObject(rt, info));
    throw jsi::JSError(rt, std::move(err));
}

} // namespace rdma
} // namespace facebook
