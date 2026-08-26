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

data class RdmaPluginParameter(
    val lambdaArity: Int?,
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
                val arity = (p.type.type as? RdmaType.FunctionType)?.parameters?.size
                RdmaPluginParameter(arity)
            }
            RdmaPluginFunction(fn.name, fn.qualifiedName, fn.composable, parameters)
        }

        return RdmaPluginManifest(classes, functions)
    }
}
