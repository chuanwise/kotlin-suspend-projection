package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val JvmProjection.suffix: String
    get() = when (this) {
        JvmProjection.BLOCKING -> "Blocking"
        JvmProjection.COMPLETION_STAGE -> "CompletionStage"
        JvmProjection.COMPLETABLE_FUTURE -> "CompletableFuture"
        JvmProjection.FUTURE -> "Future"
        JvmProjection.NONE -> error("NONE has no projection suffix")
    }

internal val JvmProjection.viaTypeName: String
    get() = "Via$suffix"

internal fun JvmProjection.namedFunction(original: Name): Name =
    Name.identifier(original.asString() + suffix)

internal val JvmProjection.runtimeExportCallableId: CallableId
    get() = when (this) {
        JvmProjection.BLOCKING -> runtimeCallable("awaitSuspendProjection")
        else -> runtimeCallable("start${suffix}SuspendProjection")
    }

internal val JvmProjection.runtimeImportCallableId: CallableId
    get() = when (this) {
        JvmProjection.BLOCKING -> error("Blocking imports do not use a runtime await adapter")
        else -> runtimeCallable("await${suffix}SuspendProjection")
    }

internal val JvmProjection.cancellationCapability: String
    get() = when (this) {
        JvmProjection.BLOCKING -> "waiter-interruption-only"
        JvmProjection.COMPLETION_STAGE, JvmProjection.COMPLETABLE_FUTURE -> "completion-callback"
        JvmProjection.FUTURE -> "future-get"
        JvmProjection.NONE -> "none"
    }

internal fun JvmProjection.wrapIrType(
    pluginContext: IrPluginContext,
    type: IrType,
): IrType {
    if (this == JvmProjection.BLOCKING) return type
    val classId = ClassId.topLevel(
        FqName(
            when (this) {
                JvmProjection.COMPLETION_STAGE -> "java.util.concurrent.CompletionStage"
                JvmProjection.COMPLETABLE_FUTURE -> "java.util.concurrent.CompletableFuture"
                JvmProjection.FUTURE -> "java.util.concurrent.Future"
                else -> error("Projection $this has no wrapper type")
            },
        ),
    )
    val wrapper = pluginContext.referenceClass(classId)
        ?: error("Projection wrapper $classId was not found")
    return wrapper.typeWith(type)
}

private fun runtimeCallable(name: String): CallableId = CallableId(
    packageName = FqName("cn.chuanwise.kotlinsuspendprojection.runtime.jvm"),
    callableName = Name.identifier(name),
)
