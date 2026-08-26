package io.github.dendygrobovshik.kardman.kernel

object RdmaJsonWriter {
    fun writeManifest(classInfos: List<RdmaClassInfo>, functionInfos: List<RdmaFunctionInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        val entries = mutableListOf<String>()
        entries += classInfos.map { it.toJson() }
        entries += functionInfos.map { it.toJson() }
        entries.forEachIndexed { i, entry ->
            sb.append(entry)
            if (i < entries.size - 1) sb.appendLine(",")
        }
        sb.appendLine()
        sb.appendLine("]")
        return sb.toString()
    }

    private fun RdmaClassInfo.toJson(): String {
        val sb = StringBuilder()
        sb.append("  {\"kind\":\"class\",")
        sb.append("\"name\":\"$className\",")
        sb.append("\"qualifiedName\":\"$qualifiedName\",")
        sb.append("\"constructors\":[")
        constructors.forEachIndexed { ci, ctor ->
            sb.append("{\"parameters\":[")
            ctor.parameters.forEachIndexed { pi, param ->
                sb.append(param.toJson())
                if (pi < ctor.parameters.size - 1) sb.append(",")
            }
            sb.append("]}")
            if (ci < constructors.size - 1) sb.append(",")
        }
        sb.append("],")
        sb.append("\"methods\":[")
        methods.forEachIndexed { mi, method ->
            sb.append("{\"name\":\"${method.name}\",\"returnType\":\"${method.returnType}\",\"nullableReturn\":${method.nullableReturn},\"isList\":${method.isList},\"listElementType\":\"${method.listElementType ?: ""}\",\"vtableId\":${method.vtableId},\"parameters\":[")
            method.parameters.forEachIndexed { pi, param ->
                sb.append(param.toJson())
                if (pi < method.parameters.size - 1) sb.append(",")
            }
            sb.append("]}")
            if (mi < methods.size - 1) sb.append(",")
        }
        sb.append("],")
        sb.append("\"properties\":[")
        properties.forEachIndexed { j, prop ->
            sb.append("{\"name\":\"${prop.name}\",\"type\":\"${prop.type}\",\"isMutable\":${prop.isMutable},\"nullable\":${prop.nullable}}")
            if (j < properties.size - 1) sb.append(",")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun RdmaFunctionInfo.toJson(): String {
        val sb = StringBuilder()
        sb.append("  {\"kind\":\"function\",")
        sb.append("\"name\":\"$name\",")
        sb.append("\"qualifiedName\":\"$qualifiedName\",")
        sb.append("\"composable\":$composable,")
        sb.append("\"parameters\":[")
        parameters.forEachIndexed { pi, param ->
            sb.append("{\"name\":\"${param.name}\",\"type\":${param.type.toJson()}}")
            if (pi < parameters.size - 1) sb.append(",")
        }
        sb.append("],")
        sb.append("\"returnType\":${returnType.toJson()}")
        sb.append("}")
        return sb.toString()
    }

    private fun RdmaTypeRef.toJson(): String {
        val nullable = this.nullable
        return when (val t = this.type) {
            is RdmaType.Primitive -> "{\"kind\":\"primitive\",\"fqn\":\"${t.fqn}\",\"nullable\":$nullable}"
            is RdmaType.Ref -> "{\"kind\":\"ref\",\"fqn\":\"${t.fqn}\",\"nullable\":$nullable}"
            is RdmaType.ListType -> "{\"kind\":\"list\",\"nullable\":$nullable,\"element\":${t.element.toJson()}}"
            is RdmaType.FunctionType -> "{\"kind\":\"function\",\"nullable\":$nullable,\"parameters\":[${t.parameters.joinToString(",") { it.toJson() }}],\"return\":${t.returnType.toJson()}}"
            is RdmaType.UnitType -> "{\"kind\":\"unit\",\"nullable\":$nullable}"
        }
    }

    private fun ParameterInfo.toJson(): String =
        "{\"name\":\"$name\",\"type\":\"$type\",\"nullable\":$nullable,\"isList\":$isList,\"listElementType\":\"${listElementType ?: ""}\"}"
}
