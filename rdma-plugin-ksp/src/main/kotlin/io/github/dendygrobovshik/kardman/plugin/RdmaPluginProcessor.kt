package io.github.dendygrobovshik.kardman.plugin

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import java.io.File

class RdmaPluginProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val jsonFile = File(environment.options["rdmaClassesJson"] ?: return emptyList())
        if (!jsonFile.exists()) {
            logger.info("RdmaPluginProcessor: JSON not found at ${jsonFile.absolutePath}")
            return emptyList()
        }

        val rdmaTypes = RdmaTransformer.parseClassesJson(jsonFile.readText())
        if (rdmaTypes.isEmpty()) {
            logger.info("RdmaPluginProcessor: no @RDMA types in JSON")
            return emptyList()
        }

        logger.info("RdmaPluginProcessor: found ${rdmaTypes.size} @RDMA types from JSON: ${rdmaTypes.map { it.simpleName }}")

        for (file in resolver.getAllFiles()) {
            val code = try {
                File(file.filePath).readText()
            } catch (e: Exception) {
                continue
            }

            if (!rdmaTypes.any { code.contains(it.simpleName) }) continue

            val transformed = RdmaTransformer.transformCode(code, rdmaTypes)
            if (transformed == code) continue

            val outputFile = file.fileName.replace(".kt", "_rdma")
            val out = try {
                codeGenerator.createNewFile(Dependencies(true, file), "plugin", outputFile, "kt")
            } catch (e: Exception) {
                logger.info("Skip (already generated): $outputFile")
                continue
            }
            out.bufferedWriter().use { it.write(transformed) }
            logger.info("Generated: $outputFile")
        }

        return emptyList()
    }
}
