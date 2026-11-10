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

import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaEnumInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.StaticInfo
import java.io.OutputStream

/**
 * Generates the C++ side of the C ABI: per-class proxies + the module bridge.
 * Mirrors `CppGenerator` but the "leaf" is a direct call to the generated
 * `rdma_<mod>_<Class>_<member>` Kotlin `@CName` functions instead of JNI.
 */
class CAbiCppGenerator(
    private val moduleId: String,
    private val output: (String, String) -> OutputStream,
) {
    private val fileSuffix: String = if (moduleId.isEmpty()) "" else "_$moduleId"
    private val nsOpen: String = if (moduleId.isEmpty()) "" else "namespace $moduleId {\n"
    private val nsClose: String = if (moduleId.isEmpty()) "" else "} // namespace $moduleId\n"

    fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo> = emptyList()) {
        val plainFunctions = functions.filter { !it.composable }
        if (classInfos.isEmpty() && plainFunctions.isEmpty() && enums.isEmpty()) return
        enumFqns = enums.map { it.qualifiedName }.toSet()
        enumInfos = enums
        generateCAbiHeader(classInfos, plainFunctions, enums)
        for (info in classInfos) {
            generateProxyHeader(info)
            generateProxyCpp(info, classInfos)
        }
        generateBridge(classInfos, plainFunctions, enums)
    }

    private var enumFqns: Set<String> = emptySet()
    private var enumInfos: List<RdmaEnumInfo> = emptyList()

    private fun isEnumType(typeName: String): Boolean = typeName in enumFqns

    private fun isRdmaClass(typeName: String, allClasses: List<RdmaClassInfo>): Boolean =
        allClasses.any { it.qualifiedName == typeName }

    private fun rdmaClassByName(typeName: String, allClasses: List<RdmaClassInfo>): RdmaClassInfo? =
        allClasses.find { it.qualifiedName == typeName }

    private fun isObjectRef(typeName: String): Boolean =
        CAbi.forMemberType(typeName).isHandle

    private fun cFn(name: String): String = CAbi.prefix(moduleId) + name

    private fun cParamType(p: RdmaParameterInfo): String = when (val ty = p.type.type) {
        is RdmaType.UnitType -> "void"
        is RdmaType.Primitive -> CAbi.forMemberType(ty.fqn).cppType
        // Enums cross as name strings.
        is RdmaType.Ref -> if (isEnumType(ty.fqn)) "const char*" else "void*"
        is RdmaType.ListType -> "void*"
        is RdmaType.MapType -> "void*"
        is RdmaType.FunctionType -> "int64_t"
    }

    private fun cRetType(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.UnitType -> "void"
        is RdmaType.Primitive -> CAbi.forMemberType(ty.fqn).cppType
        is RdmaType.Ref -> if (isEnumType(ty.fqn)) "const char*" else "void*"
        is RdmaType.ListType -> "void*"
        is RdmaType.MapType -> "void*"
        is RdmaType.FunctionType -> "void*"
    }

    /** C type for a class-member type string (enums cross as `const char*`). */
    private fun memberCppType(typeName: String): String =
        if (isEnumType(typeName)) "const char*" else CAbi.forMemberType(typeName).cppType

    // ------------------------------------------------------------ C ABI header

    private fun cWrapper(ret: String, name: String, params: List<Pair<String, String>>): String {
        val decl = params.joinToString(", ") { "${it.first} ${it.second}" }
        val types = params.joinToString(", ") { it.first }
        val args = params.joinToString(", ") { it.second }
        val fp = "(($ret (*)($types))rdma_lookupFunction(\"$name\"))"
        return if (ret == "void") {
            "inline void $name($decl) { $fp($args); }"
        } else {
            "inline $ret $name($decl) { return $fp($args); }"
        }
    }

    private fun generateCAbiHeader(infos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo>) {
        val out = output("RdmaCAbi$fileSuffix.h", "RdmaCAbi$fileSuffix.h").bufferedWriter()
        out.write("""#pragma once
#include <cstdint>
#include "RdmaCAbiRegistry.h"

// Generated C ABI surface for this kernel module: one C function per @RDMA class
// member / top-level function, registered by the Kotlin/Native bridge and called
// through the function-pointer registry. The inline wrappers keep call sites
// unchanged.
""")
        for (info in infos) {
            for (ctor in info.constructors) {
                val params = ctor.parameters.map { memberCppType(it.type) to it.name }
                val withMask = if (ctor.parameters.any { it.hasDefault }) params + ("int32_t" to "mask") else params
                out.write(cWrapper("void*", cFn(info.className + "_new"), withMask) + "\n")
            }
            for (method in info.methods.filter { !it.isSuspend }) {
                val ret = memberCppType(method.returnType)
                val params = buildList {
                    add("void*" to "handle")
                    method.parameters.forEach { add(memberCppType(it.type) to it.name) }
                }
                val withMask = if (method.parameters.any { it.hasDefault }) params + ("int32_t" to "mask") else params
                out.write(cWrapper(ret, cFn(info.className + "_" + method.name), withMask) + "\n")
            }
            for (prop in info.properties) {
                val ret = memberCppType(prop.type)
                out.write(cWrapper(ret, cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() }), listOf("void*" to "handle")) + "\n")
                if (prop.isMutable) {
                    out.write(cWrapper("void", cFn(info.className + "_set" + prop.name.replaceFirstChar { it.uppercase() }), listOf("void*" to "handle", memberCppType(prop.type) to "value")) + "\n")
                }
            }
            for (static in info.statics) {
                val ret = memberCppType(static.type)
                out.write(cWrapper(ret, cFn(info.className + "_get" + static.name.replaceFirstChar { it.uppercase() }), emptyList()) + "\n")
            }
            out.write(cWrapper("void", cFn(info.className + "_setVtable"), listOf("void*" to "handle", "void*" to "vtable")) + "\n")
        }
        for (fn in functions) {
            val ret = cRetType(fn.returnType)
            val params = fn.parameters.map { cParamType(it) to it.name }
            val withMask = if (fn.parameters.any { it.hasDefault }) params + ("int32_t" to "mask") else params
            out.write(cWrapper(ret, cFn(fn.name), withMask) + "\n")
        }
        for (fn in functions.filter { it.suspend }) {
            val params = listOf("int64_t" to "completionId") + fn.parameters.map { cParamType(it) to it.name }
            val withMask = if (fn.parameters.any { it.hasDefault }) params + ("int32_t" to "mask") else params
            out.write(cWrapper("void", cFn(fn.name + "Async"), withMask) + "\n")
        }
        for (info in infos) {
            for (method in info.methods.filter { it.isSuspend }) {
                val params = listOf("int64_t" to "completionId", "void*" to "receiver") +
                    method.parameters.map { memberCppType(it.type) to it.name }
                val withMask = if (method.parameters.any { it.hasDefault }) params + ("int32_t" to "mask") else params
                out.write(cWrapper("void", cFn(info.className + "_" + method.name + "Async"), withMask) + "\n")
            }
        }
        for (e in enumInfos) {
            out.write(cWrapper("int32_t", cFn(e.enumName + "_ordinal"), listOf("const char*" to "entryName")) + "\n")
            out.write(cWrapper("const char*", cFn(e.enumName + "_valueOf"), listOf("const char*" to "entryName")) + "\n")
        }
        out.write(cWrapper("int32_t", cFn("typeIdOf"), listOf("void*" to "handle")) + "\n")
        out.close()
    }

    // ------------------------------------------------------------- proxy files

    private fun generateProxyHeader(info: RdmaClassInfo) {
        val out = output("${info.className}Proxy.h", "${info.className}Proxy.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include <memory>
#include <string>
#include "RdmaVtable.h"
#include "RdmaNativeStateCAbi.h"

namespace facebook {
namespace rdma {
$nsOpen
class ${info.className}NativeState : public RdmaObjectNativeStateCAbi {
public:
    ${info.className}NativeState(void* handle);
    ~${info.className}NativeState() override;
    RdmaVtable* vtable_ = nullptr;
};

void register${info.className}Bridge(jsi::Runtime& rt);
jsi::Object create${info.className}Instance(jsi::Runtime& rt, const jsi::Value* args, size_t count);
jsi::Object create${info.className}Wrapper(jsi::Runtime& rt, void* handle);

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateProxyCpp(info: RdmaClassInfo, allClasses: List<RdmaClassInfo>) {
        val out = output("${info.className}Proxy.cpp", "${info.className}Proxy.cpp").bufferedWriter()
        out.write("""#include "${info.className}Proxy.h"
#include "RdmaCAbi$fileSuffix.h"
#include "RdmaBridge$fileSuffix.h"
#include "RdmaComposeCAbi.h"
#include "RdmaLog.h"
""")
        // Include the other value-type proxies so cross-referencing fields/params
        // (e.g. `Shape.cornerRadius: Dp?`) can call their `create*Wrapper`/`*NativeState`.
        for (other in allClasses) {
            if (other.className != info.className) {
                out.write("#include \"${other.className}Proxy.h\"\n")
            }
        }
        out.write("""
#define LOG_TAG "Rdma${info.className}"

namespace facebook {
namespace rdma {
$nsOpen
${info.className}NativeState::${info.className}NativeState(void* handle)
    : RdmaObjectNativeStateCAbi(handle) {}

${info.className}NativeState::~${info.className}NativeState() {
    delete vtable_;
}

""")
        // Property getters/setters
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            val ret = CAbi.forMemberType(prop.type).cppType
            val body = when {
                prop.isList -> {
                    val elem = prop.listElementType ?: "kotlin.Any"
                    "    void* h = ${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle());\n" +
                        "    return createListHandle(r, h, \"$elem\");\n"
                }
                prop.isMap -> {
                    val key = prop.mapKeyType ?: "kotlin.Any"
                    val value = prop.mapValueType ?: "kotlin.Any"
                    "    void* h = ${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle());\n" +
                        "    return createMapHandle(r, h, \"$key\", \"$value\");\n"
                }
                prop.type == "kotlin.String" -> "    const char* s = ${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle());\n    return jsi::String::createFromUtf8(r, s);\n"
                isEnumType(prop.type) -> "    const char* s = ${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle());\n    return jsi::String::createFromUtf8(r, s);\n"
                prop.type == "kotlin.Int" -> "    return jsi::Value((double)${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle()));\n"
                prop.type == "kotlin.Boolean" -> "    return jsi::Value(${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle()));\n"
                prop.type == "kotlin.Double" || prop.type == "kotlin.Float" -> "    return jsi::Value(${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle()));\n"
                prop.type == "kotlin.Long" -> "    return jsi::Value((double)${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle()));\n"
                else -> "    void* h = ${cFn(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })}(state->handle());\n    return create${rdmaClassByName(prop.type, allClasses)?.className ?: "?"}Wrapper(r, h);\n"
            }
            out.write("""static jsi::Value ${info.className}_$getterName(jsi::Runtime& r, const jsi::Value& thisVal) {
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisVal.asObject(r).getNativeState(r));
$body}

""")
            if (prop.isMutable) {
                val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                val setFn = cFn(info.className + "_set" + prop.name.replaceFirstChar { it.uppercase() })
                val argBlock: String
                val argExpr: String
                when {
                    prop.isList -> {
                        argBlock = "    void* v = nullptr;\n" +
                            "    if (!args[0].isNull() && args[0].isObject()) {\n" +
                            "        auto argObj = args[0].asObject(r);\n" +
                            "        v = materializeList(r, argObj, \"${prop.listElementType ?: ""}\");\n" +
                            "    }\n"
                        argExpr = "v"
                    }
                    prop.isMap -> {
                        argBlock = "    void* v = nullptr;\n" +
                            "    if (!args[0].isNull() && args[0].isObject()) {\n" +
                            "        auto argObj = args[0].asObject(r);\n" +
                            "        v = materializeMap(r, argObj, \"${prop.mapKeyType ?: ""}\", \"${prop.mapValueType ?: ""}\");\n" +
                            "    }\n"
                        argExpr = "v"
                    }
                    prop.type == "kotlin.String" -> { argBlock = ""; argExpr = "args[0].getString(r).utf8(r).c_str()" }
                    isEnumType(prop.type) -> { argBlock = ""; argExpr = "args[0].getString(r).utf8(r).c_str()" }
                    prop.type == "kotlin.Int" -> { argBlock = ""; argExpr = "(int32_t)args[0].getNumber()" }
                    prop.type == "kotlin.Boolean" -> { argBlock = ""; argExpr = "args[0].getBool()" }
                    prop.type == "kotlin.Long" -> { argBlock = ""; argExpr = "(int64_t)args[0].getNumber()" }
                    prop.type == "kotlin.Double" || prop.type == "kotlin.Float" -> { argBlock = ""; argExpr = "args[0].getNumber()" }
                    else -> {
                        argBlock = "    void* v = nullptr;\n" +
                            "    if (!args[0].isNull() && args[0].isObject()) {\n" +
                            "        auto argObj = args[0].asObject(r);\n" +
                            "        auto argState = std::static_pointer_cast<${rdmaClassByName(prop.type, allClasses)?.className?.let { it + "NativeState" } ?: "RdmaObjectNativeStateCAbi"}>(argObj.getNativeState(r));\n" +
                            "        if (argState) v = argState->handle();\n" +
                            "    }\n"
                        argExpr = "v"
                    }
                }
                out.write("""static jsi::Value ${info.className}_$setterName(jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) {
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisVal.asObject(r).getNativeState(r));
$argBlock    $setFn(state->handle(), $argExpr);
    return jsi::Value::undefined();
}

""")
            }
        }

        // Methods
        for (method in info.methods.filter { !it.isSuspend }) {
            val isVoid = CAbi.isVoid(method.returnType)
            val ret = CAbi.forMemberType(method.returnType).cppType
            out.write("""static jsi::Value ${info.className}_${method.name}(jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) {
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisVal.asObject(r).getNativeState(r));
""")
            if (method.isOpen && method.vtableId >= 0) {
                out.write("""    if (state->vtable_ && (size_t)${method.vtableId} < state->vtable_->entries.size() && state->vtable_->entries[${method.vtableId}]) {
        auto& irt = *(jsi::IRuntime*)&r;
        return state->vtable_->entries[${method.vtableId}]->call(irt, args, count);
    }
""")
            }
            // Extract params into C types
            val hasDefaults = method.parameters.any { it.hasDefault }
            if (hasDefaults) out.write("    int32_t mask = 0;\n")
            val argNames = mutableListOf<String>()
            for ((idx, param) in method.parameters.withIndex()) {
                val guarded = param.hasDefault
                val expr: String
                if (param.type == "kotlin.String") {
                    out.write("    std::string cpp_${param.name};\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = args[$idx].getString(r).utf8(r); }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "cpp_${param.name}.c_str()"
                } else if (isEnumType(param.type)) {
                    out.write("    std::string cpp_${param.name};\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(r).utf8(r); }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "cpp_${param.name}.c_str()"
                } else if (param.isList || isObjectRef(param.type)) {
                    out.write("    void* arg_${param.name} = nullptr;\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined() && !args[$idx].isNull() && args[$idx].isObject()) {\n")
                    out.write("        auto argObj = args[$idx].asObject(r);\n")
                    if (param.isList) {
                        out.write("        arg_${param.name} = materializeList(r, argObj, \"${param.listElementType ?: ""}\");\n")
                    } else if (param.isMap) {
                        out.write("        arg_${param.name} = materializeMap(r, argObj, \"${param.mapKeyType ?: ""}\", \"${param.mapValueType ?: ""}\");\n")
                    } else {
                        out.write("        auto argState = std::static_pointer_cast<${rdmaClassByName(param.type, allClasses)?.className?.let { it + "NativeState" } ?: "RdmaObjectNativeStateCAbi"}>(argObj.getNativeState(r));\n")
                        out.write("        if (argState) arg_${param.name} = argState->handle();\n")
                    }
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "arg_${param.name}"
                } else {
                    val cType = when (param.type) {
                        "kotlin.Int" -> "int32_t"
                        "kotlin.Boolean" -> "bool"
                        "kotlin.Long" -> "int64_t"
                        "kotlin.Double", "kotlin.Float" -> "double"
                        else -> "void*"
                    }
                    val zero = when (param.type) {
                        "kotlin.Int" -> "0"
                        "kotlin.Boolean" -> "false"
                        "kotlin.Long" -> "0"
                        else -> "0.0"
                    }
                    out.write("    $cType cpp_${param.name} = $zero;\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = " +
                        when (param.type) {
                            "kotlin.Int" -> "(int32_t)args[$idx].getNumber();"
                            "kotlin.Boolean" -> "args[$idx].getBool();"
                            "kotlin.Long" -> "(int64_t)args[$idx].getNumber();"
                            "kotlin.Double", "kotlin.Float" -> "args[$idx].getNumber();"
                            else -> "nullptr;"
                        } + " }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "cpp_${param.name}"
                }
                argNames.add(expr)
            }
            val callArgs = buildList {
                add("state->handle()")
                addAll(argNames)
                if (hasDefaults) add("mask")
            }.joinToString(", ")
            val call = "${cFn(info.className + "_" + method.name)}($callArgs)"

            if (isVoid) {
                out.write("    $call;\n    checkKotlinError(r);\n    return jsi::Value::undefined();\n")
            } else {
                out.write("    auto jret = $call;\n    checkKotlinError(r);\n")
                val returnExpr = when {
                    method.isList -> "    return createListHandle(r, jret, \"${method.listElementType ?: ""}\");\n"
                    method.isMap -> "    return createMapHandle(r, jret, \"${method.mapKeyType ?: ""}\", \"${method.mapValueType ?: ""}\");\n"
                    method.returnType == "kotlin.String" -> "    return jsi::String::createFromUtf8(r, jret);\n"
                    isEnumType(method.returnType) -> "    return jsi::String::createFromUtf8(r, jret);\n"
                    method.returnType == "kotlin.Int" -> "    return jsi::Value((double)jret);\n"
                    method.returnType == "kotlin.Boolean" -> "    return jsi::Value(jret);\n"
                    method.returnType == "kotlin.Double" || method.returnType == "kotlin.Float" -> "    return jsi::Value(jret);\n"
                    method.returnType == "kotlin.Long" -> "    return jsi::Value((double)jret);\n"
                    else -> {
                        val rdma = rdmaClassByName(method.returnType, allClasses)
                        if (rdma != null) "    return create${rdma.className}Wrapper(r, jret);\n"
                        else "    return wrapUserObject(r, jret);\n"
                    }
                }
                out.write(returnExpr)
            }
            out.write("}\n\n")
        }

        // register bridge
        out.write("""void register${info.className}Bridge(jsi::Runtime& rt) {
""")
        for (method in info.methods.filter { !it.isSuspend }) {
            out.write("""    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "${method.name}"), ${method.parameters.size},
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return ${info.className}_${method.name}(r, thisVal, args, count);
            });
        rt.global().setProperty(rt, "__${info.className}_proto_${method.name}", std::move(fn));
    }
""")
        }
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            out.write("""    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$getterName"), 0,
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return ${info.className}_$getterName(r, thisVal);
            });
        rt.global().setProperty(rt, "__${info.className}_proto_$getterName", std::move(fn));
    }
""")
            if (prop.isMutable) {
                val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                out.write("""    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$setterName"), 1,
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return ${info.className}_$setterName(r, thisVal, args, count);
            });
        rt.global().setProperty(rt, "__${info.className}_proto_$setterName", std::move(fn));
    }
""")
            }
        }
        out.write("}\n\n")

        // create instance
        out.write("""jsi::Object create${info.className}Instance(jsi::Runtime& rt, const jsi::Value* args, size_t count) {
    void* handle = nullptr;
""")
        if (info.constructors.any { it.parameters.any { p -> p.hasDefault } }) {
            out.write("    int32_t mask = 0;\n")
        }
        for (ctor in info.constructors) {
            val hasDefaults = ctor.parameters.any { it.hasDefault }
            val argNames = mutableListOf<String>()
            for ((idx, param) in ctor.parameters.withIndex()) {
                val guarded = param.hasDefault
                val expr: String
                if (param.type == "kotlin.String") {
                    out.write("    std::string cpp_${param.name};\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = args[$idx].getString(rt).utf8(rt); }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "cpp_${param.name}.c_str()"
                } else if (isEnumType(param.type)) {
                    out.write("    std::string cpp_${param.name};\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(rt).utf8(rt); }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "cpp_${param.name}.c_str()"
                } else if (param.isList || isObjectRef(param.type)) {
                    out.write("    void* arg_${param.name} = nullptr;\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined() && !args[$idx].isNull() && args[$idx].isObject()) {\n")
                    out.write("        auto argObj = args[$idx].asObject(rt);\n")
                    if (param.isList) {
                        out.write("        arg_${param.name} = materializeList(rt, argObj, \"${param.listElementType ?: ""}\");\n")
                    } else if (param.isMap) {
                        out.write("        arg_${param.name} = materializeMap(rt, argObj, \"${param.mapKeyType ?: ""}\", \"${param.mapValueType ?: ""}\");\n")
                    } else {
                        out.write("        auto argState = std::static_pointer_cast<${rdmaClassByName(param.type, allClasses)?.className?.let { it + "NativeState" } ?: "RdmaObjectNativeStateCAbi"}>(argObj.getNativeState(rt));\n")
                        out.write("        if (argState) arg_${param.name} = argState->handle();\n")
                    }
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "arg_${param.name}"
                } else {
                    val cType = when (param.type) {
                        "kotlin.Int" -> "int32_t"
                        "kotlin.Boolean" -> "bool"
                        "kotlin.Long" -> "int64_t"
                        "kotlin.Double", "kotlin.Float" -> "double"
                        else -> "void*"
                    }
                    val zero = when (param.type) {
                        "kotlin.Int" -> "0"
                        "kotlin.Boolean" -> "false"
                        "kotlin.Long" -> "0"
                        else -> "0.0"
                    }
                    out.write("    $cType cpp_${param.name} = $zero;\n")
                    out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = " +
                        when (param.type) {
                            "kotlin.Int" -> "(int32_t)args[$idx].getNumber();"
                            "kotlin.Boolean" -> "args[$idx].getBool();"
                            "kotlin.Long" -> "(int64_t)args[$idx].getNumber();"
                            "kotlin.Double", "kotlin.Float" -> "args[$idx].getNumber();"
                            else -> "nullptr;"
                        } + " }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    expr = "cpp_${param.name}"
                }
                argNames.add(expr)
            }
            val callArgs = buildList {
                addAll(argNames)
                if (hasDefaults) add("mask")
            }.joinToString(", ")
            out.write("    handle = ${cFn(info.className + "_new")}($callArgs);\n")
        }
        out.write("""
    auto jsObj = jsi::Object(rt);
    auto nativeState = std::make_shared<${info.className}NativeState>(handle);
    jsObj.setNativeState(rt, nativeState);
""")
        for (method in info.methods.filter { !it.isSuspend }) {
            out.write("    jsObj.setProperty(rt, \"${method.name}\", rt.global().getProperty(rt, \"__${info.className}_proto_${method.name}\"));\n")
        }
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            out.write("    jsObj.setProperty(rt, \"$getterName\", rt.global().getProperty(rt, \"__${info.className}_proto_$getterName\"));\n")
            if (prop.isMutable) {
                val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                out.write("    jsObj.setProperty(rt, \"$setterName\", rt.global().getProperty(rt, \"__${info.className}_proto_$setterName\"));\n")
            }
        }
        out.write("    return jsObj;\n}\n\n")

        // wrapper
        out.write("""jsi::Object create${info.className}Wrapper(jsi::Runtime& rt, void* handle) {
    auto jsObj = jsi::Object(rt);
    auto nativeState = std::make_shared<${info.className}NativeState>(handle);
    jsObj.setNativeState(rt, nativeState);
""")
        for (method in info.methods.filter { !it.isSuspend }) {
            out.write("    jsObj.setProperty(rt, \"${method.name}\", rt.global().getProperty(rt, \"__${info.className}_proto_${method.name}\"));\n")
        }
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            out.write("    jsObj.setProperty(rt, \"$getterName\", rt.global().getProperty(rt, \"__${info.className}_proto_$getterName\"));\n")
        }
        out.write("    return jsObj;\n}\n\n")

        out.write("$nsClose} // namespace rdma\n} // namespace facebook\n")
        out.close()
    }

    // ------------------------------------------------------------------ bridge

    private fun generateBridge(infos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo> = emptyList()) {
        val out = output("RdmaBridge$fileSuffix.h", "RdmaBridge$fileSuffix.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>

namespace facebook {
namespace rdma {
$nsOpen
void installUserBridge(jsi::Runtime& rt, jsi::Object& rdma);
jsi::Value wrapUserObject(jsi::Runtime& rt, void* handle);
jsi::Value createWithOverrides(jsi::Runtime& rt, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides);
jsi::Object createListHandle(jsi::Runtime& rt, void* listHandle, const std::string& elementType);
void* materializeList(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& elementType);
jsi::Object createMapHandle(jsi::Runtime& rt, void* mapHandle, const std::string& keyType, const std::string& valueType);
void* materializeMap(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()

        val cpp = output("RdmaBridge$fileSuffix.cpp", "RdmaBridge$fileSuffix.cpp").bufferedWriter()
        cpp.write("""#include "RdmaBridge$fileSuffix.h"
#include "RdmaCAbi$fileSuffix.h"
#include "RdmaWidgetBridge$fileSuffix.h"
#include "RdmaVtable.h"
#include "RdmaNativeStateCAbi.h"
#include "RdmaLog.h"

#define LOG_TAG "RdmaBridge"

""")
        for (info in infos) {
            cpp.write("#include \"${info.className}Proxy.h\"\n")
        }
        cpp.write("""
namespace facebook {
namespace rdma {
$nsOpen
""")
        for (fn in functions.filter { !it.suspend }) {
            cpp.write(functionImpl(fn, infos))
        }
        for (fn in functions.filter { it.suspend }) {
            cpp.write(suspendFunctionImpl(fn, infos))
        }
        for (info in infos) {
            for (m in info.methods.filter { it.isSuspend }) {
                cpp.write(suspendMethodImpl(info, m, infos))
            }
        }
        for (info in infos) {
            for (static in info.statics) {
                cpp.write(staticImpl(info, static, infos))
            }
        }
        // Enum member bridges: validate through the Kotlin/Native `valueOf`, so an
        // invalid name surfaces as a kernel-side error via checkKotlinError.
        for (e in enums) {
            cpp.write("""
static jsi::Value rdma_enum_${e.enumName}_ordinal(jsi::Runtime& r, const jsi::Value* args, size_t count) {
    if (count < 1) return jsi::Value::undefined();
    std::string name = args[0].isNull() ? "" : args[0].getString(r).utf8(r);
    auto result = ${cFn(e.enumName + "_ordinal")}(name.c_str());
    checkKotlinError(r);
    return jsi::Value((double)result);
}

static jsi::Value rdma_enum_${e.enumName}_valueof(jsi::Runtime& r, const jsi::Value* args, size_t count) {
    if (count < 1) return jsi::Value::undefined();
    std::string name = args[0].isNull() ? "" : args[0].getString(r).utf8(r);
    auto result = ${cFn(e.enumName + "_valueOf")}(name.c_str());
    checkKotlinError(r);
    return jsi::String::createFromUtf8(r, result);
}

""")
        }
        cpp.write("""void installUserBridge(jsi::Runtime& rt, jsi::Object& rdma) {
    LOGI("Initializing RDMA user bridge (C ABI)...");
""")
        for (info in infos) {
            cpp.write("    register${info.className}Bridge(rt);\n")
        }
        for (info in infos) {
            val createFnName = "create${info.className}"
            val paramCount = info.constructors.firstOrNull()?.parameters?.size ?: 0
            cpp.write("""
    {
        auto createFn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$createFnName"), $paramCount,
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return create${info.className}Instance(r, args, count);
            });
        rdma.setProperty(rt, "$createFnName", std::move(createFn));
    }
""")
        }
        for (fn in functions.filter { !it.suspend }) {
            cpp.write(functionRegistration(fn))
        }
        for (fn in functions.filter { it.suspend }) {
            cpp.write(suspendFunctionRegistration(fn))
        }
        for (info in infos) {
            for (m in info.methods.filter { it.isSuspend }) {
                cpp.write(suspendMethodRegistration(info, m))
            }
        }
        for (info in infos) {
            for (static in info.statics) {
                cpp.write(staticRegistration(info, static))
            }
        }
        for (e in enumInfos) {
            val jsBase = e.enumName.replaceFirstChar { it.lowercase() }
            for (suffix in listOf("Ordinal", "ValueOf")) {
                cpp.write("""
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$jsBase$suffix"), 1,
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_enum_${e.enumName}_${suffix.lowercase()}(r, args, count);
            });
        rdma.setProperty(rt, "$jsBase$suffix", std::move(fn));
    }
""")
            }
        }
        cpp.write("    installRdmaWidgetBridge(rt, rdma);\n")
        cpp.write("    LOGI(\"RDMA user bridge installed successfully\");\n}\n\n")

        // createWithOverrides
        cpp.write("""jsi::Value createWithOverrides(jsi::Runtime& rt, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides) {
    size_t argCount = ctorArgs.size(rt);
    std::vector<jsi::Value> argsVec;
    argsVec.reserve(argCount);
    for (size_t i = 0; i < argCount; i++) argsVec.push_back(ctorArgs.getValueAtIndex(rt, i));
""")
        for (info in infos) {
            val openMethodCount = info.methods.count { it.isOpen }
            cpp.write("""
    if (className == "${info.className}") {
        const jsi::Value* argsPtr = argsVec.empty() ? nullptr : argsVec.data();
        auto jsObj = create${info.className}Instance(rt, argsPtr, argCount);
        auto* vt = new RdmaVtable(&rt, ${openMethodCount});
        auto objNames = overrides.getPropertyNames(rt);
        for (size_t i = 0; i < objNames.size(rt); i++) {
            auto name = objNames.getValueAtIndex(rt, i).getString(rt).utf8(rt);
            auto func = overrides.getProperty(rt, name.c_str()).asObject(rt).asFunction(rt);
""")
            for (method in info.methods.filter { it.isOpen }) {
                cpp.write("            if (name == \"${method.name}\") vt->entries[${method.vtableId}] = std::make_shared<jsi::Function>(std::move(func));\n")
            }
            cpp.write("""        }
        auto state = std::static_pointer_cast<${info.className}NativeState>(jsObj.getNativeState(rt));
        ${cFn(info.className + "_setVtable")}(state->handle(), (void*)vt);
        state->vtable_ = vt;
        return jsObj;
    }
""")
        }
        cpp.write("""    return jsi::Value::undefined();
}

jsi::Value wrapUserObject(jsi::Runtime& rt, void* handle) {
    if (!handle) return jsi::Value::null();
    int32_t typeId = ${cFn("typeIdOf")}(handle);
    switch (typeId) {
""")
        infos.forEachIndexed { i, info ->
            cpp.write("        case $i: return create${info.className}Wrapper(rt, handle);\n")
        }
        cpp.write("""        default: return jsi::Value::undefined();
    }
}

jsi::Object createListHandle(jsi::Runtime& rt, void* listHandle, const std::string& elementType) {
    // List handle wraps the Kotlin List via rdma_listSize/rdma_listGet.
    auto host = std::make_shared<ListCAbiHost>(listHandle, elementType);
    return jsi::Object::createFromHostObject(rt, host);
}

void* materializeList(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& elementType) {
    int kind = -1;
    {
        auto rdma = rt.global().getProperty(rt, "RDMA");
        if (rdma.isObject()) {
            auto fn = rdma.asObject(rt).getProperty(rt, "listKind");
            if (fn.isObject() && fn.asObject(rt).isFunction(rt)) {
                auto res = fn.asObject(rt).asFunction(rt).call(rt, jsObj);
                if (res.isNumber()) kind = (int)res.getNumber();
            }
        }
    }
    if (kind == 2) {
        throw jsi::JSError(rt, "unsupported List implementation crossing the runtime boundary; only ArrayList and EmptyList are supported");
    }
    if (kind == 1) return rdma_emptyList();
    // Already a list handle we produced earlier.
    if (jsObj.isHostObject(rt)) {
        auto ns = std::dynamic_pointer_cast<ListCAbiHost>(jsObj.getHostObject(rt));
        if (ns) return ns->listHandle();
    }
    // A plugin-created list morphed into a proxy: reuse the native handle stored
    // in the hidden `__rdma_proxy` property.
    {
        auto p = jsObj.getProperty(rt, "__rdma_proxy");
        if (p.isObject()) {
            auto h = std::dynamic_pointer_cast<ListCAbiHost>(p.asObject(rt).getHostObject(rt));
            if (h) return h->listHandle();
        }
    }
    // Plain JS array.
    if (jsObj.isArray(rt)) {
        auto arr = jsObj.asArray(rt);
        size_t n = arr.size(rt);
        std::vector<void*> handles;
        handles.reserve(n);
        for (size_t i = 0; i < n; i++) {
            handles.push_back(jsiToHandle(rt, arr.getValueAtIndex(rt, i), elementType));
        }
        return rdma_listCreate(handles.data(), (int32_t)handles.size());
    }
    // Kotlin/JS List (ArrayList): wraps a backing JS array in an own field. The
    // field is `array_1` in development output but minified in production, so scan
    // own properties for the first JS array (same as the JNI materializeArray).
    auto names = jsObj.getPropertyNames(rt);
    size_t n = names.size(rt);
    for (size_t i = 0; i < n; i++) {
        auto name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        auto val = jsObj.getProperty(rt, name.c_str());
        if (val.isObject() && val.asObject(rt).isArray(rt)) {
            auto backing = val.asObject(rt).asArray(rt);
            size_t m = backing.size(rt);
            std::vector<void*> handles;
            handles.reserve(m);
            for (size_t j = 0; j < m; j++) {
                handles.push_back(jsiToHandle(rt, backing.getValueAtIndex(rt, j), elementType));
            }
            void* h = rdma_listCreate(handles.data(), (int32_t)handles.size());
            morphListCAbi(rt, jsObj, h, elementType);
            return h;
        }
    }
    return nullptr;
}

jsi::Object createMapHandle(jsi::Runtime& rt, void* mapHandle, const std::string& keyType, const std::string& valueType) {
    auto host = std::make_shared<MapCAbiHost>(mapHandle, keyType, valueType);
    return jsi::Object::createFromHostObject(rt, host);
}

void* materializeMap(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType) {
    int kind = -1;
    {
        auto rdma = rt.global().getProperty(rt, "RDMA");
        if (rdma.isObject()) {
            auto fn = rdma.asObject(rt).getProperty(rt, "mapKind");
            if (fn.isObject() && fn.asObject(rt).isFunction(rt)) {
                auto res = fn.asObject(rt).asFunction(rt).call(rt, jsObj);
                if (res.isNumber()) kind = (int)res.getNumber();
            }
        }
    }
    if (kind == 3) {
        throw jsi::JSError(rt, "unsupported Map implementation crossing the runtime boundary; only LinkedHashMap, HashMap and EmptyMap are supported");
    }
    if (kind == 2) return rdma_emptyMap();
    // Already a map handle we produced earlier.
    if (jsObj.isHostObject(rt)) {
        auto ns = std::dynamic_pointer_cast<MapCAbiHost>(jsObj.getHostObject(rt));
        if (ns) return ns->mapHandle();
    }
    // A plugin-created map morphed into a proxy: reuse the native handle stored
    // in the hidden `__rdma_proxy` property.
    {
        auto p = jsObj.getProperty(rt, "__rdma_proxy");
        if (p.isObject()) {
            auto h = std::dynamic_pointer_cast<MapCAbiHost>(p.asObject(rt).getHostObject(rt));
            if (h) return h->mapHandle();
        }
    }
    void* h = buildMapHandleFromJs(rt, jsObj, keyType, valueType);
    if (h) morphMapCAbi(rt, jsObj, h, keyType, valueType);
    return h;
}

$nsClose} // namespace rdma
} // namespace facebook
""")
        cpp.close()
    }

    private fun functionImpl(fn: RdmaFunctionInfo, allClasses: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        sb.append("""static jsi::Value rdma_fn_${fn.name}(jsi::Runtime& r, const jsi::Value* args, size_t count) {
""")
        val hasDefaults = fn.parameters.any { it.hasDefault }
        if (hasDefaults) sb.append("    int32_t mask = 0;\n")
        val argNames = mutableListOf<String>()
        for ((idx, p) in fn.parameters.withIndex()) {
            val guarded = p.hasDefault
            when (val ty = p.type.type) {
                is RdmaType.Primitive -> {
                    if (ty.fqn == "kotlin.String") {
                        val decl = "std::string cpp_${p.name}"
                        val extract = "cpp_${p.name} = args[$idx].getString(r).utf8(r);"
                        emitParam(sb, idx, "$decl;", extract, guarded, "std::string cpp_${p.name};")
                        argNames.add("cpp_${p.name}.c_str()")
                    } else {
                        val cType = CAbi.forMemberType(ty.fqn).cppType
                        val zero = when (ty.fqn) {
                            "kotlin.Int" -> "0"
                            "kotlin.Long" -> "0"
                            "kotlin.Boolean" -> "false"
                            else -> "0.0"
                        }
                        val expr = when (ty.fqn) {
                            "kotlin.Int" -> "(int32_t)args[$idx].getNumber()"
                            "kotlin.Boolean" -> "args[$idx].getBool()"
                            "kotlin.Long" -> "(int64_t)args[$idx].getNumber()"
                            else -> "args[$idx].getNumber()"
                        }
                        val decl = "$cType cpp_${p.name}"
                        val extract = "cpp_${p.name} = $expr;"
                        emitParam(sb, idx, "$decl = $zero;", extract, guarded, "$decl = $zero;")
                        argNames.add("cpp_${p.name}")
                    }
                }
                is RdmaType.Ref -> {
                    if (isEnumType(ty.fqn)) {
                        val decl = "std::string cpp_${p.name}"
                        val extract = "cpp_${p.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(r).utf8(r);"
                        emitParam(sb, idx, "$decl;", extract, guarded, "$decl;")
                        argNames.add("cpp_${p.name}.c_str()")
                    } else {
                        sb.append("    void* arg_${p.name} = nullptr;\n")
                        sb.append("    if (!args[$idx].isUndefined() && !args[$idx].isNull() && args[$idx].isObject()) {\n")
                        sb.append("        auto argState = std::static_pointer_cast<${rdmaClassByName(ty.fqn, allClasses)?.className?.let { it + "NativeState" } ?: "RdmaObjectNativeStateCAbi"}>(args[$idx].asObject(r).getNativeState(r));\n")
                        sb.append("        if (argState) arg_${p.name} = argState->handle();\n")
                        sb.append("    }\n")
                        if (guarded) sb.append("    if (args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                        argNames.add("arg_${p.name}")
                    }
                }
                is RdmaType.ListType -> {
                    sb.append("    void* arg_${p.name} = nullptr;\n")
                    sb.append("    if (!args[$idx].isUndefined() && !args[$idx].isNull() && args[$idx].isObject()) {\n")
                    sb.append("        auto argObj_${p.name} = args[$idx].asObject(r);\n")
                    sb.append("        arg_${p.name} = materializeList(r, argObj_${p.name}, \"${typeName(ty.element)}\");\n")
                    sb.append("    }\n")
                    if (guarded) sb.append("    if (args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    argNames.add("arg_${p.name}")
                }
                is RdmaType.MapType -> {
                    sb.append("    void* arg_${p.name} = nullptr;\n")
                    sb.append("    if (!args[$idx].isUndefined() && !args[$idx].isNull() && args[$idx].isObject()) {\n")
                    sb.append("        auto argObj_${p.name} = args[$idx].asObject(r);\n")
                    sb.append("        arg_${p.name} = materializeMap(r, argObj_${p.name}, \"${typeName(ty.key)}\", \"${typeName(ty.value)}\");\n")
                    sb.append("    }\n")
                    if (guarded) sb.append("    if (args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    argNames.add("arg_${p.name}")
                }
                is RdmaType.FunctionType -> {
                    val decl = "int64_t cpp_${p.name}"
                    val extract = "cpp_${p.name} = (int64_t)args[$idx].getNumber();"
                    emitParam(sb, idx, "$decl = 0;", extract, guarded, "$decl = 0;")
                    argNames.add("cpp_${p.name}")
                }
                is RdmaType.UnitType -> {}
            }
        }
        val callArgs = buildList {
            addAll(argNames)
            if (hasDefaults) add("mask")
        }.joinToString(", ")
        val call = "${cFn(fn.name)}($callArgs)"
        when (val ty = fn.returnType.type) {
            is RdmaType.UnitType -> sb.append("    $call;\n    checkKotlinError(r);\n    return jsi::Value::undefined();\n")
            is RdmaType.Primitive -> {
                sb.append("    auto jret = $call;\n    checkKotlinError(r);\n")
                sb.append("    return ${primitiveReturn(ty.fqn, "jret")}\n")
            }
            is RdmaType.Ref -> {
                if (isEnumType(ty.fqn)) {
                    sb.append("    const char* jret = $call;\n    checkKotlinError(r);\n")
                    sb.append("    return jsi::String::createFromUtf8(r, jret);\n")
                } else {
                    val rdma = rdmaClassByName(ty.fqn, allClasses)
                    sb.append("    void* jret = $call;\n    checkKotlinError(r);\n")
                    if (rdma != null) sb.append("    return create${rdma.className}Wrapper(r, jret);\n")
                    else sb.append("    return wrapUserObject(r, jret);\n")
                }
            }
            is RdmaType.ListType -> {
                sb.append("    void* jret = $call;\n    checkKotlinError(r);\n")
                sb.append("    return createListHandle(r, jret, \"${typeName(ty.element)}\");\n")
            }
            is RdmaType.MapType -> {
                sb.append("    void* jret = $call;\n    checkKotlinError(r);\n")
                sb.append("    return createMapHandle(r, jret, \"${typeName(ty.key)}\", \"${typeName(ty.value)}\");\n")
            }
            is RdmaType.FunctionType -> sb.append("    return wrapUserObject(r, $call);\n")
        }
        sb.append("}\n\n")
        return sb.toString()
    }

    /**
     * Emits a scalar parameter extraction, guarded by `isUndefined()` when [guarded]. For a
     * guarded parameter the declaration is emitted with a zero placeholder and the extraction
     * only runs when the argument is present; otherwise the mask bit is set.
     */
    private fun emitParam(sb: StringBuilder, idx: Int, decl: String, extract: String, guarded: Boolean, guardedDecl: String) {
        if (guarded) {
            sb.append("    $guardedDecl\n")
            sb.append("    if (!args[$idx].isUndefined()) {\n")
            sb.append("        $extract\n")
            sb.append("    } else {\n")
            sb.append("        mask |= (1 << $idx);\n")
            sb.append("    }\n")
        } else {
            // A required parameter must still be extracted: `decl` is the zero-init placeholder
            // and `extract` is the full `cpp_x = args[idx]…;` assignment.
            sb.append("    $decl\n")
            sb.append("    $extract\n")
        }
    }

    private fun primitiveReturn(fqn: String, expr: String): String = when (fqn) {
        "kotlin.String" -> "jsi::String::createFromUtf8(r, $expr);"
        "kotlin.Int" -> "jsi::Value((double)$expr);"
        "kotlin.Boolean" -> "jsi::Value($expr);"
        "kotlin.Long" -> "jsi::Value((double)$expr);"
        else -> "jsi::Value($expr);"
    }

    private fun typeName(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.Primitive -> ty.fqn
        is RdmaType.Ref -> ty.fqn
        is RdmaType.ListType -> "list:" + typeName(ty.element)
        is RdmaType.MapType -> "map:" + typeName(ty.key) + ":" + typeName(ty.value)
        is RdmaType.UnitType -> "kotlin.Unit"
        is RdmaType.FunctionType -> "kotlin.Any"
    }

    private fun functionRegistration(fn: RdmaFunctionInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "${fn.name}"), ${fn.parameters.size},
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_fn_${fn.name}(r, args, count);
            });
        rdma.setProperty(rt, "${fn.name}", std::move(fn));
    }
"""

    // ---------------------------------------------------------------- suspend

    private fun suspendFunctionImpl(fn: RdmaFunctionInfo, allClasses: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        val hasDefaults = fn.parameters.any { it.hasDefault }
        sb.append("""static jsi::Value rdma_suspend_${fn.name}(jsi::Runtime& r, const jsi::Value* args, size_t count) {
    int64_t completionId = (int64_t)args[0].getNumber();
    rdmaRegisterSuspendResultType(completionId, "${typeName(fn.returnType)}");
""")
        if (hasDefaults) sb.append("    int32_t mask = 0;\n")
        val argNames = mutableListOf<String>()
        fn.parameters.forEachIndexed { i, p ->
            suspendFunctionParamExtraction(sb, p, i + 1, i, allClasses, argNames)
        }
        val callArgs = buildList {
            addAll(argNames)
            if (hasDefaults) add("mask")
        }
        val suffix = if (callArgs.isEmpty()) "completionId" else "completionId, ${callArgs.joinToString(", ")}"
        sb.append("    ${cFn(fn.name + "Async")}($suffix);\n")
        sb.append("    return jsi::Value::undefined();\n}\n\n")
        return sb.toString()
    }

    private fun suspendMethodImpl(cls: RdmaClassInfo, m: MethodInfo, allClasses: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        val hasDefaults = m.parameters.any { it.hasDefault }
        sb.append("""static jsi::Value rdma_suspend_${cls.className}_${m.name}(jsi::Runtime& r, const jsi::Value* args, size_t count) {
    int64_t completionId = (int64_t)args[0].getNumber();
    rdmaRegisterSuspendResultType(completionId, "${methodTypeDesc(m)}");
    void* receiver = nullptr;
    if (!args[1].isNull() && args[1].isObject()) {
        auto recvState = std::static_pointer_cast<RdmaObjectNativeStateCAbi>(args[1].asObject(r).getNativeState(r));
        if (recvState) receiver = recvState->handle();
    }
""")
        if (hasDefaults) sb.append("    int32_t mask = 0;\n")
        val argNames = mutableListOf<String>()
        m.parameters.forEachIndexed { i, p ->
            suspendMethodParamExtraction(sb, p, i + 2, i, allClasses, argNames)
        }
        val callArgs = buildList {
            addAll(argNames)
            if (hasDefaults) add("mask")
        }
        val suffix = if (callArgs.isEmpty()) "completionId, receiver" else "completionId, receiver, ${callArgs.joinToString(", ")}"
        sb.append("    ${cFn(cls.className + "_" + m.name + "Async")}($suffix);\n")
        sb.append("    return jsi::Value::undefined();\n}\n\n")
        return sb.toString()
    }

    private fun suspendFunctionParamExtraction(sb: StringBuilder, p: RdmaParameterInfo, argIdx: Int, maskBit: Int, allClasses: List<RdmaClassInfo>, argNames: MutableList<String>) {
        val guarded = p.hasDefault
        fun guard() { if (guarded) sb.append("    if (args[$argIdx].isUndefined()) mask |= (1 << $maskBit);\n") }
        when (val ty = p.type.type) {
            is RdmaType.Primitive -> {
                if (ty.fqn == "kotlin.String") {
                    sb.append("    std::string cpp_${p.name};\n")
                    sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = args[$argIdx].getString(r).utf8(r);\n")
                    guard()
                    argNames.add("cpp_${p.name}.c_str()")
                } else {
                    val cType = CAbi.forMemberType(ty.fqn).cppType
                    val zero = when (ty.fqn) {
                        "kotlin.Int" -> "0"
                        "kotlin.Long" -> "0"
                        "kotlin.Boolean" -> "false"
                        else -> "0.0"
                    }
                    val expr = when (ty.fqn) {
                        "kotlin.Int" -> "(int32_t)args[$argIdx].getNumber()"
                        "kotlin.Boolean" -> "args[$argIdx].getBool()"
                        "kotlin.Long" -> "(int64_t)args[$argIdx].getNumber()"
                        else -> "args[$argIdx].getNumber()"
                    }
                    sb.append("    $cType cpp_${p.name} = $zero;\n")
                    sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = $expr;\n")
                    guard()
                    argNames.add("cpp_${p.name}")
                }
            }
            is RdmaType.Ref -> {
                if (isEnumType(ty.fqn)) {
                    sb.append("    std::string cpp_${p.name};\n")
                    sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = args[$argIdx].isNull() ? \"\" : args[$argIdx].getString(r).utf8(r);\n")
                    guard()
                    argNames.add("cpp_${p.name}.c_str()")
                } else {
                    sb.append("    void* arg_${p.name} = nullptr;\n")
                    sb.append("    if (!args[$argIdx].isUndefined() && !args[$argIdx].isNull() && args[$argIdx].isObject()) {\n")
                    sb.append("        auto argState = std::static_pointer_cast<${rdmaClassByName(ty.fqn, allClasses)?.className?.let { it + "NativeState" } ?: "RdmaObjectNativeStateCAbi"}>(args[$argIdx].asObject(r).getNativeState(r));\n")
                    sb.append("        if (argState) arg_${p.name} = argState->handle();\n")
                    sb.append("    }\n")
                    guard()
                    argNames.add("arg_${p.name}")
                }
            }
            is RdmaType.ListType -> {
                sb.append("    void* arg_${p.name} = nullptr;\n")
                sb.append("    if (!args[$argIdx].isUndefined() && !args[$argIdx].isNull() && args[$argIdx].isObject()) {\n")
                sb.append("        auto argObj_${p.name} = args[$argIdx].asObject(r);\n")
                sb.append("        arg_${p.name} = materializeList(r, argObj_${p.name}, \"${typeName(ty.element)}\");\n")
                sb.append("    }\n")
                guard()
                argNames.add("arg_${p.name}")
            }
            is RdmaType.MapType -> {
                sb.append("    void* arg_${p.name} = nullptr;\n")
                sb.append("    if (!args[$argIdx].isUndefined() && !args[$argIdx].isNull() && args[$argIdx].isObject()) {\n")
                sb.append("        auto argObj_${p.name} = args[$argIdx].asObject(r);\n")
                sb.append("        arg_${p.name} = materializeMap(r, argObj_${p.name}, \"${typeName(ty.key)}\", \"${typeName(ty.value)}\");\n")
                sb.append("    }\n")
                guard()
                argNames.add("arg_${p.name}")
            }
            is RdmaType.FunctionType -> {
                sb.append("    int64_t cpp_${p.name} = 0;\n")
                sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = (int64_t)args[$argIdx].getNumber();\n")
                guard()
                argNames.add("cpp_${p.name}")
            }
            is RdmaType.UnitType -> {}
        }
    }

    private fun suspendMethodParamExtraction(sb: StringBuilder, p: ParameterInfo, argIdx: Int, maskBit: Int, allClasses: List<RdmaClassInfo>, argNames: MutableList<String>) {
        val guarded = p.hasDefault
        fun guard() { if (guarded) sb.append("    if (args[$argIdx].isUndefined()) mask |= (1 << $maskBit);\n") }
        if (p.type == "kotlin.String") {
            sb.append("    std::string cpp_${p.name};\n")
            sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = args[$argIdx].getString(r).utf8(r);\n")
            guard()
            argNames.add("cpp_${p.name}.c_str()")
        } else if (isEnumType(p.type)) {
            sb.append("    std::string cpp_${p.name};\n")
            sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = args[$argIdx].isNull() ? \"\" : args[$argIdx].getString(r).utf8(r);\n")
            guard()
            argNames.add("cpp_${p.name}.c_str()")
        } else if (CAbi.isPrimitive(p.type)) {
            val cType = CAbi.forMemberType(p.type).cppType
            val zero = when (p.type) {
                "kotlin.Int" -> "0"
                "kotlin.Long" -> "0"
                "kotlin.Boolean" -> "false"
                else -> "0.0"
            }
            val expr = when (p.type) {
                "kotlin.Int" -> "(int32_t)args[$argIdx].getNumber()"
                "kotlin.Boolean" -> "args[$argIdx].getBool()"
                "kotlin.Long" -> "(int64_t)args[$argIdx].getNumber()"
                else -> "args[$argIdx].getNumber()"
            }
            sb.append("    $cType cpp_${p.name} = $zero;\n")
            sb.append("    if (!args[$argIdx].isUndefined()) cpp_${p.name} = $expr;\n")
            guard()
            argNames.add("cpp_${p.name}")
        } else if (p.isList) {
            sb.append("    void* arg_${p.name} = nullptr;\n")
            sb.append("    if (!args[$argIdx].isUndefined() && !args[$argIdx].isNull() && args[$argIdx].isObject()) {\n")
            sb.append("        auto argObj_${p.name} = args[$argIdx].asObject(r);\n")
            sb.append("        arg_${p.name} = materializeList(r, argObj_${p.name}, \"${p.listElementType ?: "kotlin.Any"}\");\n")
            sb.append("    }\n")
            guard()
            argNames.add("arg_${p.name}")
        } else if (p.isMap) {
            sb.append("    void* arg_${p.name} = nullptr;\n")
            sb.append("    if (!args[$argIdx].isUndefined() && !args[$argIdx].isNull() && args[$argIdx].isObject()) {\n")
            sb.append("        auto argObj_${p.name} = args[$argIdx].asObject(r);\n")
            sb.append("        arg_${p.name} = materializeMap(r, argObj_${p.name}, \"${p.mapKeyType ?: "kotlin.Any"}\", \"${p.mapValueType ?: "kotlin.Any"}\");\n")
            sb.append("    }\n")
            guard()
            argNames.add("arg_${p.name}")
        } else {
            sb.append("    void* arg_${p.name} = nullptr;\n")
            sb.append("    if (!args[$argIdx].isUndefined() && !args[$argIdx].isNull() && args[$argIdx].isObject()) {\n")
            sb.append("        auto argState = std::static_pointer_cast<RdmaObjectNativeStateCAbi>(args[$argIdx].asObject(r).getNativeState(r));\n")
            sb.append("        if (argState) arg_${p.name} = argState->handle();\n")
            sb.append("    }\n")
            guard()
            argNames.add("arg_${p.name}")
        }
    }

    private fun suspendFunctionRegistration(fn: RdmaFunctionInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "_${fn.name}Async"), ${fn.parameters.size + 1},
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_suspend_${fn.name}(r, args, count);
            });
        rdma.setProperty(rt, "_${fn.name}Async", std::move(fn));
    }
"""

    private fun suspendMethodRegistration(cls: RdmaClassInfo, m: MethodInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "_${cls.className}_${m.name}Async"), ${m.parameters.size + 2},
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_suspend_${cls.className}_${m.name}(r, args, count);
            });
        rdma.setProperty(rt, "_${cls.className}_${m.name}Async", std::move(fn));
    }
"""

    private fun methodTypeDesc(m: MethodInfo): String = when {
        m.isList -> "list:${m.listElementType ?: "kotlin.Any"}"
        m.isMap -> "map:${m.mapKeyType ?: "kotlin.Any"}:${m.mapValueType ?: "kotlin.Any"}"
        else -> m.returnType
    }

    private fun staticJsName(info: RdmaClassInfo, static: StaticInfo): String =
        info.className.replaceFirstChar { it.lowercase() } + static.name.replaceFirstChar { it.uppercase() }

    private fun staticImpl(info: RdmaClassInfo, static: StaticInfo, allClasses: List<RdmaClassInfo>): String {
        val call = "${cFn(info.className + "_get" + static.name.replaceFirstChar { it.uppercase() })}()"
        val body = when {
            static.type == "kotlin.String" -> "    auto jret = $call;\n    checkKotlinError(r);\n    return jsi::String::createFromUtf8(r, jret);\n"
            isEnumType(static.type) -> "    auto jret = $call;\n    checkKotlinError(r);\n    return jsi::String::createFromUtf8(r, jret);\n"
            static.type == "kotlin.Int" -> "    auto jret = $call;\n    checkKotlinError(r);\n    return jsi::Value((double)jret);\n"
            static.type == "kotlin.Boolean" -> "    auto jret = $call;\n    checkKotlinError(r);\n    return jsi::Value(jret);\n"
            static.type == "kotlin.Long" -> "    auto jret = $call;\n    checkKotlinError(r);\n    return jsi::Value((double)jret);\n"
            isObjectRef(static.type) -> {
                val rdma = rdmaClassByName(static.type, allClasses)
                if (rdma != null) "    void* jret = $call;\n    checkKotlinError(r);\n    return create${rdma.className}Wrapper(r, jret);\n"
                else "    void* jret = $call;\n    checkKotlinError(r);\n    return wrapUserObject(r, jret);\n"
            }
            else -> "    auto jret = $call;\n    checkKotlinError(r);\n    return jsi::Value(jret);\n"
        }
        return """
static jsi::Value rdma_static_${info.className}_${static.name}(jsi::Runtime& r) {
$body}
"""
    }

    private fun staticRegistration(info: RdmaClassInfo, static: StaticInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "${staticJsName(info, static)}"), 0,
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_static_${info.className}_${static.name}(r);
            });
        rdma.setProperty(rt, "${staticJsName(info, static)}", std::move(fn));
    }
"""
}
