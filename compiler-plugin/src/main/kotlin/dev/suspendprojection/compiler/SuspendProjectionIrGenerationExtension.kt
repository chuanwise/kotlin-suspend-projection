package dev.suspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal class SuspendProjectionIrGenerationExtension(
    private val enforcement: DirectImplementationEnforcement,
    private val emitCompatibilityGuard: Boolean,
    private val emitInvalidPathGuard: Boolean,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        ViaBlockingProjectionIrGenerator(pluginContext).generate(moduleFragment)
        SameNameBlockingProjectionGenerator(
            pluginContext,
            enforcement,
            emitCompatibilityGuard,
            emitInvalidPathGuard,
        ).generate(moduleFragment)
    }
}
