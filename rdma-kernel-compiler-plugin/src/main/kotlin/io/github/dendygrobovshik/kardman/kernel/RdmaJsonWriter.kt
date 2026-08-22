package io.github.dendygrobovshik.kardman.kernel

object RdmaJsonWriter {
    fun write(classInfos: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        classInfos.forEachIndexed { i, info ->
            sb.append("  {")
            sb.append("\"name\":\"${info.className}\",")
            sb.append("\"qualifiedName\":\"${info.qualifiedName}\",")
            sb.append("\"constructors\":[")
            info.constructors.forEachIndexed { ci, ctor ->
                sb.append("{\"parameters\":[")
                ctor.parameters.forEachIndexed { pi, param ->
                    sb.append(param.toJson())
                    if (pi < ctor.parameters.size - 1) sb.append(",")
                }
                sb.append("]}")
                if (ci < info.constructors.size - 1) sb.append(",")
            }
            sb.append("],")
            sb.append("\"methods\":[")
            info.methods.forEachIndexed { mi, method ->
                sb.append("{\"name\":\"${method.name}\",\"returnType\":\"${method.returnType}\",\"nullableReturn\":${method.nullableReturn},\"isList\":${method.isList},\"listElementType\":\"${method.listElementType ?: ""}\",\"vtableId\":${method.vtableId},\"parameters\":[")
                method.parameters.forEachIndexed { pi, param ->
                    sb.append(param.toJson())
                    if (pi < method.parameters.size - 1) sb.append(",")
                }
                sb.append("]}")
                if (mi < info.methods.size - 1) sb.append(",")
            }
            sb.append("],")
            sb.append("\"properties\":[")
            info.properties.forEachIndexed { j, prop ->
                sb.append("{\"name\":\"${prop.name}\",\"type\":\"${prop.type}\",\"isMutable\":${prop.isMutable},\"nullable\":${prop.nullable}}")
                if (j < info.properties.size - 1) sb.append(",")
            }
            sb.append("]}")
            if (i < classInfos.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("]")
        return sb.toString()
    }

    private fun ParameterInfo.toJson(): String =
        "{\"name\":\"$name\",\"type\":\"$type\",\"nullable\":$nullable,\"isList\":$isList,\"listElementType\":\"${listElementType ?: ""}\"}"
}
