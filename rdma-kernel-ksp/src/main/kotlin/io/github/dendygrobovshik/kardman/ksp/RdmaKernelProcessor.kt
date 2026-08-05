package io.github.dendygrobovshik.kardman.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

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
            codeGenerator.createNewFile(Dependencies(false), "cpp", fileName, "")
        }
        cppGenerator.generate(classInfos)

        writeClassesJson(classInfos)

        return emptyList()
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
                    sb.append("{\"name\":\"${param.name}\",\"type\":\"${param.type}\"}")
                    if (pi < ctor.parameters.size - 1) sb.append(",")
                }
                sb.append("]}")
                if (ci < info.constructors.size - 1) sb.append(",")
            }
            sb.append("],")
            sb.append("\"properties\":[")
            info.properties.forEachIndexed { j, prop ->
                sb.append("{\"name\":\"${prop.name}\",\"type\":\"${prop.type}\"}")
                if (j < info.properties.size - 1) sb.append(",")
            }
            sb.append("]}")
            if (i < classInfos.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("]")

        val out = codeGenerator.createNewFile(Dependencies(false), "cpp", "rdma_classes.json", "")
        out.bufferedWriter().use { it.write(sb.toString()) }
    }

    private fun extractClassInfo(cls: KSClassDeclaration): RdmaClassInfo {
        val packageName = cls.packageName.asString()
        val className = cls.simpleName.asString()
        val qualifiedName = cls.qualifiedName?.asString() ?: "$packageName.$className"

        val constructors = cls.primaryConstructor?.let { ctor ->
            val params = ctor.parameters.map { param ->
                val typeName = param.type.resolve().declaration.qualifiedName?.asString() ?: "kotlin.Any"
                ParameterInfo(name = param.name?.asString() ?: "arg", type = typeName)
            }
            listOf(ConstructorInfo(params))
        } ?: emptyList()

        val methods = cls.getAllFunctions().filter { func ->
            val name = func.simpleName.asString()
            name !in setOf("<init>", "equals", "hashCode") &&
                !name.startsWith("component") && !name.startsWith("copy")
        }.map { func ->
            val returnType = func.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
            val params = func.parameters.map { param ->
                val typeName = param.type.resolve().declaration.qualifiedName?.asString() ?: "kotlin.Any"
                ParameterInfo(name = param.name?.asString() ?: "arg", type = typeName)
            }
            MethodInfo(func.simpleName.asString(), returnType, params)
        }.toList()

        val properties = cls.getAllProperties().map { prop ->
            PropertyInfo(
                name = prop.simpleName.asString(),
                type = prop.type.resolve().declaration.qualifiedName?.asString() ?: "kotlin.Any",
                isMutable = prop.isMutable
            )
        }.toList()

        return RdmaClassInfo(packageName, className, qualifiedName, constructors, methods, properties)
    }
}
