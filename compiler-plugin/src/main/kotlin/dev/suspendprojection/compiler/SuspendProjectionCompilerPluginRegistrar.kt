package dev.suspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

class SuspendProjectionCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val enforcement = configuration[
            SuspendProjectionConfiguration.DIRECT_IMPLEMENTATION_ENFORCEMENT,
        ]?.let(DirectImplementationEnforcement::parse)
            ?: DirectImplementationEnforcement.GUARDED_DEFAULT
        val emitCompatibilityGuard = configuration[
            SuspendProjectionConfiguration.EMIT_COMPATIBILITY_GUARD,
        ] ?: true
        val emitInvalidPathGuard = configuration[
            SuspendProjectionConfiguration.EMIT_INVALID_PATH_GUARD,
        ] ?: true

        IrGenerationExtension.registerExtension(
            SuspendProjectionIrGenerationExtension(
                enforcement,
                emitCompatibilityGuard,
                emitInvalidPathGuard,
            ),
        )
        FirExtensionRegistrarAdapter.registerExtension(
            SuspendProjectionFirExtensionRegistrar(),
        )
    }

    companion object {
        const val PLUGIN_ID: String = "dev.suspendprojection.compiler"
    }
}
