package io.github.dendygrobovshik.kardman.plugin

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar.ExtensionRegistrarContext
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class RdmaPluginFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +FirAdditionalCheckersExtension.Factory { session -> RdmaPluginAdditionalCheckersExtension(session) }
    }
}
