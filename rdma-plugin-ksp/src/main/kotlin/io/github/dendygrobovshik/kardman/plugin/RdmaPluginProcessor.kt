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
        val jsonPath = environment.options["rdmaClassesJson"] ?: return emptyList()
        val jsonFile = File(jsonPath)
        if (!jsonFile.exists()) return emptyList()

        val rdmaTypes = RdmaTransformer.parseClassesJson(jsonFile.readText())
        if (rdmaTypes.isEmpty()) return emptyList()

        logger.info("RdmaPluginProcessor: found ${rdmaTypes.size} @RDMA types: ${rdmaTypes.map { it.simpleName }}")

        for (file in resolver.getAllFiles()) {
            val originalCode = File(file.filePath).readText()
            var code = originalCode

            // Phase 1: Inheritance (text-based, class declarations are regular)
            for (type in rdmaTypes) {
                code = RdmaTransformer.transformInheritance(code, type.simpleName)
            }

            // Phase 2: Constructor calls and property access (scanner-based, not regex)
            code = RdmaTransformer.transformCode(code, rdmaTypes)

            if (code == originalCode || code.isEmpty()) continue

            val outputName = file.fileName.replace(".kt", "_rdma")
            val out = try {
                codeGenerator.createNewFile(Dependencies(true, file), "plugin", outputName, "kt")
            } catch (e: Exception) {
                logger.info("Skip (already generated): $outputName")
                continue
            }
            out.bufferedWriter().use { it.write(code) }
            logger.info("Generated: $outputName")
        }

        return emptyList()
    }
}
