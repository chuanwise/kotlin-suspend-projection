package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal interface CompilerOptionValue {
    val optionValue: String
}

internal enum class SelectionMode(override val optionValue: String) : CompilerOptionValue {
    ALL("all"),
    ANNOTATED("annotated"),
}

internal enum class JvmProjection(override val optionValue: String) : CompilerOptionValue {
    NONE("none"),
    BLOCKING("blocking"),
    COMPLETION_STAGE("completion-stage"),
    COMPLETABLE_FUTURE("completable-future"),
    FUTURE("future"),
}

internal enum class RawSuspendAbi(override val optionValue: String) : CompilerOptionValue {
    HIDDEN("hidden"),
    VISIBLE("visible"),
}

internal enum class DirectImplementationEnforcement(
    override val optionValue: String,
) : CompilerOptionValue {
    GUARDED_DEFAULT("guarded-default"),
    STRICT("strict"),
}

internal enum class UninstrumentedKotlin(override val optionValue: String) : CompilerOptionValue {
    WARNING("warning"),
    ERROR("error"),
    OFF("off"),
}

internal data class SuspendProjectionPluginConfiguration(
    val selectionMode: SelectionMode,
    val generatedTypesNamespace: String,
    val rawSuspendAbi: RawSuspendAbi,
    val sameNameCaller: JvmProjection,
    val directImplementation: JvmProjection,
    val directImplementationEnforcement: DirectImplementationEnforcement,
    val uninstrumentedKotlin: UninstrumentedKotlin,
    val blockingExportsEnabled: Boolean,
    val blockingEmitNamedCaller: Boolean,
    val blockingImportsEnabled: Boolean,
    val completionStageExportsEnabled: Boolean,
    val completionStageEmitNamedCaller: Boolean,
    val completionStageImportsEnabled: Boolean,
    val completableFutureExportsEnabled: Boolean,
    val completableFutureEmitNamedCaller: Boolean,
    val completableFutureImportsEnabled: Boolean,
    val futureExportsEnabled: Boolean,
    val futureEmitNamedCaller: Boolean,
    val futureImportsEnabled: Boolean,
    val emitCompatibilityGuard: Boolean,
    val emitInvalidPathGuard: Boolean,
) {
    val enabledImports: List<JvmProjection>
        get() = JvmProjection.entries.filter(::importsEnabled)

    val enabledExports: List<JvmProjection>
        get() = JvmProjection.entries.filter(::exportsEnabled)

    val hasOwnerSurface: Boolean
        get() = enabledExports.isNotEmpty() || directImplementation != JvmProjection.NONE

    val hasAnyProjection: Boolean
        get() = hasOwnerSurface || enabledImports.isNotEmpty()

    fun exportsEnabled(projection: JvmProjection): Boolean = when (projection) {
        JvmProjection.BLOCKING -> blockingExportsEnabled
        JvmProjection.COMPLETION_STAGE -> completionStageExportsEnabled
        JvmProjection.COMPLETABLE_FUTURE -> completableFutureExportsEnabled
        JvmProjection.FUTURE -> futureExportsEnabled
        JvmProjection.NONE -> false
    }

    fun emitNamedCaller(projection: JvmProjection): Boolean = when (projection) {
        JvmProjection.BLOCKING -> blockingEmitNamedCaller
        JvmProjection.COMPLETION_STAGE -> completionStageEmitNamedCaller
        JvmProjection.COMPLETABLE_FUTURE -> completableFutureEmitNamedCaller
        JvmProjection.FUTURE -> futureEmitNamedCaller
        JvmProjection.NONE -> false
    }

    fun importsEnabled(projection: JvmProjection): Boolean = when (projection) {
        JvmProjection.BLOCKING -> blockingImportsEnabled
        JvmProjection.COMPLETION_STAGE -> completionStageImportsEnabled
        JvmProjection.COMPLETABLE_FUTURE -> completableFutureImportsEnabled
        JvmProjection.FUTURE -> futureImportsEnabled
        JvmProjection.NONE -> false
    }
}

internal object SuspendProjectionConfigurationKeys {
    val SELECTION_MODE: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("suspend projection selection mode")
    val GENERATED_TYPES_NAMESPACE: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("suspend projection generated types namespace")
    val RAW_SUSPEND_ABI: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("raw suspend ABI visibility")
    val SAME_NAME_CALLER: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("same-name caller projection")
    val DIRECT_IMPLEMENTATION: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("direct implementation projection")
    val DIRECT_IMPLEMENTATION_ENFORCEMENT: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("direct implementation enforcement")
    val UNINSTRUMENTED_KOTLIN: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("uninstrumented Kotlin implementation policy")
    val BLOCKING_EXPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("blocking exports enabled")
    val BLOCKING_EMIT_NAMED_CALLER: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("blocking named caller enabled")
    val BLOCKING_IMPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("blocking imports enabled")
    val COMPLETION_STAGE_EXPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("completion stage exports enabled")
    val COMPLETION_STAGE_EMIT_NAMED_CALLER: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("completion stage named caller enabled")
    val COMPLETION_STAGE_IMPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("completion stage imports enabled")
    val COMPLETABLE_FUTURE_EXPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("completable future exports enabled")
    val COMPLETABLE_FUTURE_EMIT_NAMED_CALLER: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("completable future named caller enabled")
    val COMPLETABLE_FUTURE_IMPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("completable future imports enabled")
    val FUTURE_EXPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("future exports enabled")
    val FUTURE_EMIT_NAMED_CALLER: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("future named caller enabled")
    val FUTURE_IMPORTS_ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("future imports enabled")
    val EMIT_COMPATIBILITY_GUARD: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("emit compatibility guard")
    val EMIT_INVALID_PATH_GUARD: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("emit invalid path guard")
}

internal fun CompilerConfiguration.suspendProjectionConfiguration():
    SuspendProjectionPluginConfiguration = SuspendProjectionPluginConfiguration(
        selectionMode = enumValue(
            SuspendProjectionConfigurationKeys.SELECTION_MODE,
            SelectionMode.ANNOTATED,
        ),
        generatedTypesNamespace =
            this[SuspendProjectionConfigurationKeys.GENERATED_TYPES_NAMESPACE] ?: "Projections",
        rawSuspendAbi = enumValue(
            SuspendProjectionConfigurationKeys.RAW_SUSPEND_ABI,
            RawSuspendAbi.HIDDEN,
        ),
        sameNameCaller = enumValue(
            SuspendProjectionConfigurationKeys.SAME_NAME_CALLER,
            JvmProjection.BLOCKING,
        ),
        directImplementation = enumValue(
            SuspendProjectionConfigurationKeys.DIRECT_IMPLEMENTATION,
            JvmProjection.BLOCKING,
        ),
        directImplementationEnforcement = enumValue(
            SuspendProjectionConfigurationKeys.DIRECT_IMPLEMENTATION_ENFORCEMENT,
            DirectImplementationEnforcement.STRICT,
        ),
        uninstrumentedKotlin = enumValue(
            SuspendProjectionConfigurationKeys.UNINSTRUMENTED_KOTLIN,
            UninstrumentedKotlin.ERROR,
        ),
        blockingExportsEnabled =
            this[SuspendProjectionConfigurationKeys.BLOCKING_EXPORTS_ENABLED] ?: true,
        blockingEmitNamedCaller =
            this[SuspendProjectionConfigurationKeys.BLOCKING_EMIT_NAMED_CALLER] ?: false,
        blockingImportsEnabled =
            this[SuspendProjectionConfigurationKeys.BLOCKING_IMPORTS_ENABLED] ?: true,
        completionStageExportsEnabled =
            this[SuspendProjectionConfigurationKeys.COMPLETION_STAGE_EXPORTS_ENABLED] ?: false,
        completionStageEmitNamedCaller =
            this[SuspendProjectionConfigurationKeys.COMPLETION_STAGE_EMIT_NAMED_CALLER] ?: true,
        completionStageImportsEnabled =
            this[SuspendProjectionConfigurationKeys.COMPLETION_STAGE_IMPORTS_ENABLED] ?: false,
        completableFutureExportsEnabled =
            this[SuspendProjectionConfigurationKeys.COMPLETABLE_FUTURE_EXPORTS_ENABLED] ?: false,
        completableFutureEmitNamedCaller =
            this[SuspendProjectionConfigurationKeys.COMPLETABLE_FUTURE_EMIT_NAMED_CALLER] ?: true,
        completableFutureImportsEnabled =
            this[SuspendProjectionConfigurationKeys.COMPLETABLE_FUTURE_IMPORTS_ENABLED] ?: false,
        futureExportsEnabled =
            this[SuspendProjectionConfigurationKeys.FUTURE_EXPORTS_ENABLED] ?: false,
        futureEmitNamedCaller =
            this[SuspendProjectionConfigurationKeys.FUTURE_EMIT_NAMED_CALLER] ?: true,
        futureImportsEnabled =
            this[SuspendProjectionConfigurationKeys.FUTURE_IMPORTS_ENABLED] ?: false,
        emitCompatibilityGuard =
            this[SuspendProjectionConfigurationKeys.EMIT_COMPATIBILITY_GUARD] ?: true,
        emitInvalidPathGuard =
            this[SuspendProjectionConfigurationKeys.EMIT_INVALID_PATH_GUARD] ?: true,
    )

private inline fun <reified T> CompilerConfiguration.enumValue(
    key: CompilerConfigurationKey<String>,
    default: T,
): T where T : Enum<T>, T : CompilerOptionValue {
    val value = this[key] ?: return default
    return enumValues<T>().singleOrNull { it.optionValue == value }
        ?: error(
            "Unsupported value '$value'; expected one of " +
                enumValues<T>().joinToString { it.optionValue },
        )
}
