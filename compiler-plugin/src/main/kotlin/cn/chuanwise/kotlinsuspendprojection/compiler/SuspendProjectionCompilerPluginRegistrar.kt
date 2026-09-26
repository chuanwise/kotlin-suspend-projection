package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

class SuspendProjectionCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val pluginConfiguration = configuration.suspendProjectionConfiguration()

        IrGenerationExtension.registerExtension(
            SuspendProjectionIrGenerationExtension(pluginConfiguration),
        )
        FirExtensionRegistrarAdapter.registerExtension(
            SuspendProjectionFirExtensionRegistrar(pluginConfiguration),
        )
    }

    companion object {
        const val PLUGIN_ID: String = "cn.chuanwise.kotlinsuspendprojection.compiler"
    }
}
