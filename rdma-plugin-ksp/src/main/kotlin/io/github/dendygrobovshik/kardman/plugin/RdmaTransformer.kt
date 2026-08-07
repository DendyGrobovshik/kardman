package io.github.dendygrobovshik.kardman.plugin

object RdmaTransformer {
    data class RdmaType(
        val simpleName: String,
        val constructorParams: List<Pair<String, String>>,
        val properties: List<Pair<String, Boolean>>,
    )

    fun parseClassesJson(json: String): List<RdmaType> {
        val result = mutableListOf<RdmaType>()
        val nameValueRegex = Regex(""""name":"([^"]+)"""")
        val typeValueRegex = Regex(""""type":"([^"]+)"""")
        val mutableRegex = Regex(""""isMutable":(true|false)""")
        var depth = 0; var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val block = json.substring(start, i + 1)
                        val name = nameValueRegex.find(block)?.groupValues?.get(1) ?: ""
                        val params = mutableListOf<Pair<String, String>>()
                        Regex(""""parameters":\[([^\]]*)\]""").find(block)?.let { cm ->
                            Regex("""\{[^}]*"type":"([^"]+)"[^}]*}""").findAll(cm.groupValues[1]).forEach { pm ->
                                val tn = typeValueRegex.find(pm.value)?.groupValues?.get(1) ?: "Any"
                                params.add("arg" to tn.substringAfterLast("."))
                            }
                        }
                        val propNames = mutableListOf<String>()
                        Regex(""""properties":\[([^\]]*)\]""").find(block)?.let { pm ->
                            nameValueRegex.findAll(pm.groupValues[1]).forEach { propNames.add(it.groupValues[1]) }
                        }
                        val mutableFlags = mutableListOf<Boolean>()
                        val propsBlock = Regex(""""properties":\[([^\]]*)\]""").find(block)?.groupValues?.get(1) ?: ""
                        mutableRegex.findAll(propsBlock).forEach { mutableFlags.add(it.groupValues[1] == "true") }
                        result.add(RdmaType(name, params, propNames.mapIndexed { idx, n -> n to (mutableFlags.getOrElse(idx) { false }) }))
                    }
                }
            }
        }
        return result
    }

    fun transformCode(source: String, rdmaTypes: List<RdmaType>): String {
        if (rdmaTypes.isEmpty()) return source

        val names = rdmaTypes.map { it.simpleName }.toSet()
        val typeByName = rdmaTypes.associateBy { it.simpleName }
        val mutableProps = rdmaTypes.flatMap { t -> t.properties.filter { it.second }.map { t.simpleName to it.first } }
        val allProps = rdmaTypes.flatMap { t -> t.properties.map { t.simpleName to it.first } }

        val result = StringBuilder()
        var i = 0
        val rdmaVars = mutableSetOf<String>()

        while (i < source.length) {
            val remaining = source.substring(i)

            // 1. Import removal
            val importSkipped = tryRemoveImport(remaining, names)
            if (importSkipped > 0) {
                i += importSkipped
                continue
            }

            // 2. Variable declaration: val/var NAME = TypeName(...)
            val varDecl = findRdmaVarDecl(remaining, typeByName)
            if (varDecl != null) {
                result.append(source.substring(i, i + varDecl.prefixLength))
                result.append(varDecl.jsCode)
                i += varDecl.endOffset
                rdmaVars.add(varDecl.varName)
                continue
            }

            // 3. Standalone constructor call: TypeName(...)
            val ctorMatch = findConstructorCall(remaining, typeByName)
            if (ctorMatch != null) {
                result.append(ctorMatch.jsCode)
                i += ctorMatch.endOffset
                continue
            }

            // 4. Property access on tracked RDMA variables
            val propAccess = findRdmaPropertyAccess(remaining, rdmaVars, allProps, mutableProps)
            if (propAccess != null) {
                result.append(source.substring(i, i + propAccess.prefixLength))
                result.append(propAccess.replacement)
                i += propAccess.endOffset
                continue
            }

            // 4b. Fallback: property access by pattern (non-tracked variables)
            val propFallback = findPropertyAccessByPattern(remaining, allProps, mutableProps)
            if (propFallback != null) {
                result.append(source.substring(i, i + propFallback.prefixLength))
                result.append(propFallback.replacement)
                i += propFallback.endOffset
                continue
            }

            result.append(source[i])
            i++
        }

        return result.toString()
    }

    data class CtorMatchResult(val jsCode: String, val endOffset: Int)
    data class VarDeclResult(val varName: String, val jsCode: String, val prefixLength: Int, val endOffset: Int)
    data class PropAccessResult(val prefixLength: Int, val replacement: String, val endOffset: Int)

    private fun tryRemoveImport(text: String, names: Set<String>): Int {
        for (name in names) {
            val importRx = Regex("""^import\s+\S+\.$name\s*\n""")
            val match = importRx.find(text) ?: continue
            return match.value.length
        }
        return 0
    }

    private fun findConstructorCall(text: String, types: Map<String, RdmaType>): CtorMatchResult? {
        for ((name, type) in types) {
            if (!text.startsWith(name) || (text.length > name.length && text[name.length] != '(')) continue
            val argsStart = name.length + 1
            val argsEnd = findMatchingParen(text, argsStart - 1)
            if (argsEnd < 0) continue
            val argsText = text.substring(argsStart, argsEnd)
            return CtorMatchResult(buildJsConstructor(name, argsText, type), argsEnd + 1)
        }
        return null
    }

    private fun findRdmaVarDecl(text: String, types: Map<String, RdmaType>): VarDeclResult? {
        val valMatch = Regex("""(val|var)\s+(\w+)\s*=\s*""").find(text) ?: return null
        val prefixLen = valMatch.range.last + 1
        val varName = valMatch.groupValues[2]
        val afterEq = text.substring(prefixLen)
        for ((ctorName, type) in types) {
            if (!afterEq.startsWith(ctorName) || (afterEq.length > ctorName.length && afterEq[ctorName.length] != '(')) continue
            val argsStart = ctorName.length + 1
            val argsEnd = findMatchingParen(afterEq, argsStart - 1)
            if (argsEnd < 0) continue
            val argsText = afterEq.substring(argsStart, argsEnd)
            return VarDeclResult(varName, buildJsConstructor(ctorName, argsText, type), prefixLen, prefixLen + argsEnd + 1)
        }
        return null
    }

    private fun findRdmaPropertyAccess(
        text: String, rdmaVars: Set<String>,
        allProps: List<Pair<String, String>>, mutableProps: List<Pair<String, String>>
    ): PropAccessResult? {
        for (varName in rdmaVars) {
            if (!text.startsWith(varName) || (text.length > varName.length && text[varName.length] != '.')) continue
            val afterDot = text.substring(varName.length + 1)

            // Setter: varName.property = value
            for ((_, propName) in mutableProps) {
                val setterRx = Regex("""^$propName\s*=\s*([^,})\n]+)""")
                val match = setterRx.find(afterDot) ?: continue
                val setterName = "set${propName.replaceFirstChar { it.uppercase() }}"
                return PropAccessResult(varName.length, ".$setterName(${match.groupValues[1]})", varName.length + 1 + match.value.length)
            }

            // Getter: varName.property (not followed by '(')
            for ((_, propName) in allProps) {
                if (!afterDot.startsWith(propName)) continue
                val afterProp = afterDot.substring(propName.length)
                if (afterProp.isNotEmpty() && afterProp[0] == '(') continue
                if (afterProp.isNotEmpty() && afterProp[0].isLetterOrDigit()) continue
                val getterName = "get${propName.replaceFirstChar { it.uppercase() }}"
                return PropAccessResult(varName.length, ".$getterName()", varName.length + 1 + propName.length)
            }
        }
        return null
    }

    private fun findPropertyAccessByPattern(
        text: String, allProps: List<Pair<String, String>>, mutableProps: List<Pair<String, String>>
    ): PropAccessResult? {
        for ((_, propName) in mutableProps) {
            val setterRx = Regex("""^\.$propName\s*=\s*([^,})\n]+)""")
            val match = setterRx.find(text) ?: continue
            val setterName = "set${propName.replaceFirstChar { it.uppercase() }}"
            return PropAccessResult(0, ".$setterName(${match.groupValues[1]})", match.value.length)
        }
        for ((_, propName) in allProps) {
            if (!text.startsWith(".$propName")) continue
            val afterProp = text.substring(propName.length + 1)
            if (afterProp.isNotEmpty() && afterProp[0] == '(') continue
            if (afterProp.isNotEmpty() && afterProp[0].isLetterOrDigit()) continue
            val getterName = "get${propName.replaceFirstChar { it.uppercase() }}"
            return PropAccessResult(0, ".$getterName()", propName.length + 1)
        }
        return null
    }

    private fun buildJsConstructor(typeName: String, argsText: String, type: RdmaType): String {
        val args = splitArgs(argsText)
        val quoted = args.mapIndexed { i, arg ->
            if (i < type.constructorParams.size && type.constructorParams[i].second == "String") {
                val stripped = arg.trim().removeSurrounding("\"")
                "'$stripped'"
            } else arg.trim()
        }
        return "js(\"RDMA.create${typeName}(${quoted.joinToString(", ")})\")"
    }

    private fun splitArgs(text: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        for (ch in text) {
            when {
                ch == '"' -> { inString = !inString; current.append(ch) }
                !inString && ch == '(' -> { depth++; current.append(ch) }
                !inString && ch == ')' -> { depth--; current.append(ch) }
                !inString && depth == 0 && ch == ',' -> {
                    args.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) args.add(current.toString().trim())
        return args
    }

    private fun findMatchingParen(text: String, openIdx: Int): Int {
        var depth = 0
        var inString = false
        for (i in openIdx until text.length) {
            when {
                text[i] == '"' -> inString = !inString
                !inString && text[i] == '(' -> depth++
                !inString && text[i] == ')' -> {
                    if (--depth == 0) return i
                }
            }
        }
        return -1
    }

    fun transformInheritance(code: String, parentName: String): String {
        val classRegex = Regex("""class\s+(\w+)\s*\(([^)]*)\)\s*:\s*$parentName\s*\(([^)]*)\)\s*\{""")
        val match = classRegex.find(code) ?: return code
        val childName = match.groupValues[1]
        var depth = 1
        var bodyEnd = match.range.last + 1
        for (i in bodyEnd until code.length) {
            if (code[i] == '{') depth++
            else if (code[i] == '}') { depth--; if (depth == 0) { bodyEnd = i; break } }
        }
        val classBody = code.substring(match.range.last + 1, bodyEnd)
        val overrides = mutableListOf<Pair<String, String>>()
        val overrideRegex = Regex("""override\s+fun\s+(\w+)\s*\(([^)]*)\)\s*:\s*\w+\s*=\s*(.+?)\s*$""", RegexOption.MULTILINE)
        overrideRegex.findAll(classBody).forEach { m ->
            overrides.add(m.groupValues[1] to m.groupValues[3].trim().trim('"'))
        }
        val result = code.substring(0, match.range.first) + code.substring(bodyEnd + 1)
        val ctorRegex = Regex("""$childName\s*\(([^)]*)\)""")
        return result.replace(ctorRegex) { ctorMatch ->
            val args = ctorMatch.groupValues[1]
            val overridesJs = overrides.joinToString(", ") { (n, b) -> "$n: function() { return \"$b\"; }" }
            "js(\"\"\"RDMA.createWithOverrides('$parentName', [$args], { $overridesJs })\"\"\")"
        }
    }
}
