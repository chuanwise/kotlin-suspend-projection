package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.FqName

internal data class ProjectionPolicy(
    val exports: Set<JvmProjection>,
    val imports: Set<JvmProjection>,
)

internal fun IrSimpleFunction.projectionPolicy(
    owner: IrClass,
    configuration: SuspendProjectionPluginConfiguration,
): ProjectionPolicy {
    val override = annotations.explicitProjectionOverride()
        ?: owner.annotations.explicitProjectionOverride()
        ?: owner.fileOrNull?.annotations?.explicitProjectionOverride()
    return if (override == null) {
        ProjectionPolicy(
            exports = configuration.enabledExports.toSet(),
            imports = configuration.enabledImports.toSet(),
        )
    } else {
        ProjectionPolicy(exports = override, imports = override)
    }
}

internal fun IrSimpleFunction.isProjectionSelected(
    owner: IrClass,
    configuration: SuspendProjectionPluginConfiguration,
): Boolean {
    val directive = annotations.projectionDirective()
        ?: owner.annotations.projectionDirective()
        ?: owner.fileOrNull?.annotations?.projectionDirective()
    return directive?.enable ?: (configuration.selectionMode == SelectionMode.ALL)
}

private data class ProjectionDirective(val enable: Boolean)

private fun List<IrConstructorCall>.projectionDirective(): ProjectionDirective? {
    val annotation = firstOrNull(::isJvmSuspendProjectionAnnotation) ?: return null
    val parameter = annotation.symbol.owner.parameters.singleOrNull {
        it.name.asString() == "enable"
    }
    val enable = (parameter?.let { annotation.arguments[it] } as? IrConst)?.value as? Boolean
    return ProjectionDirective(enable ?: true)
}

private fun List<IrConstructorCall>.explicitProjectionOverride(): Set<JvmProjection>? {
    val annotation = firstOrNull(::isJvmSuspendProjectionAnnotation) ?: return null
    val parameter = annotation.symbol.owner.parameters.singleOrNull {
        it.name.asString() == "projections"
    } ?: return null
    val argument = annotation.arguments[parameter] as? IrVararg ?: return null
    return argument.elements
        .filterIsInstance<IrGetEnumValue>()
        .mapTo(linkedSetOf()) { element ->
            JvmProjection.valueOf(element.symbol.owner.name.asString())
        }
        .takeIf(Set<JvmProjection>::isNotEmpty)
}

private fun isJvmSuspendProjectionAnnotation(annotation: IrConstructorCall): Boolean =
    (annotation.symbol.owner.parent as? IrClass)?.fqNameWhenAvailable ==
        SUSPEND_PROJECTION_FQ_NAME

private val SUSPEND_PROJECTION_FQ_NAME =
    FqName("cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection")
