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
    val emitCompatibilityGuard: Boolean,
    val emitInvalidPathGuard: Boolean,
) {
    val hasBlockingOwnerSurface: Boolean
        get() = blockingExportsEnabled || directImplementation == JvmProjection.BLOCKING

    val hasAnyProjection: Boolean
        get() = hasBlockingOwnerSurface || blockingImportsEnabled
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
            JvmProjection.NONE,
        ),
        directImplementation = enumValue(
            SuspendProjectionConfigurationKeys.DIRECT_IMPLEMENTATION,
            JvmProjection.NONE,
        ),
        directImplementationEnforcement = enumValue(
            SuspendProjectionConfigurationKeys.DIRECT_IMPLEMENTATION_ENFORCEMENT,
            DirectImplementationEnforcement.GUARDED_DEFAULT,
        ),
        uninstrumentedKotlin = enumValue(
            SuspendProjectionConfigurationKeys.UNINSTRUMENTED_KOTLIN,
            UninstrumentedKotlin.WARNING,
        ),
        blockingExportsEnabled =
            this[SuspendProjectionConfigurationKeys.BLOCKING_EXPORTS_ENABLED] ?: true,
        blockingEmitNamedCaller =
            this[SuspendProjectionConfigurationKeys.BLOCKING_EMIT_NAMED_CALLER] ?: true,
        blockingImportsEnabled =
            this[SuspendProjectionConfigurationKeys.BLOCKING_IMPORTS_ENABLED] ?: true,
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
