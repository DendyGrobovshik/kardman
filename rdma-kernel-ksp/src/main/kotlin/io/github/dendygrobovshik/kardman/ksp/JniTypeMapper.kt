package io.github.dendygrobovshik.kardman.ksp

object JniTypeMapper {
    data class JniType(
        val jniSignature: String,
        val cppType: String,
        val jniType: String,
        val fromJsi: String,
        val toJsi: String,
        val fromJni: String,
        val toJni: String,
    )

    private val primitives = mapOf(
        "kotlin.Int" to JniType(
            jniSignature = "I",
            cppType = "int",
            jniType = "jint",
            fromJsi = "args[%d].getNumber()",
            toJsi = "facebook::jsi::Value(%s)",
            fromJni = "",
            toJni = "",
        ),
        "kotlin.Long" to JniType(
            jniSignature = "J",
            cppType = "long",
            jniType = "jlong",
            fromJsi = "(long)args[%d].getNumber()",
            toJsi = "facebook::jsi::Value((double)%s)",
            fromJni = "",
            toJni = "",
        ),
        "kotlin.Float" to JniType(
            jniSignature = "F",
            cppType = "float",
            jniType = "jfloat",
            fromJsi = "(float)args[%d].getNumber()",
            toJsi = "facebook::jsi::Value((double)%s)",
            fromJni = "",
            toJni = "",
        ),
        "kotlin.Double" to JniType(
            jniSignature = "D",
            cppType = "double",
            jniType = "jdouble",
            fromJsi = "args[%d].getNumber()",
            toJsi = "facebook::jsi::Value(%s)",
            fromJni = "",
            toJni = "",
        ),
        "kotlin.Boolean" to JniType(
            jniSignature = "Z",
            cppType = "bool",
            jniType = "jboolean",
            fromJsi = "args[%d].getBool()",
            toJsi = "facebook::jsi::Value(%s)",
            fromJni = "",
            toJni = "",
        ),
        "kotlin.String" to JniType(
            jniSignature = "Ljava/lang/String;",
            cppType = "std::string",
            jniType = "jstring",
            fromJsi = "args[%d].getString(rt).utf8(rt)",
            toJsi = "facebook::jsi::String::createFromUtf8(rt, %s)",
            fromJni = "env->GetStringUTFChars(%s, nullptr)",
            toJni = "env->NewStringUTF(%s.c_str())",
        ),
        "kotlin.Unit" to JniType(
            jniSignature = "V",
            cppType = "void",
            jniType = "void",
            fromJsi = "",
            toJsi = "facebook::jsi::Value::undefined()",
            fromJni = "",
            toJni = "",
        ),
    )

    fun forType(qualifiedName: String): JniType? {
        return primitives[qualifiedName]
    }

    fun isPrimitive(qualifiedName: String): Boolean {
        return qualifiedName in primitives
    }

    fun isVoid(qualifiedName: String): Boolean {
        return qualifiedName == "kotlin.Unit"
    }

    fun isPrimitiveOrString(qualifiedName: String): Boolean {
        return qualifiedName in primitives && qualifiedName != "kotlin.Unit"
    }

    fun rdmaShortName(qualifiedName: String): String {
        return qualifiedName.substringAfterLast(".")
    }

    fun jniSignature(qualifiedName: String): String {
        forType(qualifiedName)?.let { return it.jniSignature }
        return "L${qualifiedName.replace('.', '/')};"
    }
}
