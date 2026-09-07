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
        // Kotlin types that map to JVM interfaces
        val jvmType = when (qualifiedName) {
            "kotlin.collections.List" -> "java.util.List"
            "kotlin.collections.MutableList" -> "java.util.List"
            "kotlin.collections.ArrayList" -> "java.util.ArrayList"
            else -> qualifiedName
        }
        return "L${jvmType.replace('.', '/')};"
    }
}
