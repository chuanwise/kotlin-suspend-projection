package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal class SuspendProjectionIrGenerationExtension(
    private val configuration: SuspendProjectionPluginConfiguration,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (configuration.hasOwnerSurface) {
            SameNameBlockingProjectionGenerator(pluginContext, configuration).generate(moduleFragment)
        }
        if (configuration.enabledImports.isNotEmpty()) {
            ViaBlockingProjectionIrGenerator(pluginContext, configuration).generate(moduleFragment)
        }
    }
}
