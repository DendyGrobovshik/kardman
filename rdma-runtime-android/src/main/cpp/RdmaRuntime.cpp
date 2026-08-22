#include <jsi/jsi.h>
#include <hermes/hermes.h>
#include <jni.h>
#include <string>
#include <sstream>
#include <memory>
#include <android/log.h>

#include "RdmaBridge.h"

#define LOG_TAG "RdmaRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<facebook::jsi::Runtime> g_runtime;

static std::string jsiValueToString(facebook::jsi::Runtime& rt, const facebook::jsi::Value& val) {
    if (val.isString()) {
        return val.getString(rt).utf8(rt);
    }
    if (val.isNumber()) {
        std::ostringstream oss;
        oss << val.getNumber();
        return oss.str();
    }
    if (val.isBool()) {
        return val.getBool() ? "true" : "false";
    }
    if (val.isNull()) {
        return "null";
    }
    if (val.isUndefined()) {
        return "undefined";
    }
    if (val.isObject()) {
        auto obj = val.getObject(rt);
        if (obj.isFunction(rt)) {
            return "[Function]";
        }
        facebook::jsi::Object json = rt.global().getPropertyAsObject(rt, "JSON");
        facebook::jsi::Function stringify = json.getPropertyAsFunction(rt, "stringify");
        facebook::jsi::Value jsonResult = stringify.call(rt, val);
        if (jsonResult.isString()) {
            return jsonResult.getString(rt).utf8(rt);
        }
        return "[Object]";
    }
    return "[unknown]";
}

void initRdmaRuntime(JavaVM* jvm) {
    g_runtime = facebook::hermes::makeHermesRuntime();

    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);

    facebook::rdma::installRdmaBridge(*g_runtime, jvm, env);

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

void evalJavaScript(const std::string& code, std::string& result) {
    if (!g_runtime) {
        result = "Error: RDMA runtime not initialized";
        return;
    }

    try {
        auto buffer = std::make_shared<facebook::jsi::StringBuffer>(code);
        facebook::jsi::Value jsResult = g_runtime->evaluateJavaScript(buffer, "<eval>");
        result = jsiValueToString(*g_runtime, jsResult);
    } catch (const facebook::jsi::JSError& e) {
        std::ostringstream oss;
        oss << "JSError: " << e.what() << "\n" << e.getStack();
        result = oss.str();
        LOGW("JSError: %s\n%s", e.what(), e.getStack().c_str());
    } catch (const std::exception& e) {
        result = std::string("Exception: ") + e.what();
        LOGW("Exception: %s", e.what());
    }
}
