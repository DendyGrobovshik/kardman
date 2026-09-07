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
#include <jsi/jsi.h>
#include <hermes/hermes.h>
#include <jni.h>
#include <string>
#include <memory>
#include <android/log.h>

#include "RdmaRuntime.h"
#include "RdmaCompose.h"

#define LOG_TAG "RdmaRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<facebook::jsi::Runtime> g_runtime;

facebook::jsi::Runtime* getRdmaRuntime() {
    return g_runtime.get();
}

void initRdmaRuntime(JavaVM* jvm) {
    if (g_runtime) return; // already initialized

    g_runtime = facebook::hermes::makeHermesRuntime();

    // JSI-only install; JNI caches were already initialized on the UI thread.
    facebook::rdma::installRdmaComposeBridge(*g_runtime, jvm);

    // Hermes doesn't have console, globalThis — provide stubs
    {
        auto& rt = *g_runtime;
        facebook::jsi::Object console(rt);
        auto logFn = facebook::jsi::Function::createFromHostFunction(
            rt, facebook::jsi::PropNameID::forAscii(rt, "log"), 0,
            [](facebook::jsi::Runtime& r, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) {
                if (count > 0) {
                    if (args[0].isString()) {
                        LOGI("JS: %s", args[0].getString(r).utf8(r).c_str());
                    } else if (args[0].isObject() && args[0].asObject(r).isFunction(r)) {
                        auto& irt = *(facebook::jsi::IRuntime*)&r;
                        auto result = args[0].asObject(r).asFunction(r).call(irt, nullptr, 0);
                        if (result.isString()) {
                            LOGI("JS: %s", result.getString(r).utf8(r).c_str());
                        } else if (result.isNumber()) {
                            LOGI("JS: %d", (int)result.getNumber());
                        }
                    }
                }
                return facebook::jsi::Value::undefined();
            });
        console.setProperty(rt, "log", std::move(logFn));
        rt.global().setProperty(rt, "console", std::move(console));
        rt.global().setProperty(rt, "globalThis", rt.global());
    }

    // Provide a global `println` so inlined JS event bodies (e.g. `println("clicked")`)
    // emitted by the plugin transform have a callable symbol in Hermes.
    {
        auto& rt = *g_runtime;
        auto printlnFn = facebook::jsi::Function::createFromHostFunction(
            rt, facebook::jsi::PropNameID::forAscii(rt, "println"), 1,
            [](facebook::jsi::Runtime& r, const facebook::jsi::Value&, const facebook::jsi::Value* args, size_t count) {
                for (size_t i = 0; i < count; i++) {
                    if (args[i].isString()) {
                        LOGI("println: %s", args[i].getString(r).utf8(r).c_str());
                    } else if (args[i].isNumber()) {
                        LOGI("println: %d", (int)args[i].getNumber());
                    } else if (args[i].isBool()) {
                        LOGI("println: %s", args[i].getBool() ? "true" : "false");
                    }
                }
                return facebook::jsi::Value::undefined();
            });
        rt.global().setProperty(rt, "println", std::move(printlnFn));
    }

    LOGI("RDMA runtime initialized with bridge");
}

void evalJavaScript(const std::string& code) {
    if (!g_runtime) {
        LOGW("evalJavaScript: runtime not initialized");
        return;
    }

    try {
        auto buffer = std::make_shared<facebook::jsi::StringBuffer>(code);
        auto prepared = g_runtime->prepareJavaScript(buffer, "<eval>");
        g_runtime->evaluatePreparedJavaScript(prepared);
    } catch (const facebook::jsi::JSError& e) {
        LOGW("JSError: %s\n%s", e.what(), e.getStack().c_str());
    } catch (const std::exception& e) {
        LOGW("Exception: %s", e.what());
    }
}
