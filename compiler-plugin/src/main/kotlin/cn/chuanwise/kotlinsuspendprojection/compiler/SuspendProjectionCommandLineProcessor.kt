package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
class SuspendProjectionCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = SuspendProjectionCompilerPluginRegistrar.PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        option(SELECTION_MODE, "all|annotated", "Selects declarations eligible for projection"),
        option(GENERATED_TYPES_NAMESPACE, "identifier", "Generated nested namespace name"),
        option(RAW_SUSPEND_ABI, "hidden|visible", "Controls Java visibility of raw suspend ABI"),
        option(SAME_NAME_CALLER, "none|blocking", "Selects the same-name caller projection"),
        option(DIRECT_IMPLEMENTATION, "none|blocking", "Selects direct implementation projection"),
        option(
            DIRECT_IMPLEMENTATION_ENFORCEMENT,
            "guarded-default|strict",
            "Selects direct implementation enforcement",
        ),
        option(
            UNINSTRUMENTED_KOTLIN,
            "warning|error|off",
            "Selects the uninstrumented Kotlin implementation diagnostic",
        ),
        booleanOption(BLOCKING_EXPORTS_ENABLED, "Enables Blocking caller exports"),
        booleanOption(BLOCKING_EMIT_NAMED_CALLER, "Emits projection-named Blocking callers"),
        booleanOption(BLOCKING_IMPORTS_ENABLED, "Enables ViaBlocking implementation imports"),
        booleanOption(EMIT_COMPATIBILITY_GUARD, "Emits generated-code compatibility checks"),
        booleanOption(EMIT_INVALID_PATH_GUARD, "Emits recursive invalid adapter-path checks"),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            SELECTION_MODE -> putEnum<SelectionMode>(configuration, option, value)
            GENERATED_TYPES_NAMESPACE -> {
                require(value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
                    "Option '$GENERATED_TYPES_NAMESPACE' expects a JVM identifier"
                }
                configuration.put(SuspendProjectionConfigurationKeys.GENERATED_TYPES_NAMESPACE, value)
            }
            RAW_SUSPEND_ABI -> putEnum<RawSuspendAbi>(configuration, option, value)
            SAME_NAME_CALLER -> putEnum<JvmProjection>(configuration, option, value)
            DIRECT_IMPLEMENTATION -> putEnum<JvmProjection>(configuration, option, value)
            DIRECT_IMPLEMENTATION_ENFORCEMENT ->
                putEnum<DirectImplementationEnforcement>(configuration, option, value)
            UNINSTRUMENTED_KOTLIN -> putEnum<UninstrumentedKotlin>(configuration, option, value)
            BLOCKING_EXPORTS_ENABLED -> putBoolean(
                configuration,
                SuspendProjectionConfigurationKeys.BLOCKING_EXPORTS_ENABLED,
                option.optionName,
                value,
            )
            BLOCKING_EMIT_NAMED_CALLER -> putBoolean(
                configuration,
                SuspendProjectionConfigurationKeys.BLOCKING_EMIT_NAMED_CALLER,
                option.optionName,
                value,
            )
            BLOCKING_IMPORTS_ENABLED -> putBoolean(
                configuration,
                SuspendProjectionConfigurationKeys.BLOCKING_IMPORTS_ENABLED,
                option.optionName,
                value,
            )
            EMIT_COMPATIBILITY_GUARD -> putBoolean(
                configuration,
                SuspendProjectionConfigurationKeys.EMIT_COMPATIBILITY_GUARD,
                option.optionName,
                value,
            )
            EMIT_INVALID_PATH_GUARD -> putBoolean(
                configuration,
                SuspendProjectionConfigurationKeys.EMIT_INVALID_PATH_GUARD,
                option.optionName,
                value,
            )
            else -> error("Unknown Kotlin Suspend Projection option: ${option.optionName}")
        }
    }

    private inline fun <reified T> putEnum(
        configuration: CompilerConfiguration,
        option: AbstractCliOption,
        value: String,
    ) where T : Enum<T>, T : CompilerOptionValue {
        require(enumValues<T>().any { it.optionValue == value }) {
            "Option '${option.optionName}' expects one of: " +
                enumValues<T>().joinToString { it.optionValue }
        }
        val key = when (option.optionName) {
            SELECTION_MODE -> SuspendProjectionConfigurationKeys.SELECTION_MODE
            RAW_SUSPEND_ABI -> SuspendProjectionConfigurationKeys.RAW_SUSPEND_ABI
            SAME_NAME_CALLER -> SuspendProjectionConfigurationKeys.SAME_NAME_CALLER
            DIRECT_IMPLEMENTATION -> SuspendProjectionConfigurationKeys.DIRECT_IMPLEMENTATION
            DIRECT_IMPLEMENTATION_ENFORCEMENT ->
                SuspendProjectionConfigurationKeys.DIRECT_IMPLEMENTATION_ENFORCEMENT
            UNINSTRUMENTED_KOTLIN -> SuspendProjectionConfigurationKeys.UNINSTRUMENTED_KOTLIN
            else -> error("No enum configuration key for ${option.optionName}")
        }
        configuration.put(key, value)
    }

    private companion object {
        const val SELECTION_MODE = "selectionMode"
        const val GENERATED_TYPES_NAMESPACE = "generatedTypesNamespace"
        const val RAW_SUSPEND_ABI = "rawSuspendAbi"
        const val SAME_NAME_CALLER = "sameNameCaller"
        const val DIRECT_IMPLEMENTATION = "directImplementation"
        const val DIRECT_IMPLEMENTATION_ENFORCEMENT = "directImplementationEnforcement"
        const val UNINSTRUMENTED_KOTLIN = "uninstrumentedKotlin"
        const val BLOCKING_EXPORTS_ENABLED = "blockingExportsEnabled"
        const val BLOCKING_EMIT_NAMED_CALLER = "blockingEmitNamedCaller"
        const val BLOCKING_IMPORTS_ENABLED = "blockingImportsEnabled"
        const val EMIT_COMPATIBILITY_GUARD = "emitCompatibilityGuard"
        const val EMIT_INVALID_PATH_GUARD = "emitInvalidPathGuard"

        fun option(name: String, valueDescription: String, description: String): CliOption =
            CliOption(name, valueDescription, description, required = false)

        fun booleanOption(name: String, description: String): CliOption =
            option(name, "true|false", description)

        fun putBoolean(
            configuration: CompilerConfiguration,
            key: CompilerConfigurationKey<Boolean>,
            optionName: String,
            value: String,
        ) {
            configuration.put(
                key,
                when (value) {
                    "true" -> true
                    "false" -> false
                    else -> error("Option '$optionName' expects true or false, but was '$value'")
                },
            )
        }
    }
}
