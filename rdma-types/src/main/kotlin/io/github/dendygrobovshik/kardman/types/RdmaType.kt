package io.github.dendygrobovshik.kardman.types

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A type that may cross the runtime boundary.
 *
 * The boundary admits only:
 *  - primitives + String (copied by value)
 *  - @RDMA classes (reference / handle)
 *  - functions / lambdas (registered + invoked back)
 *  - List (special-cased)
 *  - Unit (return-only)
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class RdmaType {
    @Serializable
    @SerialName("primitive")
    data class Primitive(val fqn: String) : RdmaType()

    @Serializable
    @SerialName("ref")
    data class Ref(val fqn: String) : RdmaType()

    @Serializable
    @SerialName("function")
    data class FunctionType(val parameters: List<RdmaTypeRef>, val returnType: RdmaTypeRef) : RdmaType()

    @Serializable
    @SerialName("list")
    data class ListType(val element: RdmaTypeRef) : RdmaType()

    @Serializable
    @SerialName("unit")
    object UnitType : RdmaType()
}

@Serializable
data class RdmaTypeRef(
    val type: RdmaType,
    val nullable: Boolean = false,
)
