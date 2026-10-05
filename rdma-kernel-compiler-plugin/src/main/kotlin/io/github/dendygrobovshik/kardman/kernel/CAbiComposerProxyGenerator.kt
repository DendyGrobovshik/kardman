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
package io.github.dendygrobovshik.kardman.kernel

import java.io.OutputStream

/**
 * C-ABI variant of [RdmaComposerProxyGenerator] for the iOS backend. Produces
 * `RdmaComposerProxy.h/.cpp` whose `ComposerProxyHost` marshals every `Composer`
 * method through the `rdma_composer_*` Kotlin `@CName` functions (see
 * `RdmaComposeCAbi.h`) instead of JNI. The composer handle is borrowed (owned by
 * the UI-thread content/scope task), so the host does not dispose it.
 */
class CAbiComposerProxyGenerator(private val output: (String, String) -> OutputStream) {

    fun generate(methods: List<ComposerMethod>) {
        generateHeader(methods)
        generateCpp(methods)
    }

    private fun generateHeader(methods: List<ComposerMethod>) {
        val out = output("RdmaComposerProxy.h", "RdmaComposerProxy.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include "RdmaComposeCAbi.h"

namespace facebook {
namespace rdma {

jsi::Object makeComposerProxy(jsi::Runtime& rt);

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateCpp(methods: List<ComposerMethod>) {
        val out = output("RdmaComposerProxy.cpp", "RdmaComposerProxy.cpp").bufferedWriter()
        out.write("""#include "RdmaComposerProxy.h"
#include "RdmaComposeCAbi.h"

#include <memory>
#include <string>

namespace facebook {
namespace rdma {

class ComposerProxyHost : public jsi::HostObject, public std::enable_shared_from_this<ComposerProxyHost> {
public:
    ComposerProxyHost() = default;
    // The Composer never crosses the boundary: the Kotlin shim reads it from its
    // own global, so this proxy is stateless.

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
""")
        for (m in methods) {
            out.write(handlerBlock(m))
        }
        out.write("""
        return jsi::Value::undefined();
    }

private:
};

jsi::Object makeComposerProxy(jsi::Runtime& rt) {
    auto host = std::make_shared<ComposerProxyHost>();
    return jsi::Object::createFromHostObject(rt, host);
}

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun handlerBlock(m: ComposerMethod): String = buildString {
        appendLine("        if (n.rfind(\"${m.jsName}\", 0) == 0) {")
        append(handler(m))
        appendLine("        }")
    }

    private fun handler(m: ComposerMethod): String = when (m.returnKind) {
        ComposerReturnKind.NOOP -> noopHandler(m, "undefined")
        ComposerReturnKind.NOOP_NULL -> noopHandler(m, "null")
        ComposerReturnKind.VOID -> voidHandler(m)
        ComposerReturnKind.BOOLEAN -> booleanHandler(m)
        ComposerReturnKind.COMPOSER_SELF -> composerSelfHandler(m)
        ComposerReturnKind.SCOPE_UPDATE_SCOPE -> scopeHandler(m)
        ComposerReturnKind.CHANGED -> changedHandler()
        ComposerReturnKind.REMEMBERED_VALUE -> rememberedValueHandler()
        ComposerReturnKind.UPDATE_REMEMBERED_VALUE -> updateRememberedValueHandler()
    }

    private fun noopHandler(m: ComposerMethod, value: String): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::$value();
                });
"""

    private fun cFn(m: ComposerMethod): String = "rdma_composer_" + m.jniName

    private fun extraction(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.INT ->
            "            int32_t p$i = count > $i && args[$i].isNumber() ? (int32_t)args[$i].getNumber() : 0;\n"
        ComposerParamKind.BOOLEAN ->
            "            bool p$i = count > $i && args[$i].isBool() ? args[$i].getBool() : false;\n"
        ComposerParamKind.OBJECT ->
            "            void* p$i = count > $i ? boxJsiValue(r, args[$i]) : nullptr;\n"
    }

    private fun cleanup(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.OBJECT -> "                if (p$i) rdma_disposeStableRef(p$i);\n"
        else -> ""
    }

    private fun paramExtract(m: ComposerMethod): String =
        m.params.mapIndexed { i, k -> extraction(i, k) }.joinToString("")

    private fun paramCleanup(m: ComposerMethod): String =
        m.params.mapIndexed { i, k -> cleanup(i, k) }.joinToString("")

    private fun innerParams(m: ComposerMethod): String =
        m.params.indices.joinToString(", ") { "p$it" }

    private fun voidHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
${paramExtract(m)}
                    rdmaCallUi([${innerParams(m)}]() -> RdmaResult {
                        ${cFn(m)}(${innerParams(m)});
${paramCleanup(m)}
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
"""

    private fun booleanHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
${paramExtract(m)}
                    RdmaResult result = rdmaCallUi([${innerParams(m)}]() -> RdmaResult {
                        bool res = ${cFn(m)}(${innerParams(m)});
${paramCleanup(m)}
                        return RdmaResult::boolean(res);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
"""

    private fun composerSelfHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
${paramExtract(m)}
                    rdmaCallUi([${innerParams(m)}]() -> RdmaResult {
                        ${cFn(m)}(${innerParams(m)});
${paramCleanup(m)}
                        return RdmaResult::undefined();
                    });
                    return jsi::Object::createFromHostObject(r, self);
                });
"""

    private fun scopeHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
${paramExtract(m)}
                    RdmaResult result = rdmaCallUi([${innerParams(m)}]() -> RdmaResult {
                        void* scope = ${cFn(m)}(${innerParams(m)});
${paramCleanup(m)}
                        if (!scope) return RdmaResult::null();
                        return RdmaResult::scopeRef(scope);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
"""

    private fun changedHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "changed"), 1,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    void* key = nullptr;
                    if (count > 0) {
                        if (args[0].isObject()) key = rdma_boxObject();
                        else key = boxJsiValue(r, args[0]);
                    }
                    RdmaResult result = rdmaCallUi([key]() -> RdmaResult {
                        bool res = rdma_composer_changed(key);
                        if (key) rdma_disposeStableRef(key);
                        return RdmaResult::boolean(res);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
"""

    private fun rememberedValueHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "rememberedValue"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    RdmaResult result = rdmaCallUi([]() -> RdmaResult {
                        void* v = rdma_composer_rememberedValue();
                        return valueHandleToResult(v);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
"""

    private fun updateRememberedValueHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "updateRememberedValue"), 1,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    if (count < 1) return jsi::Value::undefined();
                    void* stored = nullptr;
                    bool disposeStored = false;
                    void* stateHandle = stateProxyHandle(r, args[0]);
                    if (stateHandle) {
                        stored = stateHandle;
                    } else if (args[0].isObject()) {
                        auto obj = std::make_shared<jsi::Object>(args[0].asObject(r));
                        int64_t id = g_nextJsValueId++;
                        g_jsValues[id] = obj;
                        stored = rdma_boxJsValueHolder(id);
                        disposeStored = true;
                    } else {
                        stored = boxJsiValue(r, args[0]);
                        disposeStored = true;
                    }
                    rdmaCallUi([stored, disposeStored]() -> RdmaResult {
                        rdma_composer_updateRememberedValue(stored);
                        if (disposeStored && stored) rdma_disposeStableRef(stored);
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
"""
}
