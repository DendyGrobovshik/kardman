package io.github.dendygrobovshik.kardman.plugin

import io.github.dendygrobovshik.kardman.types.RdmaManifest
import io.github.dendygrobovshik.kardman.types.RdmaType
import kotlinx.serialization.json.Json

data class RdmaPluginType(
    val simpleName: String,
    val qualifiedName: String,
    val constructorParams: List<Pair<String, String>>,
    val properties: List<Pair<String, Boolean>>,
)

data class RdmaPluginFunction(
    val name: String,
    val qualifiedName: String,
    val composable: Boolean,
    val parameters: List<RdmaPluginParameter>,
)

enum class RdmaParamKind { VALUE, CONTENT, CALLBACK }

data class RdmaPluginParameter(
    val name: String,
    val kind: RdmaParamKind,
    /** Full rendered Kotlin type (e.g. `String`, `@Composable () -> Unit`, `(String) -> Unit`). */
    val kotlinType: String,
    /** Number of lambda parameters for CALLBACK/CONTENT; null for VALUE. */
    val lambdaArity: Int? = null,
)

data class RdmaPluginManifest(
    val classes: List<RdmaPluginType>,
    val functions: List<RdmaPluginFunction>,
)

object RdmaPluginConfig {
    fun parseManifest(json: String): RdmaPluginManifest {
        val raw = try {
            Json.decodeFromString(RdmaManifest.serializer(), json)
        } catch (e: Exception) {
            return RdmaPluginManifest(emptyList(), emptyList())
        }

        val classes = raw.classes.map { cls ->
            val constructorParams = cls.constructors.firstOrNull()
                ?.parameters?.map { it.name to it.type }
                ?: emptyList()
            val properties = cls.properties.map { it.name to it.isMutable }
            RdmaPluginType(cls.className, cls.qualifiedName, constructorParams, properties)
        }

        val functions = raw.functions.map { fn ->
            val parameters = fn.parameters.map { p ->
                val fnType = p.type.type as? RdmaType.FunctionType
                when {
                    fnType != null && p.composable ->
                        RdmaPluginParameter(p.name, RdmaParamKind.CONTENT, "@Composable () -> Unit", fnType.parameters.size)
                    fnType != null -> {
                        val paramTypes = fnType.parameters.map { kotlinTypeFor(fqnOf(it.type)) }
                        val ktType = "(" + paramTypes.joinToString(", ") + ") -> Unit"
                        RdmaPluginParameter(p.name, RdmaParamKind.CALLBACK, ktType, paramTypes.size)
                    }
                    else ->
                        RdmaPluginParameter(p.name, RdmaParamKind.VALUE, kotlinTypeFor(fqnOf(p.type.type)))
                }
            }
            RdmaPluginFunction(fn.name, fn.qualifiedName, fn.composable, parameters)
        }

        return RdmaPluginManifest(classes, functions)
    }

    private fun fqnOf(t: RdmaType): String = when (t) {
        is RdmaType.Primitive -> t.fqn
        is RdmaType.Ref -> t.fqn
        else -> "kotlin.Any"
    }

    private fun kotlinTypeFor(fqn: String): String = when (fqn) {
        "kotlin.String" -> "String"
        "kotlin.Int" -> "Int"
        "kotlin.Long" -> "Long"
        "kotlin.Boolean" -> "Boolean"
        "kotlin.Double" -> "Double"
        "kotlin.Float" -> "Float"
        "kotlin.Unit" -> "Unit"
        else -> "Any"
    }
}
