package io.github.dendygrobovshik.kardman.types

import kotlinx.serialization.Serializable

@Serializable
data class RdmaClassInfo(
    val packageName: String,
    val className: String,
    val qualifiedName: String,
    val constructors: List<ConstructorInfo>,
    val methods: List<MethodInfo>,
    val properties: List<PropertyInfo>,
)

@Serializable
data class ConstructorInfo(
    val parameters: List<ParameterInfo>,
)

@Serializable
data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val nullableReturn: Boolean = false,
    val isOpen: Boolean = false,
    val isList: Boolean = false,
    val listElementType: String? = null,
    val vtableId: Int = -1,
)

@Serializable
data class PropertyInfo(
    val name: String,
    val type: String,
    val isMutable: Boolean,
    val nullable: Boolean = false,
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val nullable: Boolean = false,
    val isList: Boolean = false,
    val listElementType: String? = null,
)

@Serializable
data class RdmaFunctionInfo(
    val name: String,
    val qualifiedName: String,
    val facadeClass: String,
    val composable: Boolean,
    val parameters: List<RdmaParameterInfo>,
    val returnType: RdmaTypeRef,
)

@Serializable
data class RdmaParameterInfo(
    val name: String,
    val type: RdmaTypeRef,
    val composable: Boolean = false,
)

@Serializable
data class RdmaManifest(
    val classes: List<RdmaClassInfo> = emptyList(),
    val functions: List<RdmaFunctionInfo> = emptyList(),
)
