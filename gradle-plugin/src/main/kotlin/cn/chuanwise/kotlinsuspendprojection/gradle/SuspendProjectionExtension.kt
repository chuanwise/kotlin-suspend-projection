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
    public var enabled: Boolean = true
    public var emitNamedCaller: Boolean = true
}

public class BlockingImportsConfiguration {
    public var enabled: Boolean = true
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

public class DirectImplementationConfiguration {
    public var projection: JvmProjection = JvmProjection.NONE
    public var enforcement: DirectImplementationEnforcement =
        DirectImplementationEnforcement.GUARDED_DEFAULT
    public var uninstrumentedKotlin: UninstrumentedKotlin = UninstrumentedKotlin.WARNING
}

public class RuntimeGuardsConfiguration {
    public var compatibility: Boolean = true
    public var invalidPath: Boolean = true
}

public class JvmProjectionConfiguration {
    public var rawSuspendAbi: RawSuspendAbi = RawSuspendAbi.HIDDEN
    public var sameNameCaller: JvmProjection = JvmProjection.NONE
    public val directImplementation: DirectImplementationConfiguration =
        DirectImplementationConfiguration()
    public val blocking: BlockingProjectionConfiguration = BlockingProjectionConfiguration()
    public val runtimeGuards: RuntimeGuardsConfiguration = RuntimeGuardsConfiguration()

    public fun directImplementation(configure: DirectImplementationConfiguration.() -> Unit) {
        directImplementation.configure()
    }

    public fun blocking(configure: BlockingProjectionConfiguration.() -> Unit) {
        blocking.configure()
    }

    public fun runtimeGuards(configure: RuntimeGuardsConfiguration.() -> Unit) {
        runtimeGuards.configure()
    }
}

public class DependencyConfiguration {
    public var automatic: Boolean = true
}

public class VerificationConfiguration {
    public var enabled: Boolean = true
}

public open class SuspendProjectionExtension {
    public var enabled: Boolean = true
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

    public fun primary(projection: JvmProjection) {
        require(projection == JvmProjection.BLOCKING) {
            "Only the Blocking primary preset is implemented in this release"
        }
        jvm.sameNameCaller = projection
        jvm.directImplementation.projection = projection
        jvm.directImplementation.enforcement = DirectImplementationEnforcement.STRICT
        jvm.directImplementation.uninstrumentedKotlin = UninstrumentedKotlin.ERROR
        jvm.blocking.exports.enabled = true
        jvm.blocking.exports.emitNamedCaller = false
        jvm.blocking.imports.enabled = true
    }

    internal fun validate() {
        require(generatedTypes.layout == GeneratedTypeLayout.NESTED_NAMESPACE) {
            "Only generatedTypes.layout = NESTED_NAMESPACE is implemented"
        }
        require(generatedTypes.namespace.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
            "generatedTypes.namespace must be a valid JVM identifier"
        }
        require(jvm.sameNameCaller in SUPPORTED_POLICY_PROJECTIONS) {
            "Only NONE and BLOCKING are supported for jvm.sameNameCaller"
        }
        require(jvm.directImplementation.projection in SUPPORTED_POLICY_PROJECTIONS) {
            "Only NONE and BLOCKING are supported for jvm.directImplementation.projection"
        }
        require(jvm.blocking.imports.execution == BlockingExecution.DIRECT) {
            "Only blocking imports execution = DIRECT is implemented"
        }
        require(jvm.blocking.imports.interruption == BlockingInterruption.THROW_CHECKED) {
            "Only blocking imports interruption = THROW_CHECKED is implemented"
        }
        if (jvm.sameNameCaller == JvmProjection.BLOCKING) {
            require(jvm.blocking.exports.enabled) {
                "Blocking exports must be enabled when Blocking is the same-name caller"
            }
        }
        if (jvm.directImplementation.projection == JvmProjection.BLOCKING) {
            require(jvm.blocking.imports.enabled) {
                "Blocking imports must be enabled when Blocking is the direct implementation"
            }
        }
    }

    private companion object {
        val SUPPORTED_POLICY_PROJECTIONS: Set<JvmProjection> =
            setOf(JvmProjection.NONE, JvmProjection.BLOCKING)
    }
}
