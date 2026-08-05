package io.github.dendygrobovshik.kardman.ksp

import java.io.OutputStream

class CppGenerator(private val output: (String, String) -> OutputStream) {

    fun generate(classInfos: List<RdmaClassInfo>) {
        if (classInfos.isEmpty()) return
        generateJniCacheHeader(classInfos)
        generateJniCacheCpp(classInfos)
        for (info in classInfos) {
            generateHostObjectHeader(info)
            generateHostObjectCpp(info, classInfos)
        }
        generateBridge(classInfos)
    }

    private fun isRdmaClass(typeName: String, allClasses: List<RdmaClassInfo>): Boolean {
        return allClasses.any { it.qualifiedName == typeName }
    }

    private fun rdmaClassByName(typeName: String, allClasses: List<RdmaClassInfo>): RdmaClassInfo? {
        return allClasses.find { it.qualifiedName == typeName }
    }

    private fun generateJniCacheHeader(infos: List<RdmaClassInfo>) {
        val out = output("RdmaJniCache.h", "RdmaJniCache.h").bufferedWriter()
        out.write("""#pragma once
#include <jni.h>
#include <string>
#include <vector>

struct RdmaJniCache {
    JavaVM* jvm = nullptr;
""")
        for (info in infos) {
            out.write("""
    struct ${info.className}Cache {
        jclass clazz = nullptr;
        jmethodID constructor = nullptr;
""")
            for (method in info.methods) {
                out.write("        jmethodID method_${method.name} = nullptr;\n")
            }
            for (prop in info.properties) {
                out.write("        jmethodID getter_${prop.name} = nullptr;\n")
                if (prop.isMutable) {
                    out.write("        jmethodID setter_${prop.name} = nullptr;\n")
                }
            }
            out.write("    };\n")
            out.write("    ${info.className}Cache ${info.className.lowercase()}_cache;\n")
        }
        out.write("};\n\nextern RdmaJniCache g_rdmaCache;\n\nvoid initJniCache(JNIEnv* env);\n")
        out.close()
    }

    private fun generateJniCacheCpp(infos: List<RdmaClassInfo>) {
        val out = output("RdmaJniCache.cpp", "RdmaJniCache.cpp").bufferedWriter()
        out.write("""#include "RdmaJniCache.h"

RdmaJniCache g_rdmaCache;

void initJniCache(JNIEnv* env) {
""")
        for (info in infos) {
            val jniClassName = info.qualifiedName.replace('.', '/')
            val cacheVar = "${info.className.lowercase()}_cache"
            out.write("""
    {
        jclass localCls = env->FindClass("$jniClassName");
        g_rdmaCache.$cacheVar.clazz = (jclass)env->NewGlobalRef(localCls);
        env->DeleteLocalRef(localCls);
""")
            for (ctor in info.constructors) {
                val paramSig = ctor.parameters.joinToString("") {
                    JniTypeMapper.jniSignature(it.type)
                }
                out.write("        g_rdmaCache.$cacheVar.constructor = env->GetMethodID(g_rdmaCache.$cacheVar.clazz, \"<init>\", \"($paramSig)V\");\n")
            }
            for (method in info.methods) {
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
            out.write("    }\n")
        }
        out.write("}\n")
        out.close()
    }

    private fun generateHostObjectHeader(info: RdmaClassInfo) {
        val out = output("${info.className}HostObject.h", "${info.className}HostObject.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <memory>
#include <string>
#include "RdmaVtable.h"

namespace facebook {
namespace rdma {

class ${info.className}NativeState : public jsi::NativeState {
public:
    ${info.className}NativeState(JavaVM* jvm, jobject globalRef);
    ~${info.className}NativeState() override;
    jobject getObject() const { return globalRef_; }
    JavaVM* getJvm() const { return jvm_; }
    RdmaVtable* vtable_ = nullptr;

private:
    JavaVM* jvm_;
    jobject globalRef_;
};

void register${info.className}Bridge(jsi::Runtime& rt, JavaVM* jvm);
jsi::Object create${info.className}Instance(jsi::Runtime& rt, JavaVM* jvm, const jsi::Value* args, size_t count);

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateHostObjectCpp(info: RdmaClassInfo, allClasses: List<RdmaClassInfo>) {
        val out = output("${info.className}HostObject.cpp", "${info.className}HostObject.cpp").bufferedWriter()
        val cacheVar = "${info.className.lowercase()}_cache"

        out.write("""#include "${info.className}HostObject.h"
#include "RdmaJniCache.h"
""")
        // Include headers for @RDMA types used as parameters or return values
        val referencedRdmaTypes = mutableSetOf<String>()
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
            out.write("#include \"${rdmaName}HostObject.h\"\n")
        }

        out.write("""
#include <android/log.h>

#define LOG_TAG "Rdma${info.className}"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {
""")
        // Forward-declare wrapper functions for @RDMA return types
        for (method in info.methods) {
            if (isRdmaClass(method.returnType, allClasses)) {
                val rdma = rdmaClassByName(method.returnType, allClasses)!!
                out.write("static jsi::Object create${rdma.className}Wrapper(jsi::Runtime& rt, JavaVM* jvm, jobject globalObj);\n")
            }
        }

        out.write("""
${info.className}NativeState::${info.className}NativeState(JavaVM* jvm, jobject globalRef)
    : jvm_(jvm), globalRef_(globalRef) {}

${info.className}NativeState::~${info.className}NativeState() {
    if (globalRef_ != nullptr && jvm_ != nullptr) {
        JNIEnv* env = nullptr;
        jint res = jvm_->GetEnv((void**)&env, JNI_VERSION_1_6);
        bool isAttached = false;
        if (res == JNI_EDETACHED) {
            res = jvm_->AttachCurrentThread(&env, nullptr);
            if (res == JNI_OK) isAttached = true;
        }
        if (env != nullptr) {
            env->DeleteGlobalRef(globalRef_);
        }
        if (isAttached) jvm_->DetachCurrentThread();
    }
    delete vtable_;
}
""")

        for (prop in info.properties) {
            val retType = JniTypeMapper.forType(prop.type)
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            val returnExpr = when (prop.type) {
                "kotlin.String" -> {
                    "auto jstr = (jstring)env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}); auto cstr = env->GetStringUTFChars(jstr, nullptr); auto result = jsi::String::createFromUtf8(r, cstr); env->ReleaseStringUTFChars(jstr, cstr); env->DeleteLocalRef(jstr); return result;"
                }
                "kotlin.Int" -> "return jsi::Value((double)env->CallIntMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                "kotlin.Boolean" -> "return jsi::Value(env->CallBooleanMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                "kotlin.Double", "kotlin.Float" -> "return jsi::Value(env->CallDoubleMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
                "kotlin.Long" -> "return jsi::Value((double)env->CallLongMethod(state->getObject(), g_rdmaCache.${cacheVar}.getter_${prop.name}));"
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
                val callExpr = when (prop.type) {
                    "kotlin.Int" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, (jint)args[0].getNumber());"
                    "kotlin.Boolean" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, args[0].getBool());"
                    "kotlin.Double", "kotlin.Float" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, args[0].getNumber());"
                    "kotlin.Long" -> "env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, (jlong)args[0].getNumber());"
                    "kotlin.String" -> "auto jstr = env->NewStringUTF(args[0].getString(r).utf8(r).c_str()); env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.setter_${prop.name}, jstr); env->DeleteLocalRef(jstr);"
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

        for (method in info.methods) {
            val paramCount = method.parameters.size
            val returnType = JniTypeMapper.forType(method.returnType)
            val isVoid = JniTypeMapper.isVoid(method.returnType)

            out.write("""
static jsi::Value ${info.className}_${method.name}(jsi::Runtime& r, JavaVM* jvm, const jsi::Value& thisVal, const jsi::Value* args, size_t count) {
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();
    auto thisObj = thisVal.asObject(r);
    auto state = std::static_pointer_cast<${info.className}NativeState>(thisObj.getNativeState(r));
    if (state->vtable_) {
        auto vtIt = state->vtable_->entries.find("${method.name}");
        if (vtIt != state->vtable_->entries.end()) {
            auto& irt = *(jsi::IRuntime*)&r;
            return vtIt->second->call(irt, args, count);
        }
    }
""")
            for (param in method.parameters) {
                val type = JniTypeMapper.forType(param.type)
                val idx = method.parameters.indexOf(param)
                if (type != null) {
                    if (param.nullable) {
                        out.write("    ${type.cppType} cpp_${param.name} = args[$idx].isNull() ? ${type.cppType}() : (${type.fromJsi.replace("%d", idx.toString())});\n")
                    } else {
                        val extractExpr = type.fromJsi.replace("%d", idx.toString())
                        out.write("    ${type.cppType} cpp_${param.name} = $extractExpr;\n")
                    }
                } else if (isRdmaClass(param.type, allClasses)) {
                    val rdmaName = rdmaClassByName(param.type, allClasses)!!.className
                    if (param.nullable) {
                        out.write("    jobject arg_${param.name} = nullptr;\n")
                        out.write("    if (!args[$idx].isNull()) {\n")
                        out.write("        auto argObj_${param.name} = args[$idx].asObject(r);\n")
                        out.write("        auto argState_${param.name} = std::static_pointer_cast<${rdmaName}NativeState>(argObj_${param.name}.getNativeState(r));\n")
                        out.write("        arg_${param.name} = argState_${param.name}->getObject();\n")
                        out.write("    }\n")
                    } else {
                        out.write("    auto argObj_${param.name} = args[$idx].asObject(r);\n")
                        out.write("    auto argState_${param.name} = std::static_pointer_cast<${rdmaName}NativeState>(argObj_${param.name}.getNativeState(r));\n")
                        out.write("    jobject arg_${param.name} = argState_${param.name}->getObject();\n")
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
                    else -> if (isRdmaClass(param.type, allClasses)) "arg_${param.name}" else "nullptr"
                }
            }
            val paramsCall = if (paramsStr.isNotEmpty()) ", $paramsStr" else ""

            if (isVoid) {
                out.write("    env->CallVoidMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});\n")
                for (param in method.parameters) {
                    if (param.type == "kotlin.String") {
                        out.write("    env->DeleteLocalRef(j_${param.name});\n")
                    }
                }
                out.write("    return jsi::Value::undefined();\n")
            } else {
                val callExpr = when (method.returnType) {
                    "kotlin.String" -> "auto jret = (jstring)env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});"
                    "kotlin.Int" -> "auto result = env->CallIntMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});"
                    "kotlin.Boolean" -> "auto result = env->CallBooleanMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});"
                    "kotlin.Double", "kotlin.Float" -> "auto result = env->CallDoubleMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});"
                    "kotlin.Long" -> "auto result = env->CallLongMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});"
                    else -> if (isRdmaClass(method.returnType, allClasses)) {
                        val rdma = rdmaClassByName(method.returnType, allClasses)!!
                        "auto jret = env->CallObjectMethod(state->getObject(), g_rdmaCache.${cacheVar}.method_${method.name}${paramsCall});"
                    } else ""
                }
                out.write("    $callExpr\n")
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
                        val rdma = rdmaClassByName(method.returnType, allClasses)
                        if (rdma != null) {
                            out.write("    jobject globalRet = env->NewGlobalRef(jret);\n")
                            out.write("    env->DeleteLocalRef(jret);\n")
                            out.write("    return create${rdma.className}Wrapper(r, jvm, globalRet);\n")
                        } else {
                            out.write("    return jsi::Value::undefined();\n")
                        }
                    }
                }
            }
            out.write("}\n")
        }

        out.write("""
void register${info.className}Bridge(jsi::Runtime& rt, JavaVM* jvm) {
""")
        for (method in info.methods) {
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
            var idx = 0
            for (param in ctor.parameters) {
                val type = JniTypeMapper.forType(param.type)
                if (type != null) {
                    val extract = type.fromJsi.replace("%d", idx.toString())
                    out.write("    ${type.cppType} cpp_${param.name} = $extract;\n")
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
                    else -> "nullptr"
                }
            }
            out.write("    jobject localObj = env->NewObject(g_rdmaCache.${cacheVar}.clazz, g_rdmaCache.${cacheVar}.constructor${if (params.isNotEmpty()) ", $params" else ""});\n")
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
        for (method in info.methods) {
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
        for (method in info.methods) {
            out.write("    jsObj.setProperty(rt, \"${method.name}\", rt.global().getProperty(rt, \"__${info.className}_proto_${method.name}\"));\n")
        }
        for (prop in info.properties) {
            val getterName = "get${prop.name.replaceFirstChar { it.uppercase() }}"
            out.write("    jsObj.setProperty(rt, \"$getterName\", rt.global().getProperty(rt, \"__${info.className}_proto_$getterName\"));\n")
        }
        out.write("    return jsObj;\n}\n\n")

        out.write("} // namespace rdma\n} // namespace facebook\n")
        out.close()
    }

    private fun generateBridge(infos: List<RdmaClassInfo>) {
        val out = output("RdmaBridge.h", "RdmaBridge.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include <jni.h>

namespace facebook {
namespace rdma {

void installRdmaBridge(jsi::Runtime& rt, JavaVM* jvm, JNIEnv* env);

} // namespace rdma
} // namespace facebook
""")
        out.close()

        val cpp = output("RdmaBridge.cpp", "RdmaBridge.cpp").bufferedWriter()
        cpp.write("""#include "RdmaBridge.h"
#include "RdmaJniCache.h"
#include "RdmaVtable.h"
""")
        for (info in infos) {
            cpp.write("#include \"${info.className}HostObject.h\"\n")
        }
        cpp.write("""
#include <android/log.h>

#define LOG_TAG "RdmaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {

static jsi::Object createWithOverrides(jsi::Runtime& rt, JavaVM* jvm, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides);

void installRdmaBridge(jsi::Runtime& rt, JavaVM* jvm, JNIEnv* env) {
    g_rdmaCache.jvm = jvm;
    initJniCache(env);

    LOGI("Initializing RDMA bridge...");

""")
        for (info in infos) {
            cpp.write("    register${info.className}Bridge(rt, jvm);\n")
        }
        cpp.write("""
    jsi::Object rdmaNamespace(rt);
""")
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
        rdmaNamespace.setProperty(rt, "$createFnName", std::move(createFn));
    }
""")
        }
        cpp.write("""
    {
        auto createOverridesFn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "createWithOverrides"), 3,
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 3) return jsi::Value::undefined();
                std::string className = args[0].getString(r).utf8(r);
                jsi::Array ctorArgs = args[1].asObject(r).asArray(r);
                jsi::Object overrides = args[2].asObject(r);
                return createWithOverrides(r, jvm, className, ctorArgs, overrides);
            }
        );
        rdmaNamespace.setProperty(rt, "createWithOverrides", std::move(createOverridesFn));
    }
    rt.global().setProperty(rt, "RDMA", std::move(rdmaNamespace));
    LOGI("RDMA bridge installed successfully");
}

static jsi::Object createWithOverrides(jsi::Runtime& rt, JavaVM* jvm, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides) {
    size_t argCount = ctorArgs.size(rt);
    std::vector<jsi::Value> argsVec;
    argsVec.reserve(argCount);
    for (size_t i = 0; i < argCount; i++) {
        argsVec.push_back(ctorArgs.getValueAtIndex(rt, i));
    }
""")
        for (info in infos) {
            cpp.write("""
    if (className == "${info.className}") {
        const jsi::Value* argsPtr = argsVec.empty() ? nullptr : argsVec.data();
        auto jsObj = create${info.className}Instance(rt, jvm, argsPtr, argCount);
        auto* vt = new RdmaVtable(&rt, jvm);
        auto objNames = overrides.getPropertyNames(rt);
        for (size_t i = 0; i < objNames.size(rt); i++) {
            auto name = objNames.getValueAtIndex(rt, i).getString(rt).utf8(rt);
            auto func = overrides.getProperty(rt, name.c_str()).asObject(rt).asFunction(rt);
            vt->entries[name] = std::make_shared<jsi::Function>(std::move(func));
        }
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
    return jsi::Object(rt);
}

} // namespace rdma
} // namespace facebook
""")
        cpp.close()
    }
}
