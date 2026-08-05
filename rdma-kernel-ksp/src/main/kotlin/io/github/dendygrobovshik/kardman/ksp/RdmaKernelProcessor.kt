package io.github.dendygrobovshik.kardman.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier

class RdmaKernelProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val rdmaAnnotation = "io.github.dendygrobovshik.kardman.RDMA"
        val rdmaSymbols = resolver.getSymbolsWithAnnotation(rdmaAnnotation)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (rdmaSymbols.isEmpty()) return emptyList()

        logger.info("RdmaKernelProcessor: found ${rdmaSymbols.size} @RDMA classes")

        val classInfos = rdmaSymbols.map { extractClassInfo(it) }
        logger.info("Class infos: ${classInfos.map { "${it.className} (ctor params: ${it.constructors.map { c -> c.parameters.size }}, props: ${it.properties.size})" }}")

        val cppGenerator = CppGenerator { fileName, _ ->
            try {
                codeGenerator.createNewFile(Dependencies(true), "cpp", fileName, "")
            } catch (e: Exception) {
                java.io.ByteArrayOutputStream()
            }
        }
        cppGenerator.generate(classInfos)

        writeClassesJson(classInfos)
        writeRdmaVtableFile()
        writeKotlinProxies(rdmaSymbols, classInfos)

        return emptyList()
    }

    private fun writeKotlinProxies(
        symbols: List<KSClassDeclaration>,
        classInfos: List<RdmaClassInfo>
    ) {
        val infoByClass = classInfos.associateBy { it.className }

        for (cls in symbols) {
            val info = infoByClass[cls.simpleName.asString()] ?: continue
            val filePath = cls.containingFile?.filePath ?: continue
            val originalCode = try {
                java.io.File(filePath).readText()
            } catch (e: Exception) { continue }

            val transformed = rewriteKotlinClass(originalCode, info, cls)
            if (transformed == originalCode) continue

            val outputFileName = cls.simpleName.asString() + "_rdma"
            val out = try {
                codeGenerator.createNewFile(
                    Dependencies(true, cls.containingFile!!),
                    "kotlin",
                    outputFileName,
                    "kt"
                )
            } catch (e: Exception) {
                logger.info("Skip (already generated): $outputFileName")
                continue
            }
            out.bufferedWriter().use { it.write(transformed) }
            logger.info("Generated Kotlin proxy: $outputFileName")
        }
    }

    private fun writeClassesJson(classInfos: List<RdmaClassInfo>) {
        val sb = StringBuilder()
        sb.appendLine("[")
        classInfos.forEachIndexed { i, info ->
            sb.append("  {")
            sb.append("\"name\":\"${info.className}\",")
            sb.append("\"constructors\":[")
            info.constructors.forEachIndexed { ci, ctor ->
                sb.append("{\"parameters\":[")
                ctor.parameters.forEachIndexed { pi, param ->
                    sb.append("{\"name\":\"${param.name}\",\"type\":\"${param.type}\",\"nullable\":${param.nullable}}")
                    if (pi < ctor.parameters.size - 1) sb.append(",")
                }
                sb.append("]}")
                if (ci < info.constructors.size - 1) sb.append(",")
            }
            sb.append("],")
            sb.append("\"methods\":[")
            info.methods.forEachIndexed { mi, method ->
                sb.append("{\"name\":\"${method.name}\",\"returnType\":\"${method.returnType}\",\"nullableReturn\":${method.nullableReturn},\"parameters\":[")
                method.parameters.forEachIndexed { pi, param ->
                    sb.append("{\"name\":\"${param.name}\",\"type\":\"${param.type}\",\"nullable\":${param.nullable}}")
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

        val out = try {
            codeGenerator.createNewFile(Dependencies(true), "cpp", "rdma_classes.json", "")
        } catch (e: Exception) {
            logger.info("Skip JSON (already generated)")
            return
        }
        out.bufferedWriter().use { it.write(sb.toString()) }
    }

    private fun extractClassInfo(cls: KSClassDeclaration): RdmaClassInfo {
        val packageName = cls.packageName.asString()
        val className = cls.simpleName.asString()
        val qualifiedName = cls.qualifiedName?.asString() ?: "$packageName.$className"

        val constructors = cls.primaryConstructor?.let { ctor ->
            val params = ctor.parameters.map { param ->
                val resolved = param.type.resolve()
                val typeName = resolved.declaration.qualifiedName?.asString() ?: "kotlin.Any"
                ParameterInfo(
                    name = param.name?.asString() ?: "arg",
                    type = typeName,
                    nullable = resolved.isMarkedNullable
                )
            }
            listOf(ConstructorInfo(params))
        } ?: emptyList()

        val methods = cls.getAllFunctions().filter { func ->
            val name = func.simpleName.asString()
            name !in setOf("<init>", "equals", "hashCode") &&
                !name.startsWith("component") && !name.startsWith("copy")
        }.map { func ->
            val resolvedReturn = func.returnType?.resolve()
            val returnType = resolvedReturn?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
            val params = func.parameters.map { param ->
                val resolved = param.type.resolve()
                val typeName = resolved.declaration.qualifiedName?.asString() ?: "kotlin.Any"
                ParameterInfo(
                    name = param.name?.asString() ?: "arg",
                    type = typeName,
                    nullable = resolved.isMarkedNullable
                )
            }
            MethodInfo(func.simpleName.asString(), returnType, params,
                nullableReturn = resolvedReturn?.isMarkedNullable ?: false,
                isOpen = Modifier.OPEN in func.modifiers)
        }.toList()

        val properties = cls.getAllProperties()
            .filter { !it.simpleName.asString().startsWith("__") }
            .map { prop ->
            val resolved = prop.type.resolve()
            PropertyInfo(
                name = prop.simpleName.asString(),
                type = resolved.declaration.qualifiedName?.asString() ?: "kotlin.Any",
                isMutable = prop.isMutable,
                nullable = resolved.isMarkedNullable
            )
        }.toList()

        return RdmaClassInfo(packageName, className, qualifiedName, constructors, methods, properties)
    }

    private fun writeRdmaVtableFile() {
        val code = """
package com.example.kernel

external fun rdmaVtableDispatch(vtablePtr: Long, method: String): Any?
""".trimIndent()
        try {
            val out = codeGenerator.createNewFile(Dependencies(true), "kotlin", "RdmaVtable", "kt")
            out.bufferedWriter().use { it.write(code) }
        } catch (e: Exception) {
            logger.info("Skip RdmaVtable.kt")
        }
    }

    private fun rewriteKotlinClass(code: String, info: RdmaClassInfo, cls: KSClassDeclaration): String {
        val lines = code.lines().toMutableList()

        // Find class opening brace and insert __vtable field
        val className = info.className
        var classOpenIdx = -1
        for (i in lines.indices) {
            if (lines[i].contains("class $className")) {
                for (j in i until lines.size) {
                    if (lines[j].contains("{")) {
                        classOpenIdx = j
                        break
                    }
                }
                break
            }
        }

        if (classOpenIdx >= 0 && !lines.any { it.contains("__vtable") }) {
            val indent = lines[classOpenIdx].takeWhile { it == ' ' }
            val fieldLine = "$indent    @kotlin.jvm.JvmField\n$indent    var __vtable: Long = 0"
            lines.add(classOpenIdx + 1, fieldLine)
        }

        // Inject vtable-check into open methods
        val openMethods = info.methods.filter { it.isOpen }
        for (method in openMethods) {
            injectVtableCheck(lines, method)
        }

        return lines.joinToString("\n")
    }

    private fun injectVtableCheck(lines: MutableList<String>, method: MethodInfo) {
        var methodOpenIdx = -1
        for (i in lines.indices) {
            if (lines[i].trimStart().startsWith("open ") && lines[i].contains("fun ${method.name}(")) {
                for (j in i until lines.size) {
                    if (lines[j].contains("{")) {
                        methodOpenIdx = j
                        break
                    }
                }
                break
            }
        }
        if (methodOpenIdx < 0) return

        val indent = lines[methodOpenIdx].takeWhile { it == ' ' } + "    "
        val params = method.parameters.joinToString(", ") { it.name }
        val paramCall = if (params.isEmpty()) "" else ", $params"

        val returnCast = when (method.returnType) {
            "kotlin.Unit" -> ""
            "kotlin.String" -> " as String"
            "kotlin.Int" -> " as Int"
            "kotlin.Boolean" -> " as Boolean"
            "kotlin.Long" -> " as Long"
            "kotlin.Double" -> " as Double"
            "kotlin.Float" -> " as Float"
            else -> ""
        }
        val returnKeyword = if (method.returnType == "kotlin.Unit") "" else "return "

        val vtableLines = listOf(
            "$indent if (__vtable != 0L) {",
            "$indent     val __r = rdmaVtableDispatch(__vtable, \"${method.name}\"$paramCall)",
            "$indent     if (__r != null) ${returnKeyword}__r$returnCast",
            "$indent }",
        )
        lines.addAll(methodOpenIdx + 1, vtableLines)
    }
}
