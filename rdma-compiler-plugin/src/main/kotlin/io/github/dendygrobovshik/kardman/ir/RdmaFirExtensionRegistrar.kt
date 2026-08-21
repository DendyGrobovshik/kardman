package io.github.dendygrobovshik.kardman.ir

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar.ExtensionRegistrarContext
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class RdmaFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +FirAdditionalCheckersExtension.Factory { session -> RdmaAdditionalCheckersExtension(session) }
    }
}
