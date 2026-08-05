package io.github.dendygrobovshik.kardman.plugin

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.File

class RdmaPluginProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val jsonFile = File(environment.options["rdmaClassesJson"] ?: return emptyList())
        if (!jsonFile.exists()) return emptyList()

        val rdmaTypes = RdmaTransformer.parseClassesJson(jsonFile.readText())
        if (rdmaTypes.isEmpty()) return emptyList()

        logger.info("RdmaPluginProcessor: found ${rdmaTypes.size} @RDMA types: ${rdmaTypes.map { it.simpleName }}")

        val rdmaNames = rdmaTypes.map { it.simpleName }.toSet()

        for (file in resolver.getAllFiles()) {
            var code = File(file.filePath).readText()

            // 1. Handle class inheritance: class Child : Parent (must run first)
            for (decl in file.declarations) {
                if (decl is KSClassDeclaration) {
                    val superType = decl.superTypes.firstOrNull {
                        it.resolve().declaration.qualifiedName?.asString()?.substringAfterLast(".") in rdmaNames
                    }
                    if (superType != null && decl.simpleName.asString() !in rdmaNames) {
                        val parentName = superType.resolve().declaration.qualifiedName!!.asString().substringAfterLast(".")
                        code = transformInheritance(code, decl.simpleName.asString(), parentName)
                    }
                }
            }

            // 2. Regular @RDMA usage (constructors, properties)
            if (rdmaNames.any { code.contains(it) }) {
                code = RdmaTransformer.transformCode(code, rdmaTypes)
            }

            val originalCode = File(file.filePath).readText()
            if (code != originalCode && code.isNotEmpty()) {
                val outputFile = file.fileName.replace(".kt", "_rdma")
                val out = try {
                    codeGenerator.createNewFile(Dependencies(true, file), "plugin", outputFile, "kt")
                } catch (e: Exception) {
                    logger.info("Skip: $outputFile")
                    continue
                }
                out.bufferedWriter().use { it.write(code) }
                logger.info("Generated: $outputFile")
            }
        }

        return emptyList()
    }

    private fun transformInheritance(code: String, childName: String, parentName: String): String {
        // Find the class block
        val classPattern = Regex("""class\s+$childName\s*\([^)]*\)\s*:\s*$parentName\s*\([^)]*\)\s*\{""")
        val match = classPattern.find(code) ?: return code
        
        val classBodyStart = match.range.last + 1
        // Find matching closing brace
        var depth = 1
        var classBodyEnd = classBodyStart
        for (i in classBodyStart until code.length) {
            if (code[i] == '{') depth++
            else if (code[i] == '}') {
                depth--
                if (depth == 0) { classBodyEnd = i; break }
            }
        }
        
        val classBody = code.substring(classBodyStart, classBodyEnd)
        
        // Extract override methods with their bodies
        val overrides = mutableListOf<Pair<String, String>>()
        val overridePattern = Regex("""override\s+fun\s+(\w+)\s*\([^)]*\)\s*:\s*\w+\s*=\s*(.+)\s*""")
        overridePattern.findAll(classBody).forEach { m ->
            val methodName = m.groupValues[1]
            val body = m.groupValues[2].trim().trim('"').replace("\"", "\\\"")
            overrides.add(methodName to body)
        }
        
        // Remove the class declaration from code
        val beforeClass = code.substring(0, match.range.first)
        val afterClass = code.substring(classBodyEnd + 1)
        var result = beforeClass + afterClass
        
        // Replace Child(...) calls with createWithOverrides
        val ctorPattern = Regex("""$childName\s*\(([^)]*)\)""")
        result = result.replace(ctorPattern) { ctorMatch ->
            val args = ctorMatch.groupValues[1]
            val overridesJs = overrides.joinToString(", ") { (name, body) ->
                "$name: function() { return \"$body\"; }"
            }
            """js("RDMA.createWithOverrides('$parentName', [$args], { $overridesJs })")"""
        }
        
        return result
    }
}
