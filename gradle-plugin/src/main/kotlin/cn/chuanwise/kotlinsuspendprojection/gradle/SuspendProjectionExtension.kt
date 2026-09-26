package cn.chuanwise.kotlinsuspendprojection.gradle

public enum class SelectionMode(internal val compilerValue: String) {
    ALL("all"),
    ANNOTATED("annotated"),
}

public enum class GeneratedTypeLayout(internal val compilerValue: String) {
    NESTED_NAMESPACE("nested-namespace"),
    NESTED_DIRECT("nested-direct"),
    TOP_LEVEL("top-level"),
}

public enum class JvmProjection(internal val compilerValue: String) {
    NONE("none"),
    BLOCKING("blocking"),
    COMPLETION_STAGE("completion-stage"),
    COMPLETABLE_FUTURE("completable-future"),
    FUTURE("future"),
    CONTINUATION("continuation"),
}

public enum class RawSuspendAbi(internal val compilerValue: String) {
    HIDDEN("hidden"),
    VISIBLE("visible"),
}

public enum class DirectImplementationEnforcement(internal val compilerValue: String) {
    GUARDED_DEFAULT("guarded-default"),
    STRICT("strict"),
}

public enum class UninstrumentedKotlin(internal val compilerValue: String) {
    WARNING("warning"),
    ERROR("error"),
    OFF("off"),
}

public enum class BlockingExecution(internal val compilerValue: String) {
    DIRECT("direct"),
    IO("io"),
    CUSTOM("custom"),
}

public enum class BlockingInterruption(internal val compilerValue: String) {
    THROW_CHECKED("throw-checked"),
    CANCEL_AND_THROW_UNCHECKED("cancel-and-throw-unchecked"),
    WAIT_UNINTERRUPTIBLY_RESTORE("wait-uninterruptibly-restore"),
    CUSTOM("custom"),
}

public class SelectionConfiguration {
    public var mode: SelectionMode = SelectionMode.ANNOTATED
}

public class GeneratedTypesConfiguration {
    public var layout: GeneratedTypeLayout = GeneratedTypeLayout.NESTED_NAMESPACE
    public var namespace: String = "Projections"
}

public class BlockingExportsConfiguration {
    public var enable: Boolean = true
    public var emitNamedCaller: Boolean = false
}

public class BlockingImportsConfiguration {
    public var enable: Boolean = true
    public var execution: BlockingExecution = BlockingExecution.DIRECT
    public var interruption: BlockingInterruption = BlockingInterruption.THROW_CHECKED
}

public class BlockingProjectionConfiguration {
    public val exports: BlockingExportsConfiguration = BlockingExportsConfiguration()
    public val imports: BlockingImportsConfiguration = BlockingImportsConfiguration()

    public fun exports(configure: BlockingExportsConfiguration.() -> Unit) {
        exports.configure()
    }

    public fun imports(configure: BlockingImportsConfiguration.() -> Unit) {
        imports.configure()
    }
}

public class AsyncExportsConfiguration {
    public var enable: Boolean = false
    public var emitNamedCaller: Boolean = true
}

public class AsyncImportsConfiguration {
    public var enable: Boolean = false
}

public open class AsyncProjectionConfiguration {
    public val exports: AsyncExportsConfiguration = AsyncExportsConfiguration()
    public val imports: AsyncImportsConfiguration = AsyncImportsConfiguration()

    public fun exports(configure: AsyncExportsConfiguration.() -> Unit) {
        exports.configure()
    }

    public fun imports(configure: AsyncImportsConfiguration.() -> Unit) {
        imports.configure()
    }
}

public class DirectImplementationConfiguration {
    public var projection: JvmProjection = JvmProjection.BLOCKING
    public var enforcement: DirectImplementationEnforcement =
        DirectImplementationEnforcement.STRICT
    public var uninstrumentedKotlin: UninstrumentedKotlin = UninstrumentedKotlin.ERROR
}

public class RuntimeGuardsConfiguration {
    public var compatibility: Boolean = true
    public var invalidPath: Boolean = true
}

public class JvmProjectionConfiguration {
    public var rawSuspendAbi: RawSuspendAbi = RawSuspendAbi.HIDDEN
    public var sameNameCaller: JvmProjection = JvmProjection.BLOCKING
    public val directImplementation: DirectImplementationConfiguration =
        DirectImplementationConfiguration()
    public val blocking: BlockingProjectionConfiguration = BlockingProjectionConfiguration()
    public val completionStage: AsyncProjectionConfiguration = AsyncProjectionConfiguration()
    public val completableFuture: AsyncProjectionConfiguration = AsyncProjectionConfiguration()
    public val future: AsyncProjectionConfiguration = AsyncProjectionConfiguration()
    public val runtimeGuards: RuntimeGuardsConfiguration = RuntimeGuardsConfiguration()

    public fun directImplementation(configure: DirectImplementationConfiguration.() -> Unit) {
        directImplementation.configure()
    }

    public fun blocking(configure: BlockingProjectionConfiguration.() -> Unit) {
        blocking.configure()
    }

    public fun completionStage(configure: AsyncProjectionConfiguration.() -> Unit) {
        completionStage.configure()
    }

    public fun completableFuture(configure: AsyncProjectionConfiguration.() -> Unit) {
        completableFuture.configure()
    }

    public fun future(configure: AsyncProjectionConfiguration.() -> Unit) {
        future.configure()
    }

    public fun runtimeGuards(configure: RuntimeGuardsConfiguration.() -> Unit) {
        runtimeGuards.configure()
    }
}

public class DependencyConfiguration {
    public var automatic: Boolean = true
}

public class VerificationConfiguration {
    public var enable: Boolean = true
}

public open class SuspendProjectionExtension {
    public var enable: Boolean = true
    public var projections: Set<JvmProjection> = setOf(JvmProjection.BLOCKING)
        set(value) {
            require(value.all { it in SUPPORTED_POLICY_PROJECTIONS && it != JvmProjection.NONE }) {
                "Project projections support Blocking, CompletionStage, CompletableFuture, and Future"
            }
            field = value.toSet()
            SUPPORTED_POLICY_PROJECTIONS.filterNot { it == JvmProjection.NONE }.forEach { projection ->
                projectionConfiguration(projection).apply {
                    val enable = projection in field
                    setExportsEnabled(enable)
                    setImportsEnabled(enable)
                    setEmitNamedCaller(projection != primary)
                }
            }
        }
    public var primary: JvmProjection = JvmProjection.BLOCKING
        set(value) {
            require(value in SUPPORTED_POLICY_PROJECTIONS) {
                "Primary projection must be None, Blocking, CompletionStage, CompletableFuture, or Future"
            }
            val previous = field
            field = value
            if (previous != JvmProjection.NONE && previous in projections) {
                projectionConfiguration(previous).setEmitNamedCaller(true)
            }
            jvm.sameNameCaller = value
            jvm.directImplementation.projection = value
            if (value != JvmProjection.NONE) {
                projections = projections + value
                projectionConfiguration(value).setEmitNamedCaller(false)
            }
            jvm.directImplementation.enforcement = DirectImplementationEnforcement.STRICT
            jvm.directImplementation.uninstrumentedKotlin = UninstrumentedKotlin.ERROR
        }
    public val selection: SelectionConfiguration = SelectionConfiguration()
    public val generatedTypes: GeneratedTypesConfiguration = GeneratedTypesConfiguration()
    public val jvm: JvmProjectionConfiguration = JvmProjectionConfiguration()
    public val dependencies: DependencyConfiguration = DependencyConfiguration()
    public val verification: VerificationConfiguration = VerificationConfiguration()

    public fun selection(configure: SelectionConfiguration.() -> Unit) {
        selection.configure()
    }

    public fun generatedTypes(configure: GeneratedTypesConfiguration.() -> Unit) {
        generatedTypes.configure()
    }

    public fun jvm(configure: JvmProjectionConfiguration.() -> Unit) {
        jvm.configure()
    }

    public fun dependencies(configure: DependencyConfiguration.() -> Unit) {
        dependencies.configure()
    }

    public fun verification(configure: VerificationConfiguration.() -> Unit) {
        verification.configure()
    }

    internal fun validate() {
        require(generatedTypes.layout == GeneratedTypeLayout.NESTED_NAMESPACE) {
            "Only generatedTypes.layout = NESTED_NAMESPACE is implemented"
        }
        require(generatedTypes.namespace.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
            "generatedTypes.namespace must be a valid JVM identifier"
        }
        require(jvm.sameNameCaller in SUPPORTED_POLICY_PROJECTIONS)
        require(jvm.directImplementation.projection in SUPPORTED_POLICY_PROJECTIONS)
        require(jvm.blocking.imports.execution == BlockingExecution.DIRECT) {
            "Only blocking imports execution = DIRECT is implemented"
        }
        require(jvm.blocking.imports.interruption == BlockingInterruption.THROW_CHECKED) {
            "Only blocking imports interruption = THROW_CHECKED is implemented"
        }
        if (jvm.sameNameCaller != JvmProjection.NONE) {
            require(projectionConfiguration(jvm.sameNameCaller).exportsEnabled()) {
                "${jvm.sameNameCaller} exports.enable must be true for the same-name caller"
            }
        }
        if (jvm.directImplementation.projection != JvmProjection.NONE) {
            require(projectionConfiguration(jvm.directImplementation.projection).importsEnabled()) {
                "${jvm.directImplementation.projection} imports.enable must be true for the " +
                    "direct implementation"
            }
        }
    }

    private fun projectionConfiguration(projection: JvmProjection): ProjectionConfigurationView =
        when (projection) {
            JvmProjection.BLOCKING -> ProjectionConfigurationView(
                exportsEnabled = { jvm.blocking.exports.enable },
                setExportsEnabled = { jvm.blocking.exports.enable = it },
                setEmitNamedCaller = { jvm.blocking.exports.emitNamedCaller = it },
                importsEnabled = { jvm.blocking.imports.enable },
                setImportsEnabled = { jvm.blocking.imports.enable = it },
            )
            JvmProjection.COMPLETION_STAGE -> ProjectionConfigurationView(
                exportsEnabled = { jvm.completionStage.exports.enable },
                setExportsEnabled = { jvm.completionStage.exports.enable = it },
                setEmitNamedCaller = { jvm.completionStage.exports.emitNamedCaller = it },
                importsEnabled = { jvm.completionStage.imports.enable },
                setImportsEnabled = { jvm.completionStage.imports.enable = it },
            )
            JvmProjection.COMPLETABLE_FUTURE -> ProjectionConfigurationView(
                exportsEnabled = { jvm.completableFuture.exports.enable },
                setExportsEnabled = { jvm.completableFuture.exports.enable = it },
                setEmitNamedCaller = { jvm.completableFuture.exports.emitNamedCaller = it },
                importsEnabled = { jvm.completableFuture.imports.enable },
                setImportsEnabled = { jvm.completableFuture.imports.enable = it },
            )
            JvmProjection.FUTURE -> ProjectionConfigurationView(
                exportsEnabled = { jvm.future.exports.enable },
                setExportsEnabled = { jvm.future.exports.enable = it },
                setEmitNamedCaller = { jvm.future.exports.emitNamedCaller = it },
                importsEnabled = { jvm.future.imports.enable },
                setImportsEnabled = { jvm.future.imports.enable = it },
            )
            else -> error("Projection $projection does not support generated callers or imports")
        }

    private data class ProjectionConfigurationView(
        val exportsEnabled: () -> Boolean,
        val setExportsEnabled: (Boolean) -> Unit,
        val setEmitNamedCaller: (Boolean) -> Unit,
        val importsEnabled: () -> Boolean,
        val setImportsEnabled: (Boolean) -> Unit,
    )

    private companion object {
        val SUPPORTED_POLICY_PROJECTIONS: Set<JvmProjection> =
            setOf(
                JvmProjection.NONE,
                JvmProjection.BLOCKING,
                JvmProjection.COMPLETION_STAGE,
                JvmProjection.COMPLETABLE_FUTURE,
                JvmProjection.FUTURE,
            )
    }
}
