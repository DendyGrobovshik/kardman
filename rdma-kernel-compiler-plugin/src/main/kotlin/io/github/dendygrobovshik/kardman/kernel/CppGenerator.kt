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
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.StaticInfo
import io.github.dendygrobovshik.kardman.types.RdmaEnumInfo
import java.io.OutputStream

class CppGenerator(
    private val moduleId: String = "",
    private val kernelPackage: String = "",
    private val output: (String, String) -> OutputStream,
) {

    /** Suffix appended to module-level generated file names to avoid collisions across kernel modules. */
    private val fileSuffix: String = if (moduleId.isEmpty()) "" else "_$moduleId"

    /** Inner C++ namespace wrapping this module's generated glue (empty = unnamespaced). */
    private val nsOpen: String = if (moduleId.isEmpty()) "" else "namespace $moduleId {\n"
    private val nsClose: String = if (moduleId.isEmpty()) "" else "} // namespace $moduleId\n"

    fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo> = emptyList(), enums: List<RdmaEnumInfo> = emptyList()) {
        val plainFunctions = functions.filter { !it.composable }
        if (classInfos.isEmpty() && plainFunctions.isEmpty() && enums.isEmpty()) return
        enumFqns = enums.map { it.qualifiedName }.toSet()
        generateJniCacheHeader(classInfos, plainFunctions, enums)
        generateJniCacheCpp(classInfos, plainFunctions, enums)
        for (info in classInfos) {
            generateProxyHeader(info)
            generateProxyCpp(info, classInfos)
        }
        generateBridge(classInfos, plainFunctions, enums)
    }

    private var enumFqns: Set<String> = emptySet()

    private fun isEnumType(fqn: String): Boolean = fqn in enumFqns

    private fun isRdmaClass(typeName: String, allClasses: List<RdmaClassInfo>): Boolean {
        return allClasses.any { it.qualifiedName == typeName }
    }

    private fun rdmaClassByName(typeName: String, allClasses: List<RdmaClassInfo>): RdmaClassInfo? {
        return allClasses.find { it.qualifiedName == typeName }
    }

    /**
     * Structural classification: a type that crosses the boundary as an @RDMA object
     * handle (as opposed to a primitive/Unit, a List, or a function type). Used instead
     * of `isRdmaClass` so that a module can marshal @RDMA types declared in *other*
     * kernel modules without knowing their concrete class.
     */
    private fun isObjectRef(typeName: String): Boolean =
        JniTypeMapper.forType(typeName) == null && !typeName.startsWith("kotlin.Function")

    private fun jniSignature(type: RdmaTypeRef): String = when (val t = type.type) {
        is RdmaType.UnitType -> "V"
        is RdmaType.Primitive -> JniTypeMapper.jniSignature(t.fqn)
        is RdmaType.Ref -> "L${t.fqn.replace('.', '/')};"
        is RdmaType.ListType -> "Ljava/util/List;"
        is RdmaType.MapType -> "Ljava/util/Map;"
        is RdmaType.FunctionType -> "Lkotlin/jvm/functions/Function${t.parameters.size};"
    }

    private fun jniReturnSignature(type: RdmaTypeRef): String = jniSignature(type)

    /** FQN of the generated suspend-wrapper facade (`<pkg>.RdmaSuspendBridgeKt`), or null if none. */
    private fun suspendFacade(infos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>): String? {
        val pkg = kernelPackage.ifEmpty {
            infos.firstOrNull()?.packageName
                ?: functions.firstOrNull { it.suspend }?.facadeClass?.substringBeforeLast('.')
                ?: functions.firstOrNull()?.facadeClass?.substringBeforeLast('.')
        }
        return pkg?.let { "$it.RdmaSuspendBridgeKt" }
    }

    private fun generateJniCacheHeader(infos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo>) {
        val out = output("RdmaJniCache$fileSuffix.h", "RdmaJniCache$fileSuffix.h").bufferedWriter()
        out.write("""#pragma once
#include <jni.h>
#include <string>
#include <vector>

namespace facebook {
namespace rdma {
$nsOpen
struct RdmaJniCache {
    JavaVM* jvm = nullptr;
    // Shared java.lang.Enum cache: `name()`/`ordinal()` on any enum entry.
    jclass enumClazz = nullptr;
    jmethodID enumName = nullptr;
    jmethodID enumOrdinal = nullptr;
""")
        for (info in infos) {
            out.write("""
    struct ${info.className}Cache {
        jclass clazz = nullptr;
        jmethodID constructor = nullptr;
""")
            if (info.statics.isNotEmpty()) {
                out.write("        jclass companionClazz = nullptr;\n")
                out.write("        jfieldID companionField = nullptr;\n")
            }
            for (method in info.methods.filter { !it.isSuspend }) {
                out.write("        jmethodID method_${method.name} = nullptr;\n")
            }
            for (prop in info.properties) {
                out.write("        jmethodID getter_${prop.name} = nullptr;\n")
                if (prop.isMutable) {
                    out.write("        jmethodID setter_${prop.name} = nullptr;\n")
                }
            }
            for (static in info.statics) {
                out.write("        jmethodID static_get_${static.name} = nullptr;\n")
            }
            out.write("    };\n")
            out.write("    ${info.className}Cache ${info.className.lowercase()}_cache;\n")
        }
        for (fn in functions) {
            if (fn.suspend) continue
            out.write("    jclass fn_${fn.name}_clazz = nullptr;\n")
            out.write("    jmethodID fn_${fn.name}_method = nullptr;\n")
        }
        val hasDefaulted = functions.any { !it.suspend && it.parameters.any { p -> p.hasDefault } } ||
            infos.any { c -> c.constructors.any { it.parameters.any { p -> p.hasDefault } } || c.methods.any { m -> !m.isSuspend && m.parameters.any { p -> p.hasDefault } } }
        if (hasDefaulted) {
            out.write("    jclass wrappersClazz = nullptr;\n")
            for (fn in functions.filter { !it.suspend && it.parameters.any { p -> p.hasDefault } }) {
                out.write("    jmethodID fn_${fn.name}_rdma = nullptr;\n")
            }
            for (info in infos) {
                if (info.constructors.any { it.parameters.any { p -> p.hasDefault } }) {
                    out.write("    jmethodID ctor_${info.className}_rdma = nullptr;\n")
                }
                for (method in info.methods.filter { !it.isSuspend && it.parameters.any { p -> p.hasDefault } }) {
                    out.write("    jmethodID method_${info.className}_${method.name}_rdma = nullptr;\n")
                }
            }
        }
        for (e in enums) {
            out.write("    jclass enum_${e.enumName}_clazz = nullptr;\n")
            out.write("    jmethodID enum_${e.enumName}_valueOf = nullptr;\n")
        }
        val suspendFns = functions.filter { it.suspend }
        val suspendMethods = infos.flatMap { cls -> cls.methods.filter { it.isSuspend }.map { cls to it } }
        if (suspendFns.isNotEmpty() || suspendMethods.isNotEmpty()) {
            out.write("    jclass suspendClazz = nullptr;\n")
            for (fn in suspendFns) {
                out.write("    jmethodID suspend_fn_${fn.name}Async = nullptr;\n")
            }
            for ((cls, m) in suspendMethods) {
                out.write("    jmethodID suspend_${cls.className}_${m.name}Async = nullptr;\n")
            }
        }
        out.write("};\n\nextern RdmaJniCache g_rdmaCache;\n\nvoid initJniCache(JNIEnv* env);\n")
        out.write("$nsClose} // namespace rdma\n} // namespace facebook\n")
        out.close()
    }

    private fun generateJniCacheCpp(infos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo>) {
        val out = output("RdmaJniCache$fileSuffix.cpp", "RdmaJniCache$fileSuffix.cpp").bufferedWriter()
        out.write("""#include "RdmaJniCache$fileSuffix.h"

namespace facebook {
namespace rdma {
$nsOpen
RdmaJniCache g_rdmaCache;

void initJniCache(JNIEnv* env) {
    {
        jclass localEnum = env->FindClass("java/lang/Enum");
        if (localEnum) {
            g_rdmaCache.enumClazz = (jclass)env->NewGlobalRef(localEnum);
            g_rdmaCache.enumName = env->GetMethodID(g_rdmaCache.enumClazz, "name", "()Ljava/lang/String;");
            g_rdmaCache.enumOrdinal = env->GetMethodID(g_rdmaCache.enumClazz, "ordinal", "()I");
            env->DeleteLocalRef(localEnum);
        }
    }
""")
        for (e in enums) {
            val jniEnumName = e.qualifiedName.replace('.', '/')
            val valueOfSig = "(Ljava/lang/String;)L$jniEnumName;"
            out.write("""
    {
        jclass localEnumCls = env->FindClass("$jniEnumName");
        if (localEnumCls) {
            g_rdmaCache.enum_${e.enumName}_clazz = (jclass)env->NewGlobalRef(localEnumCls);
            g_rdmaCache.enum_${e.enumName}_valueOf = env->GetStaticMethodID(g_rdmaCache.enum_${e.enumName}_clazz, "valueOf", "$valueOfSig");
            env->DeleteLocalRef(localEnumCls);
        }
    }
""")
        }
        for (info in infos) {
            val jniClassName = info.qualifiedName.replace('.', '/')
            val cacheVar = "${info.className.lowercase()}_cache"
            out.write("""
    {
        jclass localCls = env->FindClass("$jniClassName");
        g_rdmaCache.$cacheVar.clazz = (jclass)env->NewGlobalRef(localCls);
        env->DeleteLocalRef(localCls);
""")
            if (info.statics.isNotEmpty()) {
                val companionJni = "$jniClassName\$Companion"
                out.write("""
        jclass companionLocal = env->FindClass("$companionJni");
        g_rdmaCache.$cacheVar.companionClazz = (jclass)env->NewGlobalRef(companionLocal);
        env->DeleteLocalRef(companionLocal);
        g_rdmaCache.$cacheVar.companionField = env->GetStaticFieldID(g_rdmaCache.$cacheVar.clazz, "Companion", "L$companionJni;");
""")
            }
            for (ctor in info.constructors) {
                val paramSig = ctor.parameters.joinToString("") {
                    JniTypeMapper.jniSignature(it.type)
                }
                out.write("        g_rdmaCache.$cacheVar.constructor = env->GetMethodID(g_rdmaCache.$cacheVar.clazz, \"<init>\", \"($paramSig)V\");\n")
            }
            for (method in info.methods.filter { !it.isSuspend }) {
                val paramSig = method.parameters.joinToString("") {
                    JniTypeMapper.jniSignature(it.type)
                }
                val retSig = JniTypeMapper.jniSignature(method.returnType)
                out.write("        g_rdmaCache.$cacheVar.method_${method.name} = env->GetMethodID(g_rdmaCache.$cacheVar.clazz, \"${method.name}\", \"($paramSig)$retSig\");\n")
            }
            for (prop in info.properties) {
                val retSig = JniTypeMapper.jniSignature(prop.type)
                val getterName = if (prop.type == "kotlin.Boolean") "is${prop.name.replaceFirstChar { it.uppercase() }}" else "get${prop.name.replaceFirstChar { it.uppercase() }}"
                out.write("        g_rdmaCache.$cacheVar.getter_${prop.name} = env->GetMethodID(g_rdmaCache.$cacheVar.clazz, \"$getterName\", \"()$retSig\");\n")
                if (prop.isMutable) {
                    val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                    val setSig = JniTypeMapper.jniSignature(prop.type)
                    out.write("        g_rdmaCache.$cacheVar.setter_${prop.name} = env->GetMethodID(g_rdmaCache.$cacheVar.clazz, \"$setterName\", \"($setSig)V\");\n")
                }
            }
            for (static in info.statics) {
                val retSig = JniTypeMapper.jniSignature(static.type)
                val getterName = if (static.type == "kotlin.Boolean") "is${static.name.replaceFirstChar { it.uppercase() }}" else "get${static.name.replaceFirstChar { it.uppercase() }}"
                out.write("        g_rdmaCache.$cacheVar.static_get_${static.name} = env->GetMethodID(g_rdmaCache.$cacheVar.companionClazz, \"$getterName\", \"()$retSig\");\n")
            }
            out.write("    }\n")
        }
        for (fn in functions) {
            if (fn.suspend) continue
            val facade = fn.facadeClass.replace('.', '/')
            val paramSig = fn.parameters.joinToString("") { jniSignature(it.type) }
            val retSig = jniReturnSignature(fn.returnType)
            out.write("""
    {
        jclass localCls = env->FindClass("$facade");
        g_rdmaCache.fn_${fn.name}_clazz = (jclass)env->NewGlobalRef(localCls);
        env->DeleteLocalRef(localCls);
        g_rdmaCache.fn_${fn.name}_method = env->GetStaticMethodID(g_rdmaCache.fn_${fn.name}_clazz, "${fn.name}", "($paramSig)$retSig");
    }
""")
        }
        val suspendFns = functions.filter { it.suspend }
        val suspendMethods = infos.flatMap { cls -> cls.methods.filter { it.isSuspend }.map { cls to it } }
        val defaultedFns = functions.filter { !it.suspend && it.parameters.any { p -> p.hasDefault } }
        val defaultedCtorInfos = infos.filter { it.constructors.any { c -> c.parameters.any { p -> p.hasDefault } } }
        val defaultedMethods = infos.flatMap { cls -> cls.methods.filter { !it.isSuspend && it.parameters.any { p -> p.hasDefault } }.map { cls to it } }
        if (defaultedFns.isNotEmpty() || defaultedCtorInfos.isNotEmpty() || defaultedMethods.isNotEmpty()) {
            val wrappersFacade = "$kernelPackage.RdmaDefaultWrappersKt".replace('.', '/')
            out.write("""
    {
        jclass localWrappers = env->FindClass("$wrappersFacade");
        g_rdmaCache.wrappersClazz = (jclass)env->NewGlobalRef(localWrappers);
        env->DeleteLocalRef(localWrappers);
    }
""")
            for (fn in defaultedFns) {
                val paramSig = fn.parameters.joinToString("") { jniSignature(it.type) } + "I"
                val retSig = jniReturnSignature(fn.returnType)
                out.write("    g_rdmaCache.fn_${fn.name}_rdma = env->GetStaticMethodID(g_rdmaCache.wrappersClazz, \"${fn.name}_rdma_default\", \"($paramSig)$retSig\");\n")
            }
            for (info in defaultedCtorInfos) {
                val ctor = info.constructors.first { it.parameters.any { p -> p.hasDefault } }
                val paramSig = ctor.parameters.joinToString("") { JniTypeMapper.jniSignature(it.type) } + "I"
                val retSig = "L${info.qualifiedName.replace('.', '/')};"
                out.write("    g_rdmaCache.ctor_${info.className}_rdma = env->GetStaticMethodID(g_rdmaCache.wrappersClazz, \"${info.className}_new_rdma_default\", \"($paramSig)$retSig\");\n")
            }
            for ((cls, m) in defaultedMethods) {
                val receiverSig = "L${cls.qualifiedName.replace('.', '/')};"
                val paramSig = receiverSig + m.parameters.joinToString("") { JniTypeMapper.jniSignature(it.type) } + "I"
                val retSig = JniTypeMapper.jniSignature(m.returnType)
                out.write("    g_rdmaCache.method_${cls.className}_${m.name}_rdma = env->GetStaticMethodID(g_rdmaCache.wrappersClazz, \"${cls.className}_${m.name}_rdma_default\", \"($paramSig)$retSig\");\n")
            }
        }
        if (suspendFns.isNotEmpty() || suspendMethods.isNotEmpty()) {
            suspendFacade(infos, functions)?.let { facade ->
                val facadePath = facade.replace('.', '/')
                out.write("""
    {
        jclass localCls = env->FindClass("$facadePath");
        g_rdmaCache.suspendClazz = (jclass)env->NewGlobalRef(localCls);
        env->DeleteLocalRef(localCls);
    }
""")
                for (fn in suspendFns) {
                    val paramSig = fn.parameters.joinToString("") { jniSignature(it.type) }
                    val maskSig = if (fn.parameters.any { it.hasDefault }) "I" else ""
                    out.write("    g_rdmaCache.suspend_fn_${fn.name}Async = env->GetStaticMethodID(g_rdmaCache.suspendClazz, \"_${fn.name}Async\", \"(J$paramSig$maskSig)V\");\n")
                }
                for ((cls, m) in suspendMethods) {
                    val receiverSig = "L${cls.qualifiedName.replace('.', '/')};"
                    val paramSig = m.parameters.joinToString("") { JniTypeMapper.jniSignature(it.type) }
                    val maskSig = if (m.parameters.any { it.hasDefault }) "I" else ""
                    out.write("    g_rdmaCache.suspend_${cls.className}_${m.name}Async = env->GetStaticMethodID(g_rdmaCache.suspendClazz, \"_${cls.className}_${m.name}Async\", \"(J$receiverSig$paramSig$maskSig)V\");\n")
                }
            }
        }
        out.write("}\n")
        out.write("$nsClose} // namespace rdma\n} // namespace facebook\n")
        out.close()
    }

    private fun generateProxyHeader(info: RdmaClassInfo) {
        val out = output("${info.className}Proxy.h", "${info.className}Proxy.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <memory>
#include <string>
#include "RdmaVtable.h"
#include "RdmaNativeState.h"

namespace facebook {
namespace rdma {
$nsOpen
class ${info.className}NativeState : public RdmaObjectNativeState {
public:
    ${info.className}NativeState(JavaVM* jvm, jobject globalRef);
    ~${info.className}NativeState() override;
    RdmaVtable* vtable_ = nullptr;
};

void register${info.className}Bridge(jsi::Runtime& rt, JavaVM* jvm);
jsi::Object create${info.className}Instance(jsi::Runtime& rt, JavaVM* jvm, const jsi::Value* args, size_t count);
jsi::Object create${info.className}Wrapper(jsi::Runtime& rt, JavaVM* jvm, jobject globalObj);

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateProxyCpp(info: RdmaClassInfo, allClasses: List<RdmaClassInfo>) {
        val out = output("${info.className}Proxy.cpp", "${info.className}Proxy.cpp").bufferedWriter()
        val cacheVar = "${info.className.lowercase()}_cache"

        out.write("""#include "${info.className}Proxy.h"
#include "RdmaJniCache$fileSuffix.h"
#include "ListHandle.h"
#include "MapHandle.h"
#include "RdmaCompose.h"
""")
        // Include headers for @RDMA types used as parameters or return values
        val referencedRdmaTypes = mutableSetOf<String>()
        for (ctor in info.constructors) {
            for (param in ctor.parameters) {
                if (isRdmaClass(param.type, allClasses) && param.type != info.qualifiedName) {
                    referencedRdmaTypes.add(rdmaClassByName(param.type, allClasses)!!.className)
                }
            }
        }
        for (method in info.methods) {
            for (param in method.parameters) {
                if (isRdmaClass(param.type, allClasses) && param.type != info.qualifiedName) {
                    referencedRdmaTypes.add(rdmaClassByName(param.type, allClasses)!!.className)
                }
            }
            if (isRdmaClass(method.returnType, allClasses) && method.returnType != info.qualifiedName) {
                referencedRdmaTypes.add(rdmaClassByName(method.returnType, allClasses)!!.className)
            }
        }
        for (rdmaName in referencedRdmaTypes) {
            out.write("#include \"${rdmaName}Proxy.h\"\n")
        }

        out.write("""
#include <android/log.h>

#define LOG_TAG "Rdma${info.className}"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {
$nsOpen""")
        // Forward-declare wrapper functions for @RDMA return types
        for (method in info.methods) {
            if (isRdmaClass(method.returnType, allClasses)) {
                val rdma = rdmaClassByName(method.returnType, allClasses)!!
                out.write("jsi::Object create${rdma.className}Wrapper(jsi::Runtime& rt, JavaVM* jvm, jobject globalObj);\n")
            }
        }

        out.write("""
${info.className}NativeState::${info.className}NativeState(JavaVM* jvm, jobject globalRef)
    : RdmaObjectNativeState(jvm, globalRef) {}

${info.className}NativeState::~${info.className}NativeState() {
    delete vtable_;
}
""")

        for (prop in info.properties) {
            val retType = JniTypeMapper.forType(prop.type)
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            val returnExpr = when {
                prop.isList -> {
                    val nullCheck = if (prop.nullable) "if (jret == nullptr) return jsi::Value::null(); " else ""
                    val elemType = prop.listElementType ?: "kotlin.Any"
                    "auto jret = env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}); $nullCheck" +
                        "jobject globalRet = env->NewGlobalRef(jret); env->DeleteLocalRef(jret); " +
                        "return createListHandle(r, jvm, globalRet, \"$elemType\");"
                }
                prop.isMap -> {
                    val nullCheck = if (prop.nullable) "if (jret == nullptr) return jsi::Value::null(); " else ""
                    val keyType = prop.mapKeyType ?: "kotlin.Any"
                    val valueType = prop.mapValueType ?: "kotlin.Any"
                    "auto jret = env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}); $nullCheck" +
                        "jobject globalRet = env->NewGlobalRef(jret); env->DeleteLocalRef(jret); " +
                        "return createMapHandle(r, jvm, globalRet, \"$keyType\", \"$valueType\");"
                }
                prop.type == "kotlin.String" -> {
                    "auto jstr = (jstring)env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}); auto cstr = env->GetStringUTFChars(jstr, nullptr); auto result = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jstr, cstr); env->DeleteLocalRef(jstr); return result;"
                }
                isEnumType(prop.type) -> {
                    val nullCheck = if (prop.nullable) "if (jret == nullptr) return jsi::Value::null(); " else ""
                    "auto jret = env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}); $nullCheck" +
                        "auto jname = (jstring)env->CallObjectMethod(jret, g_rdmaCache.enumName); auto cstr = env->GetStringUTFChars(jname, nullptr); auto result = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jname, cstr); env->DeleteLocalRef(jname); env->DeleteLocalRef(jret); return result;"
                }
                prop.type == "kotlin.Int" -> "return jsi::Value((double)env->CallIntMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                prop.type == "kotlin.Boolean" -> "return jsi::Value(env->CallBooleanMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                prop.type == "kotlin.Double" || prop.type == "kotlin.Float" -> "return jsi::Value(env->CallDoubleMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                prop.type == "kotlin.Long" -> "return jsi::Value((double)env->CallLongMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                else -> "return jsi::Value::undefined();"
            }
            out.write("""
static jsi::Value ${info.className}_${getterName}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value& thisVal) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
    auto thisObj = thisVal.asObject(r);
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisObj.getNativeState(r));
    $returnExpr
}
""")
            if (prop.isMutable) {
                val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                val callExpr = when {
                    prop.isList -> {
                        val elemType = prop.listElementType ?: "kotlin.Any"
                        "auto listObj = args[0].asObject(r); jobject argList = materializeArray(env, r, jvm, listObj, \"$elemType\"); env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, argList);"
                    }
                    prop.isMap -> {
                        val keyType = prop.mapKeyType ?: "kotlin.Any"
                        val valueType = prop.mapValueType ?: "kotlin.Any"
                        "auto mapObj = args[0].asObject(r); jobject argMap = materializeMap(env, r, jvm, mapObj, \"$keyType\", \"$valueType\"); env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, argMap);"
                    }
                    prop.type == "kotlin.Int" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, (jint)args[0].getNumber());"
                    prop.type == "kotlin.Boolean" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, args[0].getBool());"
                    prop.type == "kotlin.Double" || prop.type == "kotlin.Float" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, args[0].getNumber());"
                    prop.type == "kotlin.Long" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, (jlong)args[0].getNumber());"
                    prop.type == "kotlin.String" -> "auto jstr = env->NewStringUTF(args[0].getString(r).utf8(r).c_str()); env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, jstr); env->DeleteLocalRef(jstr);"
                    isEnumType(prop.type) -> {
                        val enumName = prop.type.substringAfterLast('.')
                        "std::string cpp_val = args[0].isNull() ? \"\" : args[0].getString(r).utf8(r); jstring jstr = env->NewStringUTF(cpp_val.c_str()); jobject entry = env->CallStaticObjectMethod(g_rdmaCache.enum_${enumName}_clazz, g_rdmaCache.enum_${enumName}_valueOf, jstr); env->DeleteLocalRef(jstr); checkAndThrowJs(env, r); env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, entry);"
                    }
                    else -> ""
                }
                out.write("""
static jsi::Value ${info.className}_${setterName}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value& thisVal, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
    auto thisObj = thisVal.asObject(r);
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisObj.getNativeState(r));
    $callExpr
    return jsi::Value::undefined();
}
""")
            }
        }

        for (method in info.methods.filter { !it.isSuspend }) {
            val paramCount = method.parameters.size
            val returnType = JniTypeMapper.forType(method.returnType)
            val isVoid = JniTypeMapper.isVoid(method.returnType)
            val hasDefaults = method.parameters.any { it.hasDefault }

            out.write("""
static jsi::Value ${info.className}_${method.name}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value& thisVal, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
    auto thisObj = thisVal.asObject(r);
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisObj.getNativeState(r));
""")
            if (method.isOpen && method.vtableId >= 0) {
                out.write("""    if (state->vtable_ && (size_t)${method.vtableId} < state->vtable_->entries.size() && state->vtable_->entries[${method.vtableId}]) {
        auto& irt = *(jsi::IRuntime*)&r;
        return state->vtable_->entries[${method.vtableId}]->call(irt, args, count);
    }
""")
            }
            out.write("""
""")
            if (hasDefaults) {
                out.write("    int mask = 0;\n")
            }
            for (param in method.parameters) {
                val type = JniTypeMapper.forType(param.type)
                val idx = method.parameters.indexOf(param)
                val guarded = param.hasDefault
                if (type != null) {
                    if (param.type == "kotlin.String") {
                        // String crosses as std::string; the method body uses `r` (not `rt`).
                        if (guarded) {
                            out.write("    std::string cpp_${param.name};\n")
                            out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(r).utf8(r); } else { mask |= (1 << $idx); }\n")
                        } else if (param.nullable) {
                            out.write("    std::string cpp_${param.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(r).utf8(r);\n")
                        } else {
                            out.write("    std::string cpp_${param.name} = args[$idx].getString(r).utf8(r);\n")
                        }
                    } else if (guarded) {
                        val zero = when (param.type) {
                            "kotlin.Int" -> "0"
                            "kotlin.Long" -> "0"
                            "kotlin.Boolean" -> "false"
                            else -> "0.0"
                        }
                        out.write("    ${type.cppType} cpp_${param.name} = $zero;\n")
                        out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = ${type.fromJsi.replace("%d", idx.toString())}; } else { mask |= (1 << $idx); }\n")
                    } else if (param.nullable) {
                        out.write("    ${type.cppType} cpp_${param.name} = args[$idx].isNull() ? ${type.cppType}() : (${type.fromJsi.replace("%d", idx.toString())});\n")
                    } else {
                        val extractExpr = type.fromJsi.replace("%d", idx.toString())
                        out.write("    ${type.cppType} cpp_${param.name} = $extractExpr;\n")
                    }
                } else if (param.isList) {
                    out.write("    jobject arg_${param.name} = nullptr;\n")
                    out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                    out.write("        auto listObj = args[$idx].asObject(r);\n")
                    out.write("        if (listObj.hasNativeState(r)) {\n")
                    out.write("            auto ns = listObj.getNativeState(r);\n")
                    out.write("            if (ns) arg_${param.name} = *(jobject*)((char*)ns.get() + 16);\n")
                    out.write("        } else {\n")
                    out.write("            arg_${param.name} = materializeArray(env, r, jvm, listObj, \"${param.listElementType ?: ""}\");\n")
                    out.write("        }\n")
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                } else if (param.isMap) {
                    out.write("    jobject arg_${param.name} = nullptr;\n")
                    out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                    out.write("        auto mapObj = args[$idx].asObject(r);\n")
                    out.write("        if (mapObj.hasNativeState(r)) {\n")
                    out.write("            auto ns = mapObj.getNativeState(r);\n")
                    out.write("            if (ns) arg_${param.name} = *(jobject*)((char*)ns.get() + 16);\n")
                    out.write("        } else {\n")
                    out.write("            arg_${param.name} = materializeMap(env, r, jvm, mapObj, \"${param.mapKeyType ?: ""}\", \"${param.mapValueType ?: ""}\");\n")
                    out.write("        }\n")
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                } else if (isEnumType(param.type)) {
                    val enumName = param.type.substringAfterLast('.')
                    out.write("    jobject arg_${param.name} = nullptr;\n")
                    out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                    out.write("        std::string cpp_${param.name} = args[$idx].getString(r).utf8(r);\n")
                    out.write("        jstring j_${param.name} = env->NewStringUTF(cpp_${param.name}.c_str());\n")
                    out.write("        arg_${param.name} = env->CallStaticObjectMethod(g_rdmaCache.enum_${enumName}_clazz, g_rdmaCache.enum_${enumName}_valueOf, j_${param.name});\n")
                    out.write("        env->DeleteLocalRef(j_${param.name});\n")
                    out.write("        checkAndThrowJs(env, r);\n")
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                } else if (isObjectRef(param.type)) {
                    if (param.nullable) {
                        out.write("    jobject arg_${param.name} = nullptr;\n")
                        out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                        out.write("        auto argObj_${param.name} = args[$idx].asObject(r);\n")
                        out.write("        auto argState_${param.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${param.name}.getNativeState(r));\n")
                        out.write("        arg_${param.name} = argState_${param.name}->getObject();\n")
                        out.write("    }\n")
                        if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    } else {
                        out.write("    jobject arg_${param.name} = nullptr;\n")
                        out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                        out.write("        auto argObj_${param.name} = args[$idx].asObject(r);\n")
                        out.write("        auto argState_${param.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${param.name}.getNativeState(r));\n")
                        out.write("        arg_${param.name} = argState_${param.name}->getObject();\n")
                        out.write("    }\n")
                        if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    }
                }
            }
            for (param in method.parameters) {
                if (param.type == "kotlin.String") {
                    if (param.nullable) {
                        out.write("    jstring j_${param.name} = args[${method.parameters.indexOf(param)}].isNull() ? nullptr : env->NewStringUTF(cpp_${param.name}.c_str());\n")
                    } else {
                        out.write("    jstring j_${param.name} = env->NewStringUTF(cpp_${param.name}.c_str());\n")
                    }
                }
            }

            val paramsStr = method.parameters.joinToString(", ") { param ->
                when (param.type) {
                    "kotlin.String" -> "j_${param.name}"
                    "kotlin.Int" -> "(jint)cpp_${param.name}"
                    "kotlin.Boolean" -> "(jboolean)cpp_${param.name}"
                    "kotlin.Double", "kotlin.Float" -> "(jdouble)cpp_${param.name}"
                    "kotlin.Long" -> "(jlong)cpp_${param.name}"
                    else -> when {
                        param.isList -> "arg_${param.name}"
                        param.isMap -> "arg_${param.name}"
                        isObjectRef(param.type) -> "arg_${param.name}"
                        else -> "nullptr"
                    }
                }
            }
            // For a defaulted method the C++ calls the generated static wrapper
            // `<Class>_<method>_rdma_default(self, params..., mask)` instead of the instance method.
            val paramsCall = if (hasDefaults) {
                val base = if (paramsStr.isNotEmpty()) "$paramsStr, " else ""
                ", ${base}mask"
            } else if (paramsStr.isNotEmpty()) ", $paramsStr" else ""
            val methodField = if (hasDefaults) "method_${info.className}_${method.name}_rdma" else "method_${method.name}"

            if (isVoid) {
                val call = if (hasDefaults) {
                    "env->CallStaticVoidMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);"
                } else {
                    "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                }
                out.write("    $call\n")
                out.write("    checkAndThrowJs(env, r);\n")
                for (param in method.parameters) {
                    if (param.type == "kotlin.String") {
                        out.write("    env->DeleteLocalRef(j_${param.name});\n")
                    }
                }
                out.write("    return jsi::Value::undefined();\n")
            } else {
                val callExpr = when (method.returnType) {
                    "kotlin.String" -> if (hasDefaults) "auto jret = (jstring)env->CallStaticObjectMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);" else "auto jret = (jstring)env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                    "kotlin.Int" -> if (hasDefaults) "auto result = env->CallStaticIntMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);" else "auto result = env->CallIntMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                    "kotlin.Boolean" -> if (hasDefaults) "auto result = env->CallStaticBooleanMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);" else "auto result = env->CallBooleanMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                    "kotlin.Double", "kotlin.Float" -> if (hasDefaults) "auto result = env->CallStaticDoubleMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);" else "auto result = env->CallDoubleMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                    "kotlin.Long" -> if (hasDefaults) "auto result = env->CallStaticLongMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);" else "auto result = env->CallLongMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                    else -> if (isObjectRef(method.returnType) || method.isList || method.isMap) {
                        if (hasDefaults) "auto jret = env->CallStaticObjectMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.$methodField, state->getObject()$paramsCall);" else "auto jret = env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.$methodField$paramsCall);"
                    } else ""
                }
                out.write("    $callExpr\n")
                out.write("    checkAndThrowJs(env, r);\n")
                if (method.nullableReturn) {
                    out.write("    if (jret == nullptr) return jsi::Value::null();\n")
                }
                for (param in method.parameters) {
                    if (param.type == "kotlin.String") {
                        out.write("    env->DeleteLocalRef(j_${param.name});\n")
                    }
                }
                when (method.returnType) {
                    "kotlin.String" -> out.write("    auto cstr = env->GetStringUTFChars(jret, nullptr); auto ret = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jret, cstr); env->DeleteLocalRef(jret); return ret;\n")
                    "kotlin.Int" -> out.write("    return jsi::Value((double)result);\n")
                    "kotlin.Boolean" -> out.write("    return jsi::Value(result);\n")
                    "kotlin.Double", "kotlin.Float" -> out.write("    return jsi::Value(result);\n")
                    "kotlin.Long" -> out.write("    return jsi::Value((double)result);\n")
                    else -> {
                        if (method.isList) {
                            out.write("    if (jret != nullptr) {\n")
                            out.write("        jobject globalRet = env->NewGlobalRef(jret);\n")
                            out.write("        env->DeleteLocalRef(jret);\n")
                            out.write("        return createListHandle(r, jvm, globalRet, \"${method.listElementType ?: ""}\");\n")
                            out.write("    }\n")
                            out.write("    return jsi::Value::null();\n")
                        } else if (method.isMap) {
                            out.write("    if (jret != nullptr) {\n")
                            out.write("        jobject globalRet = env->NewGlobalRef(jret);\n")
                            out.write("        env->DeleteLocalRef(jret);\n")
                            out.write("        return createMapHandle(r, jvm, globalRet, \"${method.mapKeyType ?: ""}\", \"${method.mapValueType ?: ""}\");\n")
                            out.write("    }\n")
                            out.write("    return jsi::Value::null();\n")
                        } else if (isEnumType(method.returnType)) {
                            out.write("    auto jname = (jstring)env->CallObjectMethod(jret, g_rdmaCache.enumName);\n")
                            out.write("    auto cstr = env->GetStringUTFChars(jname, nullptr); auto ret = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jname, cstr); env->DeleteLocalRef(jname);\n")
                            out.write("    env->DeleteLocalRef(jret);\n")
                            out.write("    return ret;\n")
                        } else {
                            val rdma = rdmaClassByName(method.returnType, allClasses)
                            if (rdma != null) {
                                out.write("    jobject globalRet = env->NewGlobalRef(jret);\n")
                                out.write("    env->DeleteLocalRef(jret);\n")
                                out.write("    return create${rdma.className}Wrapper(r, jvm, globalRet);\n")
                            } else {
                                out.write("    auto ret = wrapAny(r, jvm, jret);\n")
                                out.write("    env->DeleteLocalRef(jret);\n")
                                out.write("    return ret;\n")
                            }
                        }
                    }
                }
            }
            out.write("}\n")
        }

        out.write("""
void register${info.className}Bridge(jsi::Runtime& rt, JavaVM* jvm) {
""")
        for (method in info.methods.filter { !it.isSuspend }) {
            val paramCount = method.parameters.size
            out.write("""
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "${method.name}"), $paramCount,
            [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return ${info.className}_${method.name}(r, jvm, thisVal, args, count);
            }
        );
        rt.global().setProperty(rt, "__${info.className}_proto_${method.name}", std::move(fn));
    }
""")
        }
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            out.write("""
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$getterName"), 0,
            [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return ${info.className}_${getterName}(r, jvm, thisVal);
            }
        );
        rt.global().setProperty(rt, "__${info.className}_proto_${getterName}", std::move(fn));
    }
""")
            if (prop.isMutable) {
                val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                out.write("""
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$setterName"), 1,
            [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return ${info.className}_${setterName}(r, jvm, thisVal, args, count);
            }
        );
        rt.global().setProperty(rt, "__${info.className}_proto_${setterName}", std::move(fn));
    }
""")
            }
        }
        out.write("}\n\n")

        out.write("""jsi::Object create${info.className}Instance(jsi::Runtime& rt, JavaVM* jvm, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Object(rt);

""")
        for (ctor in info.constructors) {
            val hasDefaults = ctor.parameters.any { it.hasDefault }
            if (hasDefaults) out.write("    int mask = 0;\n")
            var idx = 0
            for (param in ctor.parameters) {
                val type = JniTypeMapper.forType(param.type)
                val guarded = param.hasDefault
                if (type != null) {
                    if (guarded) {
                        val zero = when (param.type) {
                            "kotlin.Int" -> "0"
                            "kotlin.Long" -> "0"
                            "kotlin.Boolean" -> "false"
                            else -> "0.0"
                        }
                        val extract = type.fromJsi.replace("%d", idx.toString())
                        out.write("    ${type.cppType} cpp_${param.name} = $zero;\n")
                        out.write("    if (count > $idx && !args[$idx].isUndefined()) { cpp_${param.name} = $extract; } else { mask |= (1 << $idx); }\n")
                    } else {
                        val extract = type.fromJsi.replace("%d", idx.toString())
                        out.write("    ${type.cppType} cpp_${param.name} = $extract;\n")
                    }
                } else if (param.isList) {
                    val elemType = param.listElementType ?: "kotlin.Any"
                    out.write("    jobject arg_${param.name} = nullptr;\n")
                    out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                    out.write("        auto listObj_${param.name} = args[$idx].asObject(rt);\n")
                    out.write("        if (listObj_${param.name}.hasNativeState(rt)) {\n")
                    out.write("            auto ns_${param.name} = listObj_${param.name}.getNativeState(rt);\n")
                    out.write("            if (ns_${param.name}) arg_${param.name} = *(jobject*)((char*)ns_${param.name}.get() + 16);\n")
                    out.write("        } else {\n")
                    out.write("            arg_${param.name} = materializeArray(env, rt, jvm, listObj_${param.name}, \"$elemType\");\n")
                    out.write("        }\n")
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                } else if (param.isMap) {
                    val keyType = param.mapKeyType ?: "kotlin.Any"
                    val valueType = param.mapValueType ?: "kotlin.Any"
                    out.write("    jobject arg_${param.name} = nullptr;\n")
                    out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                    out.write("        auto mapObj_${param.name} = args[$idx].asObject(rt);\n")
                    out.write("        if (mapObj_${param.name}.hasNativeState(rt)) {\n")
                    out.write("            auto ns_${param.name} = mapObj_${param.name}.getNativeState(rt);\n")
                    out.write("            if (ns_${param.name}) arg_${param.name} = *(jobject*)((char*)ns_${param.name}.get() + 16);\n")
                    out.write("        } else {\n")
                    out.write("            arg_${param.name} = materializeMap(env, rt, jvm, mapObj_${param.name}, \"$keyType\", \"$valueType\");\n")
                    out.write("        }\n")
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                } else if (isEnumType(param.type)) {
                    val enumName = param.type.substringAfterLast('.')
                    out.write("    jobject arg_${param.name} = nullptr;\n")
                    out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull() && args[$idx].isString()) {\n")
                    out.write("        std::string cpp_${param.name} = args[$idx].getString(rt).utf8(rt);\n")
                    out.write("        jstring j_${param.name} = env->NewStringUTF(cpp_${param.name}.c_str());\n")
                    out.write("        arg_${param.name} = env->CallStaticObjectMethod(g_rdmaCache.enum_${enumName}_clazz, g_rdmaCache.enum_${enumName}_valueOf, j_${param.name});\n")
                    out.write("        env->DeleteLocalRef(j_${param.name});\n")
                    out.write("        checkAndThrowJs(env, rt);\n")
                    out.write("    }\n")
                    if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                } else if (isObjectRef(param.type)) {
                    if (param.nullable) {
                        out.write("    jobject arg_${param.name} = nullptr;\n")
                        out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull() && args[$idx].isObject() && args[$idx].asObject(rt).hasNativeState(rt)) {\n")
                        out.write("        auto argObj_${param.name} = args[$idx].asObject(rt);\n")
                        out.write("        auto argState_${param.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${param.name}.getNativeState(rt));\n")
                        out.write("        if (argState_${param.name}) arg_${param.name} = argState_${param.name}->getObject();\n")
                        out.write("    }\n")
                        if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    } else {
                        out.write("    jobject arg_${param.name} = nullptr;\n")
                        out.write("    if (${if (guarded) "count > $idx && !args[$idx].isUndefined() && " else ""}!args[$idx].isNull()) {\n")
                        out.write("        auto argObj_${param.name} = args[$idx].asObject(rt);\n")
                        out.write("        auto argState_${param.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${param.name}.getNativeState(rt));\n")
                        out.write("        arg_${param.name} = argState_${param.name}->getObject();\n")
                        out.write("    }\n")
                        if (guarded) out.write("    if (count <= $idx || args[$idx].isUndefined()) mask |= (1 << $idx);\n")
                    }
                }
                idx++
            }
            for (param in ctor.parameters) {
                if (param.type == "kotlin.String") {
                    out.write("    jstring j_${param.name} = env->NewStringUTF(cpp_${param.name}.c_str());\n")
                }
            }
            val params = ctor.parameters.joinToString(", ") { param ->
                when (param.type) {
                    "kotlin.String" -> "j_${param.name}"
                    "kotlin.Int" -> "(jint)cpp_${param.name}"
                    "kotlin.Boolean" -> "(jboolean)cpp_${param.name}"
                    "kotlin.Double", "kotlin.Float" -> "(jdouble)cpp_${param.name}"
                    "kotlin.Long" -> "(jlong)cpp_${param.name}"
                    else -> when {
                        param.isList -> "arg_${param.name}"
                        param.isMap -> "arg_${param.name}"
                        isObjectRef(param.type) -> "arg_${param.name}"
                        else -> "nullptr"
                    }
                }
            }
            if (hasDefaults) {
                val callArgs = if (params.isEmpty()) "mask" else "$params, mask"
                out.write("    jobject localObj = env->CallStaticObjectMethod(g_rdmaCache.wrappersClazz, g_rdmaCache.ctor_${info.className}_rdma, $callArgs);\n")
            } else {
                out.write("    jobject localObj = env->NewObject(g_rdmaCache.${cacheVar}.clazz, g_rdmaCache.${cacheVar}.constructor${if (params.isNotEmpty()) ", $params" else ""});\n")
            }
            for (param in ctor.parameters) {
                if (param.type == "kotlin.String") {
                    out.write("    env->DeleteLocalRef(j_${param.name});\n")
                }
            }
            out.write("    jobject globalObj = env->NewGlobalRef(localObj);\n")
            out.write("    env->DeleteLocalRef(localObj);\n\n")
        }
        out.write("    auto jsObj = jsi::Object(rt);\n")
        out.write("    auto nativeState = std::make_shared<${info.className}NativeState>(jvm, globalObj);\n")
        out.write("    jsObj.setNativeState(rt, nativeState);\n")
        out.write("""
    // Copy prototype methods onto instance
""")
        for (method in info.methods.filter { !it.isSuspend }) {
            out.write("    jsObj.setProperty(rt, \"${method.name}\", rt.global().getProperty(rt, \"__${info.className}_proto_${method.name}\"));\n")
        }
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            out.write("    jsObj.setProperty(rt, \"$getterName\", rt.global().getProperty(rt, \"__${info.className}_proto_${getterName}\"));\n")
            if (prop.isMutable) {
                val setterName = "set${prop.name.replaceFirstChar { it.uppercase() }}"
                out.write("    jsObj.setProperty(rt, \"$setterName\", rt.global().getProperty(rt, \"__${info.className}_proto_${setterName}\"));\n")
            }
        }
        out.write("    return jsObj;\n}\n\n")

        // Wrapper: creates JSI object around existing jobject (for @RDMA return values)
        out.write("""jsi::Object create${info.className}Wrapper(jsi::Runtime& rt, JavaVM* jvm, jobject globalObj) {
    auto jsObj = jsi::Object(rt);
    auto nativeState = std::make_shared<${info.className}NativeState>(jvm, globalObj);
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

    private fun generateBridge(infos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo> = emptyList()) {
        val out = output("RdmaBridge$fileSuffix.h", "RdmaBridge$fileSuffix.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include <jni.h>

namespace facebook {
namespace rdma {
$nsOpen
// Installed into the shared `RDMA` namespace by the generic runtime, via the
// user-bridge hook (see RdmaCompose.h / rdmaSetUserBridgeInstaller).
void installUserBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma);

// Initializes all JNI caches (FindClass/GetMethodID) for the user bridge on the
// UI thread. Registered via rdmaSetUserBridgeJniInit and invoked from
// initRdmaComposeJniCache() before the Hermes thread starts.
void initUserBridgeJniCaches(JNIEnv* env);

// Wraps an existing JVM object into a JSI proxy by class name. Registered via
// rdmaSetObjectWrapper and invoked by the generic runtime to marshal @RDMA
// objects returned through service-lambda arguments and List elements. Takes
// ownership of the passed jobject (converts it to a global ref).
jsi::Value wrapUserObject(jsi::Runtime& rt, JavaVM* jvm, jobject obj);

// Creates a subclass instance for the given class name, dispatching to this
// module's `create<X>Instance`. Returns `undefined` if the class does not belong
// to this module (the aggregate bridge chains these across modules).
jsi::Value createWithOverrides(jsi::Runtime& rt, JavaVM* jvm, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides);

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()

        val cpp = output("RdmaBridge$fileSuffix.cpp", "RdmaBridge$fileSuffix.cpp").bufferedWriter()
        cpp.write("""#include "RdmaBridge$fileSuffix.h"
#include "RdmaJniCache$fileSuffix.h"
#include "RdmaVtable.h"
#include "ListHandle.h"
#include "MapHandle.h"
#include "RdmaCompose.h"
#include "RdmaWidgetBridge$fileSuffix.h"
""")
        for (info in infos) {
            cpp.write("#include \"${info.className}Proxy.h\"\n")
        }
        cpp.write("""
#include <android/log.h>

#define LOG_TAG "RdmaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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
        cpp.write("""
void initUserBridgeJniCaches(JNIEnv* env) {
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    g_rdmaCache.jvm = jvm;
    initJniCache(env);
    initWidgetJniCache(env);
}

${enumHostFunctions(enums)}

void installUserBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma) {
    LOGI("Initializing RDMA user bridge...");

""")
        for (info in infos) {
            cpp.write("    register${info.className}Bridge(rt, jvm);\n")
        }
        for (info in infos) {
            val createFnName = "create${info.className}"
            val paramCount = info.constructors.firstOrNull()?.parameters?.size ?: 0
            cpp.write("""
    {
        auto createFn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$createFnName"), $paramCount,
            [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                return create${info.className}Instance(r, jvm, args, count);
            }
        );
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
        for (e in enums) {
            val jsBase = e.enumName.replaceFirstChar { it.lowercase() }
            for (suffix in listOf("Ordinal", "ValueOf")) {
                cpp.write("""
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$jsBase$suffix"), 1,
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_enum_${e.enumName}_${suffix.lowercase()}(r, jvm, args, count);
            }
        );
        rdma.setProperty(rt, "$jsBase$suffix", std::move(fn));
    }
""")
            }
        }
        cpp.write("""
    installRdmaWidgetBridge(rt, jvm, rdma);
    LOGI("RDMA user bridge installed successfully");
}

jsi::Value createWithOverrides(jsi::Runtime& rt, JavaVM* jvm, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides) {
    size_t argCount = ctorArgs.size(rt);
    std::vector<jsi::Value> argsVec;
    argsVec.reserve(argCount);
    for (size_t i = 0; i < argCount; i++) {
        argsVec.push_back(ctorArgs.getValueAtIndex(rt, i));
    }
""")
        for (info in infos) {
            val openMethodCount = info.methods.count { it.isOpen }
            cpp.write("""
    if (className == "${info.className}") {
        const jsi::Value* argsPtr = argsVec.empty() ? nullptr : argsVec.data();
        auto jsObj = create${info.className}Instance(rt, jvm, argsPtr, argCount);
        auto* vt = new RdmaVtable(&rt, ${openMethodCount});
        auto objNames = overrides.getPropertyNames(rt);
        for (size_t i = 0; i < objNames.size(rt); i++) {
            auto name = objNames.getValueAtIndex(rt, i).getString(rt).utf8(rt);
            auto func = overrides.getProperty(rt, name.c_str()).asObject(rt).asFunction(rt);
""")
            for (method in info.methods.filter { it.isOpen }) {
                cpp.write("""            if (name == "${method.name}") vt->entries[${method.vtableId}] = std::make_shared<jsi::Function>(std::move(func));
""")
            }
            cpp.write("""        }
        JNIEnv* env = nullptr;
        jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        auto state = std::static_pointer_cast<${info.className}NativeState>(jsObj.getNativeState(rt));
        jfieldID vtableField = env->GetFieldID(g_rdmaCache.${info.className.lowercase()}_cache.clazz, "__vtable", "J");
        env->SetLongField(state->getObject(), vtableField, (jlong)vt);
        state->vtable_ = vt;
        return jsObj;
    }
""")
        }
        cpp.write("""
    return jsi::Value::undefined();
}

jsi::Value wrapUserObject(jsi::Runtime& rt, JavaVM* jvm, jobject obj) {
    if (!obj) return jsi::Value::null();
    JNIEnv* env = getEnv(jvm);
    if (!env) return jsi::Value::undefined();
    jclass objClass = env->GetObjectClass(obj);
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    jstring jname = (jstring)env->CallObjectMethod(objClass, getName);
    env->DeleteLocalRef(objClass);
    env->DeleteLocalRef(classClass);
    if (!jname) return jsi::Value::undefined();
    const char* cname = env->GetStringUTFChars(jname, nullptr);
    std::string name(cname);
    env->ReleaseStringUTFChars(jname, cname);
    env->DeleteLocalRef(jname);

    jobject globalObj = env->NewGlobalRef(obj);
""")
        for (info in infos) {
            cpp.write("""    if (name == "${info.qualifiedName}") return create${info.className}Wrapper(rt, jvm, globalObj);
""")
        }
        cpp.write("""    env->DeleteGlobalRef(globalObj);
    return jsi::Value::undefined();
}

$nsClose} // namespace rdma
} // namespace facebook
""")
        cpp.close()
    }

    private fun functionImpl(fn: RdmaFunctionInfo, allClasses: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        val hasDefaults = fn.parameters.any { it.hasDefault }
        sb.append("""
static jsi::Value rdma_fn_${fn.name}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
""")
        if (hasDefaults) sb.append("    int mask = 0;\n")
        fn.parameters.forEachIndexed { i, p ->
            sb.append(functionParamExtraction(p, i, allClasses, p.hasDefault))
        }
        val argsStr = fn.parameters.joinToString(", ") { callArg(it) }
        val parts = mutableListOf<String>()
        if (argsStr.isNotEmpty()) parts.add(argsStr)
        if (hasDefaults) parts.add("mask")
        val callSuffix = if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
        val marshaling = functionReturnMarshaling(fn, allClasses, callSuffix)
            .let {
                if (hasDefaults) {
                    it.replace("fn_${fn.name}_method", "fn_${fn.name}_rdma")
                        .replace("fn_${fn.name}_clazz", "wrappersClazz")
                } else {
                    it
                }
            }
        sb.append(marshaling)
        sb.append("}\n\n")
        return sb.toString()
    }

    private fun functionRegistration(fn: RdmaFunctionInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "${fn.name}"), ${fn.parameters.size},
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_fn_${fn.name}(r, jvm, args, count);
            }
        );
        rdma.setProperty(rt, "${fn.name}", std::move(fn));
    }
"""

    private fun staticJsName(info: RdmaClassInfo, static: StaticInfo): String =
        info.className.replaceFirstChar { it.lowercase() } + static.name.replaceFirstChar { it.uppercase() }

    // ---------------------------------------------------------------- suspend

    private fun suspendFunctionImpl(fn: RdmaFunctionInfo, allClasses: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        val typeDesc = typeName(fn.returnType)
        val hasDefaults = fn.parameters.any { it.hasDefault }
        sb.append("""
static jsi::Value rdma_suspend_${fn.name}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
    jlong completionId = (jlong)args[0].getNumber();
    rdmaRegisterSuspendResultType(completionId, "$typeDesc");
""")
        if (hasDefaults) sb.append("    int mask = 0;\n")
        fn.parameters.forEachIndexed { i, p ->
            sb.append(functionParamExtraction(p, i + 1, allClasses, guarded = p.hasDefault, maskBit = i))
        }
        val argsStr = fn.parameters.joinToString(", ") { callArg(it) }
        val parts = mutableListOf<String>()
        if (argsStr.isNotEmpty()) parts.add(argsStr)
        if (hasDefaults) parts.add("mask")
        val callSuffix = if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
        sb.append("    env->CallStaticVoidMethod(g_rdmaCache.suspendClazz, g_rdmaCache.suspend_fn_${fn.name}Async, completionId$callSuffix);\n")
        sb.append("    checkAndThrowJs(env, r);\n")
        fn.parameters.filter { (it.type.type as? RdmaType.Primitive)?.fqn == "kotlin.String" }
            .forEach { sb.append("    env->DeleteLocalRef(j_${it.name});\n") }
        sb.append("    return jsi::Value::undefined();\n}\n\n")
        return sb.toString()
    }

    private fun suspendMethodImpl(cls: RdmaClassInfo, m: MethodInfo, allClasses: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        val typeDesc = methodTypeDesc(m)
        val hasDefaults = m.parameters.any { it.hasDefault }
        sb.append("""
static jsi::Value rdma_suspend_${cls.className}_${m.name}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
    jlong completionId = (jlong)args[0].getNumber();
    rdmaRegisterSuspendResultType(completionId, "$typeDesc");
    auto recvObj = args[1].asObject(r);
    auto recvState = std::static_pointer_cast<RdmaObjectNativeState>(recvObj.getNativeState(r));
    jobject receiver = recvState->getObject();
""")
        if (hasDefaults) sb.append("    int mask = 0;\n")
        m.parameters.forEachIndexed { i, p ->
            sb.append(suspendMethodParamExtraction(p, i + 2, p.hasDefault, i))
        }
        m.parameters.filter { it.type == "kotlin.String" }.forEachIndexed { i, p ->
            sb.append("    jstring j_${p.name} = args[${i + 2}].isNull() ? nullptr : env->NewStringUTF(cpp_${p.name}.c_str());\n")
        }
        val paramsCall = m.parameters.joinToString(", ") { callArgMethodParam(it) }
        val parts = mutableListOf<String>()
        if (paramsCall.isNotEmpty()) parts.add(paramsCall)
        if (hasDefaults) parts.add("mask")
        val callSuffix = if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
        sb.append("    env->CallStaticVoidMethod(g_rdmaCache.suspendClazz, g_rdmaCache.suspend_${cls.className}_${m.name}Async, completionId, receiver$callSuffix);\n")
        sb.append("    checkAndThrowJs(env, r);\n")
        m.parameters.filter { it.type == "kotlin.String" }.forEach { sb.append("    env->DeleteLocalRef(j_${it.name});\n") }
        sb.append("    return jsi::Value::undefined();\n}\n\n")
        return sb.toString()
    }

    private fun suspendFunctionRegistration(fn: RdmaFunctionInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "_${fn.name}Async"), ${fn.parameters.size + 1},
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_suspend_${fn.name}(r, jvm, args, count);
            }
        );
        rdma.setProperty(rt, "_${fn.name}Async", std::move(fn));
    }
"""

    private fun suspendMethodRegistration(cls: RdmaClassInfo, m: MethodInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "_${cls.className}_${m.name}Async"), ${m.parameters.size + 2},
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_suspend_${cls.className}_${m.name}(r, jvm, args, count);
            }
        );
        rdma.setProperty(rt, "_${cls.className}_${m.name}Async", std::move(fn));
    }
"""

    private fun suspendMethodParamExtraction(p: ParameterInfo, idx: Int, guarded: Boolean = false, maskBit: Int = idx): String {
        fun maskLine(): String = if (guarded) "    if (args[$idx].isUndefined()) mask |= (1 << $maskBit);\n" else ""
        fun notUndef() = if (guarded) "!args[$idx].isUndefined() && " else ""
        val type = JniTypeMapper.forType(p.type)
        if (type != null) {
            if (p.type == "kotlin.String") {
                // String crosses as std::string; the suspend method body uses `r` (not `rt`).
                if (guarded) {
                    return "    std::string cpp_${p.name};\n" +
                        "    if (!args[$idx].isUndefined()) { cpp_${p.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(r).utf8(r); } else { mask |= (1 << $maskBit); }\n"
                }
                if (p.nullable) {
                    return "    std::string cpp_${p.name} = args[$idx].isNull() ? \"\" : args[$idx].getString(r).utf8(r);\n"
                }
                return "    std::string cpp_${p.name} = args[$idx].getString(r).utf8(r);\n"
            }
            if (guarded) {
                val decl = when (type.cppType) {
                    "bool" -> "    bool cpp_${p.name} = false;\n"
                    "int" -> "    int cpp_${p.name} = 0;\n"
                    "long" -> "    long cpp_${p.name} = 0;\n"
                    "double" -> "    double cpp_${p.name} = 0.0;\n"
                    "float" -> "    float cpp_${p.name} = 0.0f;\n"
                    else -> "    ${type.cppType} cpp_${p.name} = ${type.cppType}();\n"
                }
                return decl +
                    "    if (!args[$idx].isUndefined()) { cpp_${p.name} = ${type.fromJsi.replace("%d", idx.toString())}; } else { mask |= (1 << $maskBit); }\n"
            }
            if (p.nullable) {
                return "    ${type.cppType} cpp_${p.name} = args[$idx].isNull() ? ${type.cppType}() : (${type.fromJsi.replace("%d", idx.toString())});\n"
            }
            return "    ${type.cppType} cpp_${p.name} = ${type.fromJsi.replace("%d", idx.toString())};\n"
        }
        if (isEnumType(p.type)) {
            val enumName = p.type.substringAfterLast('.')
            return "    jobject arg_${p.name} = nullptr;\n" +
                "    if (${notUndef()}!args[$idx].isNull()) {\n" +
                "        std::string cpp_${p.name} = args[$idx].getString(r).utf8(r);\n" +
                "        jstring j_${p.name} = env->NewStringUTF(cpp_${p.name}.c_str());\n" +
                "        arg_${p.name} = env->CallStaticObjectMethod(g_rdmaCache.enum_${enumName}_clazz, g_rdmaCache.enum_${enumName}_valueOf, j_${p.name});\n" +
                "        env->DeleteLocalRef(j_${p.name});\n" +
                "        checkAndThrowJs(env, r);\n" +
                "    }\n" +
                maskLine()
        }
        if (p.isList) {
            return "    jobject arg_${p.name} = nullptr;\n" +
                "    if (${notUndef()}!args[$idx].isNull()) {\n" +
                "        auto listObj = args[$idx].asObject(r);\n" +
                "        if (listObj.hasNativeState(r)) {\n" +
                "            auto ns = listObj.getNativeState(r);\n" +
                "            if (ns) arg_${p.name} = *(jobject*)((char*)ns.get() + 16);\n" +
                "        } else {\n" +
                "            arg_${p.name} = materializeArray(env, r, jvm, listObj, \"${p.listElementType ?: ""}\");\n" +
                "        }\n" +
                "    }\n" +
                maskLine()
        }
        if (p.isMap) {
            return "    jobject arg_${p.name} = nullptr;\n" +
                "    if (${notUndef()}!args[$idx].isNull()) {\n" +
                "        auto mapObj = args[$idx].asObject(r);\n" +
                "        if (mapObj.hasNativeState(r)) {\n" +
                "            auto ns = mapObj.getNativeState(r);\n" +
                "            if (ns) arg_${p.name} = *(jobject*)((char*)ns.get() + 16);\n" +
                "        } else {\n" +
                "            arg_${p.name} = materializeMap(env, r, jvm, mapObj, \"${p.mapKeyType ?: ""}\", \"${p.mapValueType ?: ""}\");\n" +
                "        }\n" +
                "    }\n" +
                maskLine()
        }
        if (p.nullable) {
            return "    jobject arg_${p.name} = nullptr;\n" +
                "    if (${notUndef()}!args[$idx].isNull()) {\n" +
                "        auto argObj = args[$idx].asObject(r);\n" +
                "        auto argState = std::static_pointer_cast<RdmaObjectNativeState>(argObj.getNativeState(r));\n" +
                "        arg_${p.name} = argState->getObject();\n" +
                "    }\n" +
                maskLine()
        }
        if (guarded) {
            return "    jobject arg_${p.name} = nullptr;\n" +
                "    if (!args[$idx].isUndefined() && !args[$idx].isNull()) {\n" +
                "        auto argObj_${p.name} = args[$idx].asObject(r);\n" +
                "        auto argState_${p.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${p.name}.getNativeState(r));\n" +
                "        arg_${p.name} = argState_${p.name}->getObject();\n" +
                "    }\n" +
                "    if (args[$idx].isUndefined()) mask |= (1 << $maskBit);\n"
        }
        return "    auto argObj_${p.name} = args[$idx].asObject(r);\n" +
            "    auto argState_${p.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${p.name}.getNativeState(r));\n" +
            "    jobject arg_${p.name} = argState_${p.name}->getObject();\n"
    }

    private fun callArgMethodParam(p: ParameterInfo): String = when (p.type) {
        "kotlin.String" -> "j_${p.name}"
        "kotlin.Int" -> "(jint)cpp_${p.name}"
        "kotlin.Boolean" -> "(jboolean)cpp_${p.name}"
        "kotlin.Double", "kotlin.Float" -> "(jdouble)cpp_${p.name}"
        "kotlin.Long" -> "(jlong)cpp_${p.name}"
        else -> when {
            p.isList || p.isMap -> "arg_${p.name}"
            isObjectRef(p.type) -> "arg_${p.name}"
            else -> "nullptr"
        }
    }

    private fun methodTypeDesc(m: MethodInfo): String = when {
        m.isList -> "list:${m.listElementType ?: "kotlin.Any"}"
        m.isMap -> "map:${m.mapKeyType ?: "kotlin.Any"}:${m.mapValueType ?: "kotlin.Any"}"
        else -> m.returnType
    }

    /**
     * Host functions backing the plugin-side enum member bridges: `<enum>Ordinal(entry)`
     * and `<enum>ValueOf(name)`. Both validate through the kernel's `valueOf`, so an
     * invalid name surfaces as a JS error (the kernel throws IllegalArgumentException).
     */
    private fun enumHostFunctions(enums: List<RdmaEnumInfo>): String {
        if (enums.isEmpty()) return ""
        val sb = StringBuilder()
        for (e in enums) {
            val n = e.enumName
            sb.append("""
static jsi::Value rdma_enum_${n}_ordinal(jsi::Runtime& r, JavaVM* jvm, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env || count < 1) return jsi::Value::undefined();
    std::string name = args[0].isNull() ? "" : args[0].getString(r).utf8(r);
    jstring jname = env->NewStringUTF(name.c_str());
    jobject entry = env->CallStaticObjectMethod(g_rdmaCache.enum_${n}_clazz, g_rdmaCache.enum_${n}_valueOf, jname);
    env->DeleteLocalRef(jname);
    checkAndThrowJs(env, r);
    auto result = env->CallIntMethod(entry, g_rdmaCache.enumOrdinal);
    return jsi::Value((double)result);
}

static jsi::Value rdma_enum_${n}_valueof(jsi::Runtime& r, JavaVM* jvm, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env || count < 1) return jsi::Value::undefined();
    std::string name = args[0].isNull() ? "" : args[0].getString(r).utf8(r);
    jstring jname = env->NewStringUTF(name.c_str());
    jobject entry = env->CallStaticObjectMethod(g_rdmaCache.enum_${n}_clazz, g_rdmaCache.enum_${n}_valueOf, jname);
    env->DeleteLocalRef(jname);
    checkAndThrowJs(env, r);
    auto jret = (jstring)env->CallObjectMethod(entry, g_rdmaCache.enumName);
    auto cstr = env->GetStringUTFChars(jret, nullptr);
    auto ret = jsi::String::createFromUtf8(r, cstr);
    env->ReleaseStringUTFChars(jret, cstr);
    env->DeleteLocalRef(jret);
    return ret;
}

""")
        }
        return sb.toString()
    }

    private fun staticImpl(info: RdmaClassInfo, static: StaticInfo, allClasses: List<RdmaClassInfo>): String {
        val cacheVar = "${info.className.lowercase()}_cache"
        val body = when {
            isEnumType(static.type) -> {
                val nullCheck = if (static.nullable) {
                    "    if (jret == nullptr) { env->DeleteLocalRef(companion); return jsi::Value::null(); }\n"
                } else ""
                """
    jobject companion = env->GetStaticObjectField(g_rdmaCache.$cacheVar.clazz, g_rdmaCache.$cacheVar.companionField);
    jobject jret = env->CallObjectMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name});
$nullCheck    auto jname = (jstring)env->CallObjectMethod(jret, g_rdmaCache.enumName);
    auto cstr = env->GetStringUTFChars(jname, nullptr);
    auto ret = jsi::String::createFromUtf8(r, cstr);
    env->ReleaseStringUTFChars(jname, cstr);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jret);
    env->DeleteLocalRef(companion);
    return ret;
"""
            }
            isObjectRef(static.type) -> {
                val rdma = rdmaClassByName(static.type, allClasses)
                val nullCheck = if (static.nullable) {
                    "    if (jret == nullptr) { env->DeleteLocalRef(companion); return jsi::Value::null(); }\n"
                } else ""
                val wrapExpr = if (rdma != null) {
                    "    jobject globalRet = env->NewGlobalRef(jret);\n" +
                        "    env->DeleteLocalRef(jret);\n" +
                        "    env->DeleteLocalRef(companion);\n" +
                        "    return create${rdma.className}Wrapper(r, jvm, globalRet);\n"
                } else {
                    "    auto ret = wrapAny(r, jvm, jret);\n" +
                        "    env->DeleteLocalRef(jret);\n" +
                        "    env->DeleteLocalRef(companion);\n" +
                        "    return ret;\n"
                }
                """
    jobject companion = env->GetStaticObjectField(g_rdmaCache.$cacheVar.clazz, g_rdmaCache.$cacheVar.companionField);
    jobject jret = env->CallObjectMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name});
$nullCheck$wrapExpr"""
            }
            static.type == "kotlin.String" -> {
                val nullCheck = if (static.nullable) {
                    "    if (jret == nullptr) { env->DeleteLocalRef(companion); return jsi::Value::null(); }\n"
                } else ""
                """
    jobject companion = env->GetStaticObjectField(g_rdmaCache.$cacheVar.clazz, g_rdmaCache.$cacheVar.companionField);
    auto jret = (jstring)env->CallObjectMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name});
$nullCheck    auto cstr = env->GetStringUTFChars(jret, nullptr);
    auto ret = jsi::String::createFromUtf8(r, cstr);
    env->ReleaseStringUTFChars(jret, cstr);
    env->DeleteLocalRef(jret);
    env->DeleteLocalRef(companion);
    return ret;
"""
            }
            else -> {
                val call = when (static.type) {
                    "kotlin.Int" -> "env->CallIntMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name})"
                    "kotlin.Boolean" -> "env->CallBooleanMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name})"
                    "kotlin.Long" -> "env->CallLongMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name})"
                    else -> "env->CallDoubleMethod(companion, g_rdmaCache.$cacheVar.static_get_${static.name})"
                }
                val wrap = when (static.type) {
                    "kotlin.Boolean" -> "$call"
                    else -> "(double)$call"
                }
                """
    jobject companion = env->GetStaticObjectField(g_rdmaCache.$cacheVar.clazz, g_rdmaCache.$cacheVar.companionField);
    auto result = $call;
    env->DeleteLocalRef(companion);
    return jsi::Value($wrap);
"""
            }
        }
        return """
static jsi::Value rdma_static_${info.className}_${static.name}(jsi::Runtime& r, JavaVM* jvm) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
$body}
"""
    }

    private fun staticRegistration(info: RdmaClassInfo, static: StaticInfo): String = """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "${staticJsName(info, static)}"), 0,
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return rdma_static_${info.className}_${static.name}(r, jvm);
            });
        rdma.setProperty(rt, "${staticJsName(info, static)}", std::move(fn));
    }
"""

    private fun functionParamExtraction(p: RdmaParameterInfo, i: Int, allClasses: List<RdmaClassInfo>, guarded: Boolean, maskBit: Int = i): String {
        fun maskLine(): String = if (guarded) "    if (args[$i].isUndefined()) mask |= (1 << $maskBit);\n" else ""
        fun notUndef() = if (guarded) "!args[$i].isUndefined() && " else ""
        return when (val t = p.type.type) {
            is RdmaType.Primitive -> {
                if (t.fqn == "kotlin.String") {
                    if (guarded) {
                        "    std::string cpp_${p.name};\n" +
                            "    if (!args[$i].isUndefined()) { cpp_${p.name} = args[$i].isNull() ? \"\" : args[$i].getString(r).utf8(r); } else { mask |= (1 << $maskBit); }\n" +
                            "    jstring j_${p.name} = env->NewStringUTF(cpp_${p.name}.c_str());\n"
                    } else if (p.type.nullable) {
                        "    std::string cpp_${p.name} = args[$i].isNull() ? \"\" : args[$i].getString(r).utf8(r);\n    jstring j_${p.name} = args[$i].isNull() ? nullptr : env->NewStringUTF(cpp_${p.name}.c_str());\n"
                    } else {
                        "    std::string cpp_${p.name} = args[$i].getString(r).utf8(r);\n    jstring j_${p.name} = env->NewStringUTF(cpp_${p.name}.c_str());\n"
                    }
                } else {
                    val jt = JniTypeMapper.forType(t.fqn) ?: return ""
                    if (guarded) {
                        val zero = when (t.fqn) {
                            "kotlin.Int" -> "0"
                            "kotlin.Long" -> "0"
                            "kotlin.Boolean" -> "false"
                            else -> "0.0"
                        }
                        "    ${jt.cppType} cpp_${p.name} = $zero;\n" +
                            "    if (!args[$i].isUndefined()) { cpp_${p.name} = ${jt.fromJsi.replace("%d", i.toString())}; } else { mask |= (1 << $maskBit); }\n"
                    } else {
                        "    ${jt.cppType} cpp_${p.name} = ${jt.fromJsi.replace("%d", i.toString())};\n"
                    }
                }
            }
            is RdmaType.Ref -> {
                if (isEnumType(t.fqn)) {
                    val enumName = t.fqn.substringAfterLast('.')
                    "    jobject arg_${p.name} = nullptr;\n" +
                        "    if (${notUndef()}!args[$i].isNull()) {\n" +
                        "        std::string cpp_${p.name} = args[$i].getString(r).utf8(r);\n" +
                        "        jstring j_${p.name} = env->NewStringUTF(cpp_${p.name}.c_str());\n" +
                        "        arg_${p.name} = env->CallStaticObjectMethod(g_rdmaCache.enum_${enumName}_clazz, g_rdmaCache.enum_${enumName}_valueOf, j_${p.name});\n" +
                        "        env->DeleteLocalRef(j_${p.name});\n" +
                        "        checkAndThrowJs(env, r);\n" +
                        "    }\n" +
                        maskLine()
                } else {
                    "    jobject arg_${p.name} = nullptr;\n" +
                        "    if (${notUndef()}!args[$i].isNull()) {\n" +
                        "        auto argObj_${p.name} = args[$i].asObject(r);\n" +
                        "        auto argState_${p.name} = std::static_pointer_cast<RdmaObjectNativeState>(argObj_${p.name}.getNativeState(r));\n" +
                        "        arg_${p.name} = argState_${p.name}->getObject();\n" +
                        "    }\n" +
                        maskLine()
                }
            }
            is RdmaType.ListType -> {
                val elem = typeName(t.element)
                "    jobject arg_${p.name} = nullptr;\n" +
                    "    if (${notUndef()}!args[$i].isNull()) {\n" +
                    "        auto listObj_${p.name} = args[$i].asObject(r);\n" +
                    "        if (listObj_${p.name}.hasNativeState(r)) {\n" +
                    "            auto ns_${p.name} = listObj_${p.name}.getNativeState(r);\n" +
                    "            if (ns_${p.name}) arg_${p.name} = *(jobject*)((char*)ns_${p.name}.get() + 16);\n" +
                    "        } else {\n" +
                    "            arg_${p.name} = materializeArray(env, r, jvm, listObj_${p.name}, \"$elem\");\n" +
                    "        }\n" +
                    "    }\n" +
                    maskLine()
            }
            is RdmaType.MapType -> {
                val key = typeName(t.key)
                val value = typeName(t.value)
                "    jobject arg_${p.name} = nullptr;\n" +
                    "    if (${notUndef()}!args[$i].isNull()) {\n" +
                    "        auto mapObj_${p.name} = args[$i].asObject(r);\n" +
                    "        if (mapObj_${p.name}.hasNativeState(r)) {\n" +
                    "            auto ns_${p.name} = mapObj_${p.name}.getNativeState(r);\n" +
                    "            if (ns_${p.name}) arg_${p.name} = *(jobject*)((char*)ns_${p.name}.get() + 16);\n" +
                    "        } else {\n" +
                    "            arg_${p.name} = materializeMap(env, r, jvm, mapObj_${p.name}, \"$key\", \"$value\");\n" +
                    "        }\n" +
                    "    }\n" +
                    maskLine()
            }
            is RdmaType.FunctionType -> {
                val arity = t.parameters.size
                if (guarded) {
                    "    jlong id_${p.name} = 0;\n" +
                        "    if (!args[$i].isUndefined()) { id_${p.name} = (jlong)args[$i].getNumber(); } else { mask |= (1 << $i); }\n" +
                        "    jobject arg_${p.name} = createRdmaFunction(env, $arity, id_${p.name});\n"
                } else {
                    "    jlong id_${p.name} = (jlong)args[$i].getNumber();\n" +
                        "    jobject arg_${p.name} = createRdmaFunction(env, $arity, id_${p.name});\n"
                }
            }
            is RdmaType.UnitType -> ""
        }
    }

    private fun callArg(p: RdmaParameterInfo): String = when (val t = p.type.type) {
        is RdmaType.Primitive -> when (t.fqn) {
            "kotlin.String" -> "j_${p.name}"
            "kotlin.Int" -> "(jint)cpp_${p.name}"
            "kotlin.Boolean" -> "(jboolean)cpp_${p.name}"
            "kotlin.Double", "kotlin.Float" -> "(jdouble)cpp_${p.name}"
            "kotlin.Long" -> "(jlong)cpp_${p.name}"
            else -> "nullptr"
        }
        is RdmaType.Ref, is RdmaType.ListType, is RdmaType.MapType, is RdmaType.FunctionType -> "arg_${p.name}"
        is RdmaType.UnitType -> ""
    }

    private fun typeName(type: RdmaTypeRef): String = when (val t = type.type) {
        is RdmaType.Primitive -> t.fqn
        is RdmaType.Ref -> t.fqn
        is RdmaType.ListType -> "list:" + typeName(t.element)
        is RdmaType.MapType -> "map:" + typeName(t.key) + ":" + typeName(t.value)
        is RdmaType.UnitType -> "kotlin.Unit"
        is RdmaType.FunctionType -> "kotlin.Any"
    }

    private fun functionReturnMarshaling(fn: RdmaFunctionInfo, allClasses: List<RdmaClassInfo>, callSuffix: String): String {
        val ret = fn.returnType
        val deleteJStrings = fn.parameters
            .filter { (it.type.type as? RdmaType.Primitive)?.fqn == "kotlin.String" }
            .joinToString("") { "    env->DeleteLocalRef(j_${it.name});\n" }
        return when (val t = ret.type) {
            is RdmaType.UnitType ->
                "    env->CallStaticVoidMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                    "    checkAndThrowJs(env, r);\n" +
                    "$deleteJStrings    return jsi::Value::undefined();\n"
            is RdmaType.Primitive -> when (t.fqn) {
                "kotlin.String" ->
                    "    auto jret = (jstring)env->CallStaticObjectMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                        "    checkAndThrowJs(env, r);\n" +
                        (if (ret.nullable) "    if (jret == nullptr) return jsi::Value::null();\n" else "") +
                        "    auto cstr = env->GetStringUTFChars(jret, nullptr); auto ret = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jret, cstr); env->DeleteLocalRef(jret);\n$deleteJStrings    return ret;\n"
                "kotlin.Int" -> "    auto result = env->CallStaticIntMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n    checkAndThrowJs(env, r);\n$deleteJStrings    return jsi::Value((double)result);\n"
                "kotlin.Boolean" -> "    auto result = env->CallStaticBooleanMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n    checkAndThrowJs(env, r);\n$deleteJStrings    return jsi::Value(result);\n"
                "kotlin.Double", "kotlin.Float" -> "    auto result = env->CallStaticDoubleMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n    checkAndThrowJs(env, r);\n$deleteJStrings    return jsi::Value(result);\n"
                "kotlin.Long" -> "    auto result = env->CallStaticLongMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n    checkAndThrowJs(env, r);\n$deleteJStrings    return jsi::Value((double)result);\n"
                else -> "    return jsi::Value::undefined();\n"
            }
            is RdmaType.Ref -> {
                if (isEnumType(t.fqn)) {
                    // Enum returns cross as their name string.
                    "    auto jret = env->CallStaticObjectMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                        "    checkAndThrowJs(env, r);\n" +
                        (if (ret.nullable) "    if (jret == nullptr) return jsi::Value::null();\n" else "") +
                        "    auto jname = (jstring)env->CallObjectMethod(jret, g_rdmaCache.enumName);\n" +
                        "    auto cstr = env->GetStringUTFChars(jname, nullptr); auto ret = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jname, cstr); env->DeleteLocalRef(jname);\n" +
                        "    env->DeleteLocalRef(jret);\n$deleteJStrings    return ret;\n"
                } else {
                    val cls = allClasses.find { it.qualifiedName == t.fqn }?.className
                    if (cls == null) {
                        "    auto jret = env->CallStaticObjectMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                            "    checkAndThrowJs(env, r);\n" +
                            (if (ret.nullable) "    if (jret == nullptr) return jsi::Value::null();\n" else "") +
                            "    auto ret = wrapAny(r, jvm, jret);\n    env->DeleteLocalRef(jret);\n$deleteJStrings    return ret;\n"
                    } else {
                        "    auto jret = env->CallStaticObjectMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                            "    checkAndThrowJs(env, r);\n" +
                            (if (ret.nullable) "    if (jret == nullptr) return jsi::Value::null();\n" else "") +
                            "    jobject globalRet = env->NewGlobalRef(jret);\n    env->DeleteLocalRef(jret);\n$deleteJStrings    return create${cls}Wrapper(r, jvm, globalRet);\n"
                    }
                }
            }
            is RdmaType.ListType -> {
                val elem = typeName(t.element)
                "    auto jret = env->CallStaticObjectMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                    "    checkAndThrowJs(env, r);\n" +
                    "    if (jret != nullptr) {\n        jobject globalRet = env->NewGlobalRef(jret);\n        env->DeleteLocalRef(jret);\n        return createListHandle(r, jvm, globalRet, \"$elem\");\n    }\n$deleteJStrings    return jsi::Value::null();\n"
            }
            is RdmaType.MapType -> {
                val key = typeName(t.key)
                val value = typeName(t.value)
                "    auto jret = env->CallStaticObjectMethod(g_rdmaCache.fn_${fn.name}_clazz, g_rdmaCache.fn_${fn.name}_method$callSuffix);\n" +
                    "    checkAndThrowJs(env, r);\n" +
                    "    if (jret != nullptr) {\n        jobject globalRet = env->NewGlobalRef(jret);\n        env->DeleteLocalRef(jret);\n        return createMapHandle(r, jvm, globalRet, \"$key\", \"$value\");\n    }\n$deleteJStrings    return jsi::Value::null();\n"
            }
            is RdmaType.FunctionType -> "    return jsi::Value::undefined();\n"
        }
    }
}
