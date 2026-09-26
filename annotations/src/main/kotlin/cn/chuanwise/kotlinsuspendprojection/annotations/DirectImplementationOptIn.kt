package cn.chuanwise.kotlinsuspendprojection.annotations

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Implementing this projected type without Kotlin Suspend Projection tooling " +
        "may omit required platform bridges.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class UninstrumentedProjectionImplementationWarning

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Implementing this projected type requires Kotlin Suspend Projection tooling " +
        "to verify overrides and generate platform bridges.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class UninstrumentedProjectionImplementationError
