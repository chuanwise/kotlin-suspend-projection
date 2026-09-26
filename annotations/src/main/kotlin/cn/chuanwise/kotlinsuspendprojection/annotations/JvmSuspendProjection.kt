package cn.chuanwise.kotlinsuspendprojection.annotations

/** JVM projection kinds that can be selected by [JvmSuspendProjection]. */
public enum class JvmProjectionType {
    BLOCKING,
    COMPLETION_STAGE,
    COMPLETABLE_FUTURE,
    FUTURE,
}

/**
 * Selects a file, public interface, or suspend function for projection generation.
 *
 * Multiple [projections] can be requested together. An empty list inherits projection kinds from
 * the nearest enclosing annotation, then from the Gradle project configuration. Set [enable] to
 * false to exclude the annotated scope; a more specific annotation can enable it again.
 */
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class JvmSuspendProjection(
    vararg val projections: JvmProjectionType,
    val enable: Boolean = true,
)
