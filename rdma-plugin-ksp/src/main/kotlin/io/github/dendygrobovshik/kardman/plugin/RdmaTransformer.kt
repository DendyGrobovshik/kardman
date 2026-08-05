package io.github.dendygrobovshik.kardman.plugin

object RdmaTransformer {
    data class RdmaType(
        val simpleName: String,
        val constructorParams: List<Pair<String, String>>,
        val properties: List<String>,
    )

    fun parseClassesJson(json: String): List<RdmaType> {
        val result = mutableListOf<RdmaType>()
        val nameValueRegex = Regex(""""name":"([^"]+)"""")
        val typeValueRegex = Regex(""""type":"([^"]+)"""")

        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val block = json.substring(start, i + 1)
                        val name = nameValueRegex.find(block)?.groupValues?.get(1) ?: ""

                        val params = mutableListOf<Pair<String, String>>()
                        val ctorMatch = Regex(""""parameters":\[([^\]]*)\]""").find(block)
                        if (ctorMatch != null) {
                            val ctorBlock = ctorMatch.groupValues[1]
                            val paramMatches = Regex("""\{[^}]*"type":"([^"]+)"[^}]*}""").findAll(ctorBlock)
                            paramMatches.forEach { pm ->
                                val typeName = typeValueRegex.find(pm.value)?.groupValues?.get(1) ?: "Any"
                                val short = typeName.substringAfterLast(".")
                                params.add("arg" to short)
                            }
                        }

                        val props = mutableListOf<String>()
                        val propsMatch = Regex(""""properties":\[([^\]]*)\]""").find(block)
                        if (propsMatch != null) {
                            val propsBlock = propsMatch.groupValues[1]
                            nameValueRegex.findAll(propsBlock).forEach { nm ->
                                props.add(nm.groupValues[1])
                            }
                        }

                        result.add(RdmaType(name, params, props))
                    }
                }
            }
        }
        return result
    }

    fun transformCode(code: String, rdmaTypes: List<RdmaType>): String {
        var result = code

        for (type in rdmaTypes) {
            result = result.replace(Regex("""import\s+\S+\.${type.simpleName}\s*\n"""), "")

            val patterns = type.constructorParams.map { (_, pt) ->
                when (pt) {
                    "String" -> """"([^"]*)""""
                    "Int" -> """(\d+)"""
                    "Boolean" -> """(true|false)"""
                    "Long" -> """(\d+L?)"""
                    "Float", "Double" -> """(\d+\.?\d*)"""
                    else -> """([^,\s)]+)"""
                }
            }
            val rx = Regex("""${type.simpleName}\s*\(\s*${patterns.joinToString("""\s*,\s*""")}\s*\)""")

            result = result.replace(rx) { m ->
                val args = type.constructorParams.mapIndexed { i, (_, pt) ->
                    val v = m.groupValues[i + 1]
                    if (pt == "String") "'$v'" else v
                }
                "js(\"RDMA.create${type.simpleName}(${args.joinToString(", ")})\")"
            }

            for (prop in type.properties) {
                val getter = "get${prop.replaceFirstChar { it.uppercase() }}"
                result = result.replace(Regex("""\.$prop\b(?!\()""")) { ".$getter()" }
            }
        }
        return result
    }
}
