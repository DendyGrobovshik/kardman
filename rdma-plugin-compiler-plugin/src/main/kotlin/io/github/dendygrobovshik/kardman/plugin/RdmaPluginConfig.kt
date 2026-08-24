package io.github.dendygrobovshik.kardman.plugin

data class RdmaPluginType(
    val simpleName: String,
    val qualifiedName: String,
    val constructorParams: List<Pair<String, String>>,
    val properties: List<Pair<String, Boolean>>,
)

object RdmaPluginConfig {
    fun parseWidgetsJson(json: String): List<String> {
        val root = JsonParser(json).parse() ?: return emptyList()
        return (root as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }

    fun parseClassesJson(json: String): List<RdmaPluginType> {
        val root = JsonParser(json).parse() ?: return emptyList()
        val array = root as? List<*> ?: return emptyList()
        return array.mapNotNull { raw ->
            val obj = raw as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"] as? String ?: return@mapNotNull null
            val qualifiedName = obj["qualifiedName"] as? String ?: name

            val constructorParams = (obj["constructors"] as? List<*>)
                ?.firstOrNull()
                ?.let { ctor -> (ctor as? Map<*, *>)?.get("parameters") as? List<*> }
                ?.mapNotNull { param ->
                    val p = param as? Map<*, *> ?: return@mapNotNull null
                    val pName = p["name"] as? String ?: "arg"
                    val pType = p["type"] as? String ?: "kotlin.Any"
                    pName to pType
                }
                ?: emptyList()

            val properties = (obj["properties"] as? List<*>)
                ?.mapNotNull { prop ->
                    val p = prop as? Map<*, *> ?: return@mapNotNull null
                    val pName = p["name"] as? String ?: return@mapNotNull null
                    val isMutable = (p["isMutable"] as? Boolean) ?: false
                    pName to isMutable
                }
                ?: emptyList()

            RdmaPluginType(name, qualifiedName, constructorParams, properties)
        }
    }

    private class JsonParser(private val json: String) {
        private var i = 0

        fun parse(): Any? = parseValue()

        private fun parseValue(): Any? {
            skipWs()
            if (i >= json.length) return null
            return when (json[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            i++
            val map = mutableMapOf<String, Any?>()
            skipWs()
            if (json[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(json[i] == ':') { "Expected ':' at $i" }
                i++
                map[key] = parseValue()
                skipWs()
                when (json[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return map }
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++
            val list = mutableListOf<Any?>()
            skipWs()
            if (json[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (json[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return list }
                }
            }
        }

        private fun parseString(): String {
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i]
                if (c == '"') { i++; return sb.toString() }
                if (c == '\\') {
                    i++
                    when (json[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < json.length && (json[i].isDigit() || json[i] in "-+eE.")) i++
            return json.substring(start, i).toDoubleOrNull() ?: 0.0
        }

        private fun skipWs() {
            while (i < json.length && json[i].isWhitespace()) i++
        }
    }
}
