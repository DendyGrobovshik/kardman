package io.github.dendygrobovshik.kardman.ksp

data class RdmaClassInfo(
    val packageName: String,
    val className: String,
    val qualifiedName: String,
    val constructors: List<ConstructorInfo>,
    val methods: List<MethodInfo>,
    val properties: List<PropertyInfo>,
)

data class ConstructorInfo(
    val parameters: List<ParameterInfo>,
)

data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val nullableReturn: Boolean = false,
    val isOpen: Boolean = false,
)

data class PropertyInfo(
    val name: String,
    val type: String,
    val isMutable: Boolean,
    val nullable: Boolean = false,
)

data class ParameterInfo(
    val name: String,
    val type: String,
    val nullable: Boolean = false,
)
