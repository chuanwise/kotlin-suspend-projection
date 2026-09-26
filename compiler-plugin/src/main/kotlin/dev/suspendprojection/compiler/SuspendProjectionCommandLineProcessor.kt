package dev.suspendprojection.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal enum class DirectImplementationEnforcement(
    val optionValue: String,
) {
    GUARDED_DEFAULT("guarded-default"),
    STRICT("strict"),
    ;

    companion object {
        fun parse(value: String): DirectImplementationEnforcement =
            entries.singleOrNull { it.optionValue == value }
                ?: error(
                    "Unsupported direct implementation enforcement '$value'. " +
                        "Expected one of: ${entries.joinToString { it.optionValue }}",
                )
    }
}

internal object SuspendProjectionConfiguration {
    val DIRECT_IMPLEMENTATION_ENFORCEMENT: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("direct implementation enforcement")
    val EMIT_COMPATIBILITY_GUARD: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("emit compatibility guard")
    val EMIT_INVALID_PATH_GUARD: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("emit invalid path guard")
}

@OptIn(ExperimentalCompilerApi::class)
class SuspendProjectionCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = SuspendProjectionCompilerPluginRegistrar.PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption(
            optionName = DIRECT_IMPLEMENTATION_ENFORCEMENT_OPTION,
            valueDescription = "guarded-default|strict",
            description = "Selects the JVM direct implementation enforcement strategy",
            required = false,
            allowMultipleOccurrences = false,
        ),
        booleanOption(
            EMIT_COMPATIBILITY_GUARD_OPTION,
            "Emits generated-code/runtime compatibility checks",
        ),
        booleanOption(
            EMIT_INVALID_PATH_GUARD_OPTION,
            "Emits recursive invalid adapter-path checks",
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            DIRECT_IMPLEMENTATION_ENFORCEMENT_OPTION -> {
                DirectImplementationEnforcement.parse(value)
                configuration.put(
                    SuspendProjectionConfiguration.DIRECT_IMPLEMENTATION_ENFORCEMENT,
                    value,
                )
            }

            EMIT_COMPATIBILITY_GUARD_OPTION -> configuration.put(
                SuspendProjectionConfiguration.EMIT_COMPATIBILITY_GUARD,
                value.toStrictBoolean(EMIT_COMPATIBILITY_GUARD_OPTION),
            )

            EMIT_INVALID_PATH_GUARD_OPTION -> configuration.put(
                SuspendProjectionConfiguration.EMIT_INVALID_PATH_GUARD,
                value.toStrictBoolean(EMIT_INVALID_PATH_GUARD_OPTION),
            )

            else -> error("Unknown Kotlin Suspend Projection option: ${option.optionName}")
        }
    }

    private companion object {
        const val DIRECT_IMPLEMENTATION_ENFORCEMENT_OPTION =
            "directImplementationEnforcement"
        const val EMIT_COMPATIBILITY_GUARD_OPTION = "emitCompatibilityGuard"
        const val EMIT_INVALID_PATH_GUARD_OPTION = "emitInvalidPathGuard"

        fun booleanOption(name: String, description: String): CliOption = CliOption(
            optionName = name,
            valueDescription = "true|false",
            description = description,
            required = false,
            allowMultipleOccurrences = false,
        )

        fun String.toStrictBoolean(optionName: String): Boolean = when (this) {
            "true" -> true
            "false" -> false
            else -> error("Option '$optionName' expects true or false, but was '$this'")
        }
    }
}
