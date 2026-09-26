package cn.chuanwise.kotlinsuspendprojection.runtime.jvm

import cn.chuanwise.kotlinsuspendprojection.runtime.SuspendProjectionGeneratedCode

/** JVM properties that can operationally bypass emitted runtime guards. */
public object SuspendProjectionRuntimeProperties {
    public const val ALL_GUARDS: String = "kotlin.suspend.projection.runtime.guards"
    public const val COMPATIBILITY_GUARD: String =
        "kotlin.suspend.projection.runtime.compatibility"
    public const val INVALID_PATH_GUARD: String =
        "kotlin.suspend.projection.runtime.invalidPath"
}

/** Entry points called directly by generated JVM adapter code. */
public object SuspendProjectionRuntimeGuards {
    private val activePaths: ThreadLocal<MutableSet<String>> = ThreadLocal()

    @JvmStatic
    public fun requireCompatible(generatedCodeVersion: Int) {
        if (isEnabled(SuspendProjectionRuntimeProperties.COMPATIBILITY_GUARD)) {
            SuspendProjectionGeneratedCode.requireSupported(generatedCodeVersion)
        }
    }

    @JvmStatic
    public fun enterAdapterPath(pathId: String): AutoCloseable {
        require(pathId.isNotBlank()) { "Generated adapter path ID must not be blank" }

        if (!isEnabled(SuspendProjectionRuntimeProperties.INVALID_PATH_GUARD)) {
            return NoOpGuardToken
        }

        val paths = activePaths.get() ?: LinkedHashSet<String>().also(activePaths::set)
        if (!paths.add(pathId)) {
            throw InvalidSuspendProjectionPathException(pathId)
        }
        return ActiveGuardToken(pathId, paths)
    }

    private fun isEnabled(specificProperty: String): Boolean =
        propertyIsEnabled(SuspendProjectionRuntimeProperties.ALL_GUARDS) &&
            propertyIsEnabled(specificProperty)

    private fun propertyIsEnabled(name: String): Boolean =
        try {
            !System.getProperty(name).equals("false", ignoreCase = true)
        } catch (_: SecurityException) {
            true
        }

    private object NoOpGuardToken : AutoCloseable {
        override fun close() = Unit
    }

    private class ActiveGuardToken(
        private val pathId: String,
        private val paths: MutableSet<String>,
    ) : AutoCloseable {
        private var closed: Boolean = false

        override fun close() {
            if (closed) return
            closed = true
            paths.remove(pathId)
            if (paths.isEmpty()) {
                activePaths.remove()
            }
        }
    }
}

public class InvalidSuspendProjectionPathException(
    public val pathId: String,
) : IllegalStateException("Suspend projection adapter path re-entered recursively: $pathId")
