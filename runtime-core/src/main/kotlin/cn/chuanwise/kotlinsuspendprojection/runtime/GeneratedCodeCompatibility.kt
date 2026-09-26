package cn.chuanwise.kotlinsuspendprojection.runtime

/** Generated-code versions understood by this runtime artifact. */
public object SuspendProjectionGeneratedCode {
    public const val MIN_SUPPORTED_VERSION: Int = 1
    public const val MAX_SUPPORTED_VERSION: Int = 1

    @JvmStatic
    public fun isSupported(version: Int): Boolean =
        version in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION

    @JvmStatic
    public fun requireSupported(version: Int) {
        if (!isSupported(version)) {
            throw IncompatibleSuspendProjectionRuntimeException(
                generatedCodeVersion = version,
                minimumSupportedVersion = MIN_SUPPORTED_VERSION,
                maximumSupportedVersion = MAX_SUPPORTED_VERSION,
            )
        }
    }
}

/** Stable capability IDs reported by generated adapters and runtime entry points. */
public object SuspendProjectionCancellationCapabilities {
    public const val NONE: String = "none"
    public const val WAITER_INTERRUPTION_ONLY: String = "waiter-interruption-only"
}

public class IncompatibleSuspendProjectionRuntimeException(
    public val generatedCodeVersion: Int,
    public val minimumSupportedVersion: Int,
    public val maximumSupportedVersion: Int,
) : IllegalStateException(
    "Generated suspend projection code version $generatedCodeVersion is not supported by " +
        "this runtime; supported range is $minimumSupportedVersion..$maximumSupportedVersion",
)
